package ai.opencode.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal loopback HTTP client for the in-process opencode server.
 * The P0 probe verified 127.0.0.1:4096 end-to-end on-device, so 4096 stays
 * the well-known default — but P19 no longer MARRIES the app to it: the
 * server is spawned with an ephemeral port (—port 0) and the actual port
 * is parsed from the child's stdout listen line. Reason: the field crash
 * where the app process was killed, its orphaned server child kept holding
 * 4096 (occasionally un-killable mid-IO), and every respawn then died with
 * EADDRINUSE until the user cold-booted the PHONE. A server that can bind
 * ANY free port cannot be dead-locked by a squatter.
 */
public final class Api {

    private Api() {}

    public static final String HOST = "127.0.0.1";
    /** Preferred/default port; volatile: the supervisor rewrites it when the
     *  spawned server binds an ephemeral port. All requests read it live. */
    public static volatile int PORT = 4096;

    /** Supervisor-only: adopt the port the running server actually bound. */
    public static void setPort(int p) { if (p > 0 && p <= 65535) PORT = p; }

    public static String baseUrl() {
        return "http://" + HOST + ":" + PORT;
    }

    /** P19: the port the child announced, out of one stdout line. Returns
     *  0 for any line that is not the listen banner. Pure + JVM-testable:
     *  "opencode server listening on http://127.0.0.1:41234" → 41234. */
    public static int parseListenPort(String line) {
        if (line == null) return 0;
        int banner = line.indexOf("listening on http://");
        if (banner < 0) return 0;
        int colon = line.lastIndexOf(':');
        if (colon < banner) return 0;
        int end = colon + 1;
        while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
        if (end == colon + 1) return 0;          // no digits after the colon
        try {
            int p = Integer.parseInt(line.substring(colon + 1, end));
            return (p > 0 && p <= 65535) ? p : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Simple response holder: status + body. */
    public static final class Resp {
        public final int status;
        public final String body;
        public Resp(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
        public boolean ok() { return status >= 200 && status < 300; }
    }

    public static Resp get(String path) throws IOException {
        return call("GET", path, null, 10_000);
    }

    public static Resp post(String path, String jsonBody, int timeoutMs) throws IOException {
        return call("POST", path, jsonBody, timeoutMs);
    }

    /** Blocking GET with the caller-provided read timeout (used for health polls too). */
    public static int status(String path, int timeoutMs) throws IOException {
        HttpURLConnection c = open("GET", path, null, timeoutMs);
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    public static HttpURLConnection open(String method, String path, String jsonBody, int timeoutMs)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5_000);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        if (jsonBody != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] b = jsonBody.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            c.getOutputStream().write(b);
            c.getOutputStream().flush();
        }
        return c;
    }

    public static Resp call(String method, String path, String jsonBody, int timeoutMs)
            throws IOException {
        HttpURLConnection c = open(method, path, jsonBody, timeoutMs);
        try {
            int code = c.getResponseCode();
            InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            return new Resp(code, readAll(in));
        } finally {
            c.disconnect();
        }
    }

    /** Read a full stream as UTF-8 text (never null). */
    public static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        try {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Read one line from a reader without the BufferedReader line-count overhead concerns of SSE. */
    public static String readLine(BufferedReader r) throws IOException {
        return r.readLine();
    }
}
