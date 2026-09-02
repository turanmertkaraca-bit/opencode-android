package ai.opencode.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * P12 — GitHub access for the ON-DEVICE agent ("give it the key to github
 * and make sure u put everything the ai needs…").
 *
 * The user pastes a PAT once (Settings → GitHub). The app then:
 *   1. exposes it to the opencode process + every sandbox shell as
 *      GITHUB_TOKEN / GH_TOKEN (Binaries.applyEnv),
 *   2. writes $HOME/.git-credentials (mode 600) + $HOME/.gitconfig
 *      (credential.helper store, neutral git identity) so plain
 *      `git clone/pull/push` against github.com just works inside the
 *      sandbox — no prompts, no keychains.
 *
 * The token NEVER leaves the device except to api.github.com/github.com
 * over TLS, and the AGENTS.md inside the seeded repo tells the agent to
 * never print, commit, or echo it.
 */
public final class Github {

    private Github() {}

    public static final String REPO_URL =
            "https://github.com/turanmertkaraca-bit/opencode-android.git";
    private static final String PREFS = "oc";
    private static final String KEY = "gh_token";

    public static boolean hasToken(Context c) {
        String t = token(c);
        return t != null && t.length() >= 20;
    }

    public static String token(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null);
    }

    public static void setToken(Context c, String t) {
        String v = t == null ? "" : t.trim();
        if (v.isEmpty()) { clearToken(c); return; }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, v).apply();
        ensureFiles(c);
    }

    public static void clearToken(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
        // leave no stale credentials behind
        try {
            new File(Binaries.homeDir(c), ".git-credentials").delete();
        } catch (Exception ignored) {}
    }

    /**
     * (Re)write .git-credentials + .gitconfig under the sandbox $HOME.
     * Idempotent, cheap — called from applyEnv before every spawn and
     * after the token is saved. Token-less installs only get .gitconfig
     * (identity) so commits still carry a sane author.
     */
    public static void ensureFiles(Context c) {
        try {
            File home = Binaries.homeDir(c);
            if (!home.exists()) home.mkdirs();
            StringBuilder creds = new StringBuilder();
            String t = token(c);
            if (t != null && t.length() >= 20) {
                creds.append("https://x-access-token:").append(t)
                     .append("@github.com\n");
            }
            if (creds.length() > 0) {
                File f = new File(home, ".git-credentials");
                write600(f, creds.toString());
            }
            writeText(new File(home, ".gitconfig"),
                    "[user]\n"
                  + "    name = opencode-android (on-device agent)\n"
                  + "    email = agent@opencode-android.local\n"
                  + "[credential]\n"
                  + "    helper = store\n"
                  + "[init]\n"
                  + "    defaultBranch = main\n"
                  + "[core]\n"
                  + "    autocrlf = false\n");
        } catch (Exception ignored) {}
    }

    private static void write600(File f, String s) throws Exception {
        writeText(f, s);
        try {
            f.setReadable(true, true);       // owner-only
            f.setWritable(true, true);
            f.setExecutable(false, false);
        } catch (Exception ignored) {}
    }

    private static void writeText(File f, String s) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".part");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes("UTF-8"));
        }
        if (f.exists()) f.delete();
        tmp.renameTo(f);
    }
}
