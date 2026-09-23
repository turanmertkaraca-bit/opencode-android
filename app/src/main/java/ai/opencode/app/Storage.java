package ai.opencode.app;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P53 — the storage manager's model layer. The field complaint was blunt:
 * "the app gains weight too fast like 8gb is crazy". The weight is real
 * and it lives in a handful of known places: the Debian/Alpine layers,
 * apt + npm caches inside the rootfs, the bundled opencode payload, the
 * session store (opencode.db), server/incident logs, model catalog cache,
 * chat exports and the image cache. This class walks those roots, labels
 * them, and — critically — knows exactly which of them are SAFE to clear.
 *
 * SAFETY CONTRACT (the whole point):
 *   clear() deletes ONLY caches, logs, temp, exports, apt/npm caches and
 *   downloaded payload copies. It REFUSES project source, git repos,
 *   session JSON/DB, auth.json (API keys), opencode.json, run-state and
 *   told-state. A SANDBOX clear removes caches/temp INSIDE the layer but
 *   never the rootfs itself.
 *
 * Pure utility: no UI, no threads of its own, everything try/caught. The
 * caller runs scan()/clear() off the main thread (StorageActivity does).
 */
public final class Storage {

    private Storage() {}

    /** Broad categories so the UI can group/tint without string matching. */
    public enum Kind { PROJECT, SANDBOX, CACHE, LOG, SESSION, EXPORT, OTHER }

    /** One measured area of the app's storage. */
    public static final class Item {
        public String label;
        public File path;
        public long bytes;
        public Kind kind;
        public boolean clearable;
        public String clearLabel;
    }

    // ------------------------------------------------------------ scanning

    /** Scan with the default budget (20 s off-main-thread walk). */
    public static List<Item> scan(Context c) {
        return scan(c, 20_000L);
    }

    /**
     * Walk every known root and return a categorized breakdown, largest
     * first. The walk is bounded by {@code deadlineMs} (shared across all
     * roots) and by a depth/node cap, so a pathological tree can never
     * hang the worker. Never throws; a failed root is simply skipped.
     */
    public static List<Item> scan(Context c, long deadlineMs) {
        List<Item> out = new ArrayList<>();
        if (c == null) return out;
        final long deadline = System.currentTimeMillis()
                + Math.max(1_000L, deadlineMs);
        try {
            // ---- projects (source + clones) — NEVER clearable
            try {
                List<Projects.P> ps = Projects.list(c);
                for (Projects.P p : ps) {
                    if (p == null || p.path == null || p.path.isEmpty()) continue;
                    File dir = new File(p.path);
                    if (!dir.exists()) continue;
                    String name = (p.name == null || p.name.isEmpty())
                            ? dir.getName() : p.name;
                    add(out, "project · " + name, dir, Kind.PROJECT,
                            false, null, deadline);
                }
            } catch (Throwable ignored) {}

            // ---- sandbox layers (rootfs shown whole; clear = caches only)
            add(out, "Debian sandbox (rootfs · apt · node_modules)",
                    Debian.dir(c), Kind.SANDBOX, true, "Clear caches", deadline);
            add(out, "Alpine toolkit", Sandbox.alpineDir(c),
                    Kind.SANDBOX, true, "Clear caches", deadline);

            // ---- the bundled agent payload — structural, never cleared
            add(out, "opencode binary (agent engine)",
                    Binaries.binaryFile(c), Kind.OTHER, false, null, deadline);

            // ---- sessions + keys (home minus the log dir) — NEVER clearable
            try {
                File home = Binaries.homeDir(c);
                if (home.isDirectory()) {
                    File log = new File(home, ".local/share/opencode/log");
                    long homeSz = size(home, deadline);
                    long logSz = log.exists() ? size(log, deadline) : 0;
                    Item sess = new Item();
                    sess.label = "sessions · keys · config";
                    sess.path = home;
                    sess.bytes = Math.max(0, homeSz - logSz);
                    sess.kind = Kind.SESSION;
                    sess.clearable = false;
                    out.add(sess);
                    add(out, "opencode server log", log, Kind.LOG,
                            true, "Clear log", deadline);
                }
            } catch (Throwable ignored) {}

            // ---- app logs (incident + contained-error trail + crash)
            add(out, "sandbox incident log",
                    new File(c.getFilesDir(), "sandbox-diag.log"),
                    Kind.LOG, true, "Clear log", deadline);
            add(out, "contained-error trail",
                    new File(c.getFilesDir(), "guard-trail.log"),
                    Kind.LOG, true, "Clear log", deadline);
            add(out, "last Java crash report",
                    new File(c.getFilesDir(), "last-crash.txt"),
                    Kind.LOG, true, "Clear log", deadline);

            // ---- regenerable caches
            add(out, "model catalog cache",
                    new File(c.getFilesDir(), "models-cache.json"),
                    Kind.CACHE, true, "Clear cache", deadline);
            add(out, "app cache (downloads · images · temp)",
                    c.getCacheDir(), Kind.CACHE, true, "Clear cache", deadline);

            // ---- toolchain dirs (small; regenerable but needed to run)
            add(out, "toolkit binaries (busybox + imports)",
                    new File(c.getFilesDir(), "bin"),
                    Kind.OTHER, false, null, deadline);
            add(out, "shell shims",
                    new File(c.getFilesDir(), "shims"),
                    Kind.OTHER, false, null, deadline);
            add(out, "alpine command wrappers",
                    new File(c.getFilesDir(), "wrappers"),
                    Kind.OTHER, false, null, deadline);

            // ---- exports / temp
            try {
                File ext = c.getExternalFilesDir(null);
                add(out, "app exports (external files)", ext,
                        Kind.EXPORT, true, "Clear exports", deadline);
            } catch (Throwable ignored) {}
            addChatExports(out, deadline);
        } catch (Throwable ignored) {}
        Collections.sort(out, (a, b) -> Long.compare(b.bytes, a.bytes));
        return out;
    }

