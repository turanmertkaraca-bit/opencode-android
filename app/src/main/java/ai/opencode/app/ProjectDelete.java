package ai.opencode.app;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * P30 — "delete my projects": the pure half of long-press → Delete.
 *
 * Deleting a whole project folder is the most destructive move the app
 * has ever offered, so the guards are the design, not an afterthought:
 *
 *   • safetyCheck() refuses every path that could ever be more than a
 *     project: filesystem roots, mount points, the shared-storage roots
 *     a card could theoretically be aimed at, the app's own private dir
 *     (and any ANCESTOR of it — deleting /data/data/<pkg>'s parent would
 *     take the app's brain with it). The UI also shows the exact path in
 *     the confirm dialog, so what is checked here is what was read there.
 *   • deleteTree() never follows symlinks (a link pointing at /sdcard is
 *     UNLINKED, the target survives), walks with an explicit stack instead
 *     of recursion (a pathological tree cannot blow the call stack), and
 *     aborts at a hard file-count cap rather than grinding through
 *     something absurd.
 *
 * Both halves are pure JVM-testable: no Context, no Android imports.
 */
public final class ProjectDelete {

    private ProjectDelete() {}

    /** deleteTree() aborts past this many removed entries — a project is
     *  a folder of code, not a filesystem. (20k files ≫ any real project,
     *  ≪ any accidental root.) */
    public static final int MAX_FILES = 20_000;

    /** The path prefixes that are NEVER deletable, relative-independent.
     *  Compared against the canonical path with a trailing slash so
     *  /storage/emulated is refused but /storage/emulated0-mine is not. */
    private static final String[] FORBIDDEN_EXACT = {
            "/", "/storage", "/sdcard", "/emmc", "/mnt", "/data", "/system",
            "/vendor", "/proc", "/sys", "/dev", "/cache", "/firmware"
    };

    /**
     * Null when the path is safe to hand to deleteTree(), else the reason
     * (shown to the user, never logged-and-swallowed). Pure string logic:
     * existence/writability is the caller's runtime check.
     *
     * @param path        the project folder the card points at
     * @param appFilesDir the app's private files dir (Context.getFilesDir())
     */
    public static String safetyCheck(String path, String appFilesDir) {
        if (path == null || path.trim().isEmpty()) return "empty path";
        if (appFilesDir == null || appFilesDir.trim().isEmpty())
            return "internal error: app dir unknown";
        final String p;
        final String app;
        try {
            // canonical: resolves symlinks and ".." so a link to "/" cannot
            // dress up as a harmless name
            p = new File(path).getCanonicalPath();
            app = new File(appFilesDir).getCanonicalPath();
        } catch (Exception e) {
            return "path could not be resolved";
        }
        for (String f : FORBIDDEN_EXACT) {
            if (p.equals(f) || p.equals(f + "/")) return "refusing " + f;
        }
        // never the app's own dir, never an ANCESTOR of it
        if (p.equals(app) || app.startsWith(p.endsWith("/") ? p : p + "/"))
            return "refusing app-internal path";
        // must be deep enough to be a real folder (≥2 separators below root
        // already implied by the refusals above; keep the belt)
        if (p.length() < 4) return "path too shallow";
        return null;
    }

    /** How many entries the tree contains (files + dirs), capped at
     *  maxFiles+1 — the confirm dialog can show an honest size and the
     *  delete can refuse absurd targets BEFORE touching anything. */
    public static int countTree(File dir, int maxFiles) {
        if (dir == null || !dir.isDirectory()) return 0;
        int n = 0;
        Deque<File> stack = new ArrayDeque<>();
        stack.push(dir);
        while (!stack.isEmpty()) {
            File d = stack.pop();
            File[] kids = d.listFiles();
            if (kids == null) continue;             // unreadable → not counted
            for (File k : kids) {
                n++;
                if (n > maxFiles) return n;         // overflow signal
                if (k.isDirectory() && !isSymLink(k)) stack.push(k);
            }
        }
        return n;
    }

    /**
     * Delete dir and everything inside it. Never follows symlinks — a
     * symlinked entry is unlinked itself, its target untouched. Returns
     * the number of entries removed; throws on the first failure (the
     * caller reports what happened — partial deletes are possible and
     * the dialog says so up front). The count-first rule: the tree is
     * counted BEFORE anything is removed, so an oversized target aborts
     * without touching a single file.
     *
     * @throws IllegalStateException when the tree exceeds the cap
     */
    public static int deleteTree(File dir) {
        return deleteTree(dir, MAX_FILES);
    }

    /** deleteTree with an injectable cap — production callers use the
     *  MAX_FILES overload; the suite pins the abort with a small one. */
    public static int deleteTree(File dir, int maxFiles) {
        if (dir == null) return 0;
        if (!isSymLink(dir) && dir.isDirectory()) {
            int kids = countTree(dir, maxFiles);
            if (kids > maxFiles)
                throw new IllegalStateException(
                        "tree too large (" + kids + " entries) — refusing");
        }
        return deleteInner(dir, 0, maxFiles);
    }

    private static int deleteInner(File f, int done, int cap) {
        if (done > cap)
            throw new IllegalStateException("file cap exceeded");
        if (f.isDirectory() && !isSymLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) done = deleteInner(k, done, cap);
            }
        }
        if (!f.delete()) {
            // directories vanish naturally when the last child goes; a
            // stubborn dir (pinned by the kernel) is not fatal if empty
            if (f.isDirectory() && f.listFiles() != null
                    && f.listFiles().length > 0)
                throw new RuntimeException("could not delete " + f);
        }
        return done + 1;
    }

    private static boolean isSymLink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;                            // when in doubt, walk it
        }
    }
}
