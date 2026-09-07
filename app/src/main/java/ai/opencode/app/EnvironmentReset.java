package ai.opencode.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * P31 — factory reset for the SANDBOX ENVIRONMENT, with the keys kept.
 * Wipes every extracted/generated piece of the tool environment (Debian
 * rootfs, Alpine layer, wrappers, shims, busybox applets — including the
 * user's imported tools, honestly disclosed — caches) so the next boot
 * re-extracts from the bundled, sha-verified assets. NEVER touches:
 *
 *   • files/home/** — auth.json (API keys), opencode.json (config), and
 *     the server's own session/message store (every chat, on disk),
 *   • the "oc" preferences — GitHub token, model picks, settings,
 *   • projects.json, run-state.json, logs, the opencode binary itself.
 *
 * Pure planning + guarded deletion here; Settings drives the flow.
 */
public final class EnvironmentReset {

    private EnvironmentReset() {}

    /** What WILL be wiped — one human line per target (dialog copy). */
    public static List<String> targetNames() {
        List<String> out = new ArrayList<>();
        out.add("Debian rootfs + proot binaries (~250 MB, re-downloaded on demand)");
        out.add("Alpine layer + command wrappers (~4 MB)");
        out.add("shims + busybox applets + tools you imported");
        out.add("package lists + rootfs caches");
        out.add("the model catalog cache");
        return out;
    }

    /** What is KEPT — the other half of the dialog copy. */
    public static List<String> keptNames() {
        List<String> out = new ArrayList<>();
        out.add("your API keys and the OpenCode config");
        out.add("your GitHub token");
        out.add("every project and every chat (sessions live on disk)");
        out.add("all app settings");
        return out;
    }

    /**
     * The exact directories/files to delete. Everything here lives under
     * filesDir (or cacheDir) and is regenerated from bundled assets on
     * the next boot. home/ (the keys) is deliberately absent.
     */
    public static List<File> targets(File filesDir, File cacheDir) {
        List<File> out = new ArrayList<>();
        out.add(new File(filesDir, "debian"));
        out.add(new File(filesDir, "alpine"));
        out.add(new File(filesDir, "wrappers"));
        out.add(new File(filesDir, "shims"));
        out.add(new File(filesDir, "bin"));
        out.add(new File(filesDir, "busybox-applets.txt"));
        out.add(new File(filesDir, "models-cache.json"));
        out.add(new File(cacheDir, "debian-rootfs.tar.gz"));
        return out;
    }

    /**
     * The guarded recursive delete. Refuses (skips) any path that is not
     * strictly INSIDE filesDir or cacheDir after canonicalization — a
     * symlinked target can never pull the walk out onto home/ or /sdcard.
     * Returns the entry count removed.
     */
    public static int wipe(List<File> targets, File filesDir, File cacheDir) {
        int n = 0;
        for (File t : targets) {
            if (!inside(t, filesDir) && !inside(t, cacheDir)) continue;
            if (!t.exists()) continue;
            n += deleteTree(t);
        }
        return n;
    }

    /** True when f canonicalizes strictly inside root. */
    static boolean inside(File f, File root) {
        if (f == null || root == null) return false;
        try {
            String fc = f.getCanonicalPath();
            String rc = root.getCanonicalPath();
            return fc.startsWith(rc + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private static int deleteTree(File f) {
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += deleteTree(k);
        if (f.delete() || !f.exists()) n++;
        return n;
    }
}
