package ai.opencode.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * P9 — QA bridge: the "write another app that lets u take screenshots and
 * control it" idea, shipped INSIDE the app (no sideloader companion needed).
 *
 * OFF by default. When enabled in Settings (QA bridge → paste a GitHub
 * fine-grained token + repo), a poller watches qa/commands.txt in the repo:
 *
 *     12|shot
 *     13|log
 *     14|open settings
 *     15|toast hi, tap the model chip next
 *
 * and uploads results back into qa/ (shot-*.png, log-*.txt, state-*.txt).
 * That gives the developer (me) real eyes on the user's device during
 * development — the Android equivalent of looking at the website being
 * built. Only the app's OWN windows can be captured and only its OWN
 * screens opened (no accessibility/injection tricks), which is exactly
 * enough for app QA and nothing more.
 *
 * Security: the token lives in app-private prefs and should be a
 * fine-grained token scoped to ONE repo with Contents read/write only.
 */
public final class QaBridge {

    private QaBridge() {}

    private static volatile Thread worker;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    public static boolean enabled(Context c) {
        return c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getBoolean("qa_bridge", false);
    }

    public static String repo(Context c) {
        return c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getString("qa_repo", "");
    }

    /** Kick off (or stop) the poller per the current prefs. */
    public static synchronized void sync(Context c) {
        if (enabled(c) && (worker == null || !worker.isAlive())) {
            worker = new Thread(() -> loop(c.getApplicationContext()), "qa-bridge");
            worker.setDaemon(true);
            worker.start();
        } else if (!enabled(c) && worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    // ------------------------------------------------------------- loop

    private static void loop(Context c) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String repo = repo(c);
                String token = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                        .getString("qa_token", "");
                if (repo.isEmpty() || token.isEmpty()) { sleep(20_000); continue; }

                GitHubFile f = GitHub.get(c, repo, "qa/commands.txt", token);
                if (f == null) { sleep(15_000); continue; }

                long last = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                        .getLong("qa_seq", 0);
                StringBuilder remaining = new StringBuilder();
                boolean acted = false;
                for (String line : f.text.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int bar = line.indexOf('|');
                    if (bar <= 0) continue;
                    long seq;
                    try { seq = Long.parseLong(line.substring(0, bar).trim()); }
                    catch (Exception e) { continue; }
                    if (seq <= last) continue;
                    acted = true;
                    String cmd = line.substring(bar + 1).trim();
                    execute(c, cmd);
                    c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                            .edit().putLong("qa_seq", seq).apply();
                    last = seq;
                }
                if (acted) {
                    // keep only lines we did NOT process (drop old ones too)
                    StringBuilder keep = new StringBuilder();
                    for (String line : f.text.split("\n")) {
                        line = line.trim();
                        int bar = line.indexOf('|');
                        if (bar <= 0) continue;
                        try {
                            if (Long.parseLong(line.substring(0, bar).trim()) > last) {
                                keep.append(line).append('\n');
                            }
                        } catch (Exception ignored) {}
                    }
                    GitHub.put(c, repo, "qa/commands.txt",
                            keep.toString().getBytes(StandardCharsets.UTF_8),
                            token, f.sha, "qa: processed up to " + last);
                }
                sleep(12_000);
            } catch (Exception e) {
                sleep(30_000);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --------------------------------------------------------- commands

    private static void execute(Context c, String cmd) {
        String lower = cmd.toLowerCase(Locale.US);
        String arg = cmd.contains(" ") ? cmd.substring(cmd.indexOf(' ') + 1).trim() : "";
        try {
            if (lower.startsWith("shot")) {
                Activity a = App.top();
                if (a == null || a.getWindow() == null) return;
                android.view.View dv = a.getWindow().getDecorView();
                Bitmap bm = Bitmap.createBitmap(dv.getWidth(), dv.getHeight(),
                        Bitmap.Config.ARGB_8888);
                dv.draw(new Canvas(bm));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.PNG, 80, bos);
                String name = String.format(Locale.US, "qa/shot-%d.png",
                        System.currentTimeMillis());
                GitHub.put(c, repo(c), name, bos.toByteArray(),
                        token(c), null, "qa: screenshot (" + a.getLocalClassName() + ")");
                bm.recycle();

            } else if (lower.startsWith("log")) {
                String out = "==== server tail ====\n" + ServerService.getTail()
                        + "\n\n==== last crash ====\n" + readFile(App.crashFile(c))
                        + "\n\n==== state ====\n" + stateText(c);
                String name = String.format(Locale.US, "qa/log-%d.txt",
                        System.currentTimeMillis());
                GitHub.put(c, repo(c), name, out.getBytes(StandardCharsets.UTF_8),
                        token(c), null, "qa: logs");

            } else if (lower.startsWith("state")) {
                String name = String.format(Locale.US, "qa/state-%d.txt",
                        System.currentTimeMillis());
                GitHub.put(c, repo(c), name, stateText(c).getBytes(StandardCharsets.UTF_8),
                        token(c), null, "qa: state");

            } else if (lower.startsWith("open")) {
                Class<?> target;
                switch (arg.toLowerCase(Locale.US)) {
                    case "home": case "deck": target = HomeActivity.class; break;
                    case "settings": target = SettingsActivity.class; break;
                    case "keys": case "api": target = KeysActivity.class; break;
                    case "diag": case "logs": case "shell": target = DiagnosticsActivity.class; break;
                    case "chat": default: target = ChatActivity.class; break;
                }
                android.content.Intent i = new android.content.Intent(c, target);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);

            } else if (lower.startsWith("restart")) {
                ServerService.restart(c);

            } else if (lower.startsWith("toast")) {
                String msg = arg.isEmpty() ? "qa bridge ack" : arg;
                ui.post(() -> Toast.makeText(c, msg, Toast.LENGTH_LONG).show());
            }
        } catch (Exception ignored) {
            // a broken command must never kill the poller
        }
    }

