package ai.opencode.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * P10 self-test: a mock of the small slice of the opencode server API the
 * app talks to, running on the real port (127.0.0.1:4096). Raw-socket HTTP
 * (no JDK httpserver — the android.jar compile classpath hides it). Lets
 * the JVM test suite verify the permission-reply round trip END TO END —
 * the exact HTTP the shipped v1.18.25 binary expects:
 *
 *   POST /permission/{requestID}/reply   body {"reply":"once"|"always"|"reject"}
 *   GET  /permission                     → pending list
 *   GET  /config/providers               → 200 (health)
 */
final class OcTestServer {

    static final class Hit {
        final String method, path, body;
        Hit(String m, String p, String b) { method = m; path = p; body = b; }
    }

    final ConcurrentLinkedQueue<Hit> hits = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private Thread acceptor;
    private ServerSocket socket;

    void start() throws Exception {
        socket = new ServerSocket(Api.PORT, 50, java.net.InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptor = new Thread(() -> {
            while (running) {
                try (Socket s = socket.accept()) {
                    s.setSoTimeout(8000);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String line = in.readLine();
                    if (line == null) continue;
                    String[] req = line.split(" ");
                    String method = req.length > 0 ? req[0] : "GET";
                    String path = req.length > 1 ? req[1] : "/";
                    int cut = path.indexOf('?');
                    if (cut >= 0) path = path.substring(0, cut);
                    int contentLen = 0;
                    String h;
                    while ((h = in.readLine()) != null && !h.isEmpty()) {
                        if (h.toLowerCase().startsWith("content-length:")) {
                            contentLen = Integer.parseInt(h.substring(15).trim());
                        }
                    }
                    StringBuilder body = new StringBuilder();
                    for (int i = 0; i < contentLen; i++) {
                        int c = in.read();
                        if (c < 0) break;
                        body.append((char) c);
                    }
                    hits.add(new Hit(method, path, body.toString()));
                    String resp = responseFor(method, path);
                    byte[] out = resp.getBytes(StandardCharsets.UTF_8);
                    OutputStream w = s.getOutputStream();
                    w.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: " + out.length + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    w.write(out);
                    w.flush();
                } catch (Exception e) {
                    if (running) { /* accept loop resilience */ }
                }
            }
        }, "oc-test-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    /** All hits on a path prefix (newest last), regardless of method. */
    List<Hit> hitsFor(String prefix) {
        List<Hit> out = new ArrayList<>();
        for (Hit h : hits) if (h.path.startsWith(prefix)) out.add(h);
        return out;
    }

    private String responseFor(String method, String path) {
        if (path.startsWith("/permission")) return "[]";
        if (path.startsWith("/config/providers")) return "{\"providers\":{}}";
        if (path.startsWith("/project")) return "{\"projects\":[]}";
        return "{}";
    }
}