    /** Sum of the measured bytes. */
    public static long total(List<Item> items) {
        long n = 0;
        if (items != null) for (Item it : items) {
            if (it != null && it.bytes > 0) n += it.bytes;
        }
        return n;
    }

    /** Our own chat exports that landed in public Downloads. One item per
     *  file so clear() deletes exactly that file, never the folder. */
    private static void addChatExports(List<Item> out, long deadline) {
        try {
            File dl = new File("/storage/emulated/0/Download");
            File[] kids = dl.listFiles();
            if (kids == null) return;
            for (File k : kids) {
                if (System.currentTimeMillis() > deadline) return;
                if (k == null || !k.isFile()) continue;
                String n = k.getName();
                if (n.startsWith("opencode-chat-") && n.endsWith(".txt")) {
                    Item it = new Item();
                    it.label = "chat export · " + n;
                    it.path = k;
                    it.bytes = k.length();
                    it.kind = Kind.EXPORT;
                    it.clearable = true;
                    it.clearLabel = "Delete";
                    out.add(it);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void add(List<Item> out, String label, File f, Kind kind,
                            boolean clearable, String clearLabel, long deadline) {
        if (f == null || !f.exists()) return;
        long sz = size(f, deadline);
        Item it = new Item();
        it.label = label;
        it.path = f;
        it.bytes = sz;
        it.kind = kind;
        it.clearable = clearable;
        it.clearLabel = clearLabel;
        out.add(it);
    }

    // ----------------------------------------------------------- clearing

    /**
     * Delete only what is safe for this item and return the bytes
     * reclaimed. CACHE/LOG are removed whole; EXPORT only removes the
     * named file (never recurses); SANDBOX removes caches + temp inside
     * the layer (never the rootfs); PROJECT, SESSION and OTHER are
     * refused (0). Never throws.
     */
    public static long clear(Context c, Item it) {
        if (c == null || it == null || it.path == null) return 0;
        try {
            switch (it.kind) {
                case CACHE:
                    if (same(it.path, c.getCacheDir()))
                        return deleteTree(c.getCacheDir());
                    return deleteTree(it.path);
                case LOG:
                    return deleteTree(it.path);
                case EXPORT:
                    return clearExport(it.path);
                case SANDBOX:
                    return clearSandbox(c, it.path);
                default:
                    return 0;   // PROJECT / SESSION / OTHER: refuse
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Exports are deleted file-by-file and NEVER recursed into a
     * directory: the external files dir is where our chat-export fallback
     * lands, but a user could conceivably have pointed a project at a
     * subfolder of it — so only top-level regular files (or one named
     * export file) are removed.
     */
    private static long clearExport(File f) {
        if (f == null) return 0;
        long freed = 0;
        try {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids == null) return 0;
                for (File k : kids) {
                    if (k == null || !k.isFile()) continue;
                    if (isSymlink(k)) { k.delete(); continue; }
                    long n = k.length();
                    if (k.delete()) freed += n;
                }
            } else {
                if (isSymlink(f)) { f.delete(); return 0; }
                long n = f.length();
                if (f.delete()) freed = n;
            }
        } catch (Throwable ignored) {}
        return freed;
    }

    /**
     * The safe cache/temp paths inside a sandbox layer. Debian's rootfs
     * lives one level down (debian/rootfs) with its own PROOT tmp dir at
     * debian/tmp; Alpine keeps its cache under var/cache/apk. Only these
     * regenerable paths are removed — never bin/, lib/, the rootfs tree
     * itself, or anything a package installed.
     */
    private static long clearSandbox(Context c, File dir) {
        if (dir == null) return 0;
        long freed = 0;
        try {
            if (same(dir, Debian.dir(c))) {
                File rootfs = Debian.rootfsDir(c);
                freed += deleteTree(new File(rootfs, "tmp"));
                freed += deleteTree(new File(rootfs, "root/.npm"));
                freed += deleteTree(new File(rootfs, "root/.cache"));
                freed += deleteTree(new File(rootfs, "var/lib/apt/lists"));
                freed += deleteTree(new File(rootfs, "var/cache/apt/archives"));
                freed += deleteTree(new File(Debian.dir(c), "tmp"));
            } else if (same(dir, Sandbox.alpineDir(c))) {
                freed += deleteTree(new File(dir, "tmp"));
                freed += deleteTree(new File(dir, "var/cache/apk"));
                freed += deleteTree(new File(dir, "root/.cache"));
            }
        } catch (Throwable ignored) {}
        return freed;
    }

    /**
     * Recursive delete; returns the bytes of regular files actually
     * removed. Symlinks are unlinked, never followed. Partial deletes
     * still report only what was truly removed.
     */
    private static long deleteTree(File f) {
        if (f == null) return 0;
        try {
            if (isSymlink(f)) {
                f.delete();
                return 0;
            }
            if (f.isDirectory()) {
                long n = 0;
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) n += deleteTree(k);
                f.delete();
                return n;
            }
            long n = f.length();
            return f.delete() ? n : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------ sizing

    private static long size(File f, long deadline) {
        if (f == null) return 0;
        String root;
        try {
            root = f.getCanonicalPath();
        } catch (Throwable t) {
            root = f.getAbsolutePath();
        }
        return size(f, root, 0, new HashSet<>(), deadline);
    }

    /**
     * Bounded recursive size. Symlinks are skipped (no loops, no counting
     * targets outside the root); canonical paths are de-duplicated; a
     * depth/node/time cap guarantees termination on huge trees.
     */
    private static long size(File f, String root, int depth,
                             Set<String> seen, long deadline) {
        if (f == null || depth > 48) return 0;
        if (System.currentTimeMillis() > deadline) return 0;
        try {
            if (isSymlink(f)) return 0;
            if (f.isFile()) return f.length();
            if (!f.isDirectory()) return 0;
            String cp = f.getCanonicalPath();
            if (!cp.equals(root) && !cp.startsWith(root + File.separator)) return 0;
            if (seen.size() > 200_000) return 0;
            if (!seen.add(cp)) return 0;
            File[] kids = f.listFiles();
            if (kids == null) return 0;
            long n = 0;
            for (File k : kids) {
                if (System.currentTimeMillis() > deadline) break;
                n += size(k, root, depth + 1, seen, deadline);
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------ helpers

    /** "512 B", "3 KB", "12.4 MB", "1.20 GB". */
    public static String human(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L)
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static boolean same(File a, File b) {
        if (a == null || b == null) return false;
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Throwable t) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /** True when f is a symlink (readlink succeeds only for links). */
    private static boolean isSymlink(File f) {
        try {
            Os.readlink(f.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