    private static String token(Context c) {
        return c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getString("qa_token", "");
    }

    private static String stateText(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("time: ").append(new java.util.Date()).append('\n');
        sb.append("server state: ").append(ServerService.getState()).append('\n');
        sb.append("healthy: ").append(ServerService.healthy()).append('\n');
        File d = ServerService.servingDir();
        sb.append("serving dir: ").append(d == null ? "(none)" : d.getAbsolutePath()).append('\n');
        String[] sel = Models.selected(c);
        sb.append("selected model: ").append(sel == null ? "(server default)"
                : sel[0] + "/" + sel[1]).append('\n');
        sb.append("keys: ");
        for (String pid : AuthStore.readAuth(c).keySet()) sb.append(pid).append(' ');
        sb.append("\ncrash file exists: ").append(App.crashFile(c).exists()).append('\n');
        sb.append("sandbox toolkit: ").append(Sandbox.ready(c)).append('\n');
        return sb.toString();
    }

    private static String readFile(File f) {
        if (f == null || !f.exists()) return "(none)";
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "(unreadable: " + e + ")";
        }
    }

    // ------------------------------------------------------- github api

    static final class GitHubFile {
        String text, sha;
    }

    /** Shared GitHub contents-API helpers (also used by the Settings test). */
    static final class GitHub {
        static HttpURLConnection open(String url, String token) throws Exception {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(12_000);
            con.setReadTimeout(30_000);
            if (token != null && !token.isEmpty())
                con.setRequestProperty("Authorization", "Bearer " + token);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("User-Agent", "opencode-android-qa");
            return con;
        }

        static GitHubFile get(Context c, String repo, String path, String token) {
            try {
                HttpURLConnection con = open(
                        "https://api.github.com/repos/" + repo + "/contents/" + path, token);
                int code = con.getResponseCode();
                if (code != 200) { con.disconnect(); return null; }
                String body = Api.readAll(con.getInputStream());
                con.disconnect();
                java.util.Map<String, Object> m = Json.obj(Json.parse(body));
                if (m == null) return null;
                GitHubFile f = new GitHubFile();
                f.sha = Json.str(m, "sha");
                String b64 = Json.str(m, "content");
                if (b64 != null) {
                    f.text = new String(Base64.decode(b64, Base64.NO_WRAP),
                            StandardCharsets.UTF_8);
                }
                return f;
            } catch (Exception e) {
                return null;
            }
        }

        static boolean put(Context c, String repo, String path, byte[] content,
                           String token, String sha, String message) {
            try {
                java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("message", message);
                body.put("content", Base64.encodeToString(content, Base64.NO_WRAP));
                if (sha != null && !sha.isEmpty()) body.put("sha", sha);
                HttpURLConnection con = open(
                        "https://api.github.com/repos/" + repo + "/contents/" + path, token);
                con.setRequestMethod("PUT");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
                con.setFixedLengthStreamingMode(b.length);
                con.getOutputStream().write(b);
                int code = con.getResponseCode();
                con.disconnect();
                return code >= 200 && code < 300;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
