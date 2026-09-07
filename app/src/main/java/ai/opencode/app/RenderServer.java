package ai.opencode.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * P35 — the render endpoint: the browser the agent can use. The sandbox
 * has no browser and cannot have one (a phone-sized rootfs must not grow
 * a 300 MB chromium — that is the long-term-stability gamble the user
 * refused), but the sandbox and the app share the device loopback, and
 * the app already HAS a full browser engine: the WebView the canvas
 * renders in. So the app serves it.
 *
 * One tiny HTTP server on 127.0.0.1 (never a wide interface), one route:
 *
 *   POST /render   header X-Render-Key: <per-boot token>
 *                  body {"file":"RELATIVE/path.html","describe":true|false}
 *   reply          {"ok":true,"verdict":"pass|fail","console":[...],
 *                   "outline":{...},"notes":[...],"look":"...",...}
 *
 * The token gates every request (a per-boot secret the agent learns from
 * the render note / canvas clause — no other local process can drive the
 * renderer), the path guard in RenderCheck keeps every page inside the
 * served project, and the renderer is the sandboxed offscreen WebView:
 * JS on, file + network access off. The endpoint is independent of the
 * opencode server's lifecycle — a render check never races a sandbox
 * restart. Pure decision logic lives in RenderCheck (JVM-pinned); this
 * file is the socket half.
 */
public final class RenderServer {

    /** ServerService's diag channel, injected (no hard dependency). */
    public interface Diag { void log(String event, String detail); }

    private static volatile ServerSocket sock;
    private static volatile Thread loop;
    private static volatile int port = -1;
    private static volatile String token = null;
    private static volatile File root;
    private static volatile Context appCtx;
    private static volatile Diag diag;

    private RenderServer() {}

    /**
     * Idempotent: one server per app process. Re-calls (respawns, project
     * switches) just refresh the served root. Binds the first free port
     * of the RenderCheck ladder, loopback only.
     */
    public static synchronized void ensureStarted(Context c, File projectRoot,
                                                  Diag d) {
        root = projectRoot;
        diag = d;
        if (sock != null && !sock.isClosed()) return;
        appCtx = c.getApplicationContext();
        token = newToken();
        for (int i = 0; i < RenderCheck.PORT_SPAN; i++) {
            int p = RenderCheck.PORT_BASE + i;
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), p), 8);
                sock = s;
                port = p;
                break;
            } catch (Exception e) {
                // port taken — try the next rung of the ladder
            }
        }
        if (sock == null) {
            port = -1;
            if (diag != null) diag.log("render",
                    "no bindable port in the ladder — render checks off");
            return;
        }
        loop = new Thread(RenderServer::acceptLoop, "oc-render");
        loop.setDaemon(true);
        loop.start();
        if (diag != null) diag.log("render",
                "render checks on 127.0.0.1:" + port + " (root: "
                + (root == null ? "none" : root.getName()) + ")");
    }

    /** The served project root (per spawn / deck switch). */
    public static void setRoot(File f) { root = f; }

    /** Full stop (service destroyed). */
    public static synchronized void stop() {
        try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        sock = null;
        port = -1;
        loop = null;
    }

    /** The live port (−1 when the endpoint is off) — note/clause gating. */
    public static int port() { return port; }

    /** The per-boot bearer token for X-Render-Key. */
    public static String token() { return token; }

    // ------------------------------------------------- accept + handle

    private static void acceptLoop() {
        ServerSocket s = sock;
        while (s != null && !s.isClosed()) {
            final Socket conn;
            try {
                conn = s.accept();
            } catch (Throwable e) {
                return;   // closed — the endpoint is stopping
            }
            try {
                handle(conn);   // serial: one render at a time by design
            } catch (Throwable t) {
                quiet(conn);
            }
        }
    }

    private static void handle(final Socket conn) {
        try {
            conn.setSoTimeout(25_000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8),
                    8192);
            String line = in.readLine();
            if (line == null || !line.startsWith("POST /render ")) {
                respond(conn, 404, "{\"ok\":false,\"error\":\"route\"}");
                return;
            }
            long clen = 0;
            String key = null;
            int hdr = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (++hdr > 64) {
                    respond(conn, 400, "{\"ok\":false,\"error\":\"headers\"}");
                    return;
                }
                String l = line.toLowerCase(Locale.US);
                if (l.startsWith("content-length:")) {
                    try { clen = Long.parseLong(line.substring(15).trim()); }
                    catch (Exception ignored) { }
                } else if (l.startsWith("x-render-key:")) {
                    key = line.substring(13).trim();
                }
            }
            if (key == null || token == null || !MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8))) {
                respond(conn, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }
            if (clen <= 0 || clen > 8192) {
                respond(conn, 400, "{\"ok\":false,\"error\":\"body\"}");
                return;
            }
            char[] buf = new char[(int) clen];
            int off = 0;
            while (off < clen) {
                int r = in.read(buf, off, (int) clen - off);
                if (r < 0) break;
                off += r;
            }
            Map<String, Object> body = Json.obj(Json.parse(new String(buf, 0, off)));
            String rel = body == null ? null : Json.str(body, "file");
            Object dv = body == null ? null : body.get("describe");
            boolean describe = Boolean.TRUE.equals(dv)
                    || "true".equals(String.valueOf(dv));

            RenderCheck.Outcome oc = RenderCheck.validate(root, rel);
            if (!oc.ok) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", false);
                m.put("error", oc.error);
                m.put("detail", oc.detail);
                respond(conn, 422, Json.write(m));
                return;
            }
            if (appCtx == null) {
                respond(conn, 500, "{\"ok\":false,\"error\":\"no-context\"}");
                return;
            }
            HtmlRenderer.render(appCtx, oc.file, describe, (rep) ->
                    new Thread(() -> respond(conn, 200, Json.write(rep)),
                            "oc-render-reply").start());
        } catch (Throwable t) {
            quiet(conn);
        }
    }

    // ------------------------------------------------- plumbing

    private static void respond(Socket s, int code, String body) {
        try {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " ERR")
                    + "\r\nContent-Type: application/json\r\nContent-Length: "
                    + b.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            os.write(b);
            os.flush();
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static void quiet(Socket s) {
        try { s.close(); } catch (Throwable ignored) {}
    }

    /** 16 hex chars of per-boot randomness. */
    private static String newToken() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(16);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
