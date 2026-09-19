package ai.opencode.app;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * P9 — the Alpine layer: a REAL package manager for the agent, still with
 * zero proot. The complaint was blunt: "the sandbox doesn't have a package
 * manager, the AI can't do shii."
 *
 * HOW IT WORKS (all inside the app's exec-allowed private storage):
 *
 *   files/alpine/          — alpine-minirootfs-aarch64 (asset alpine.bin,
 *                            ~4 MB compressed). Real symlinks are created
 *                            via android.system.Os (API 21+, minSdk 28).
 *   files/wrappers/        — one tiny #!/system/bin/sh script per rootfs
 *                            command. EVERY alpine binary is musl-dynamic
 *                            with interpreter /lib/ld-musl-aarch64.so.1
 *                            (an absolute path that does not exist on
 *                            Android), so each wrapper execs:
 *
 *      alpine/lib/ld-musl-aarch64.so.1 --library-path alpine/lib:alpine/usr/lib <bin> args
 *
 *   files/shims/pkg        — user-facing front end: pkg install/remove/
 *                            search/update/list/rehash → apk --root.
 *
 *   NETWORK: musl reads /etc/resolv.conf (impossible on Android), so DNS
 *   for the alpine world goes through the in-app Java CONNECT proxy
 *   (ProxyServer — bionic resolver). Every wrapper sources alpine/.proxy
 *   which exports http(s)_proxy=127.0.0.1:<port>. apk, git, curl, pip and
 *   friends all honor those env vars → working network. The Alpine repo is
 *   forced to http:// because the minirootfs ships no CA bundle; package
 *   and index authenticity is still enforced by apk's RSA signatures.
 *
 * PATH priority (Binaries.applyEnv):
 *   bin/ (bundled busybox + user imports) → wrappers/ (alpine) →
 *   shims/ (bash/git/pkg fallbacks) → /system/bin.
 *
 * Non-root realities: apk --root runs as the app uid. chown-to-root
 * warnings from apk are cosmetic (we own every file); --no-scripts skips
 * maintainer scripts that assume an Alpine /bin/sh at boot.
 */
public final class Sandbox {

    private Sandbox() {}

    public static final String REPO_VER = "v3.22";

    public static File alpineDir(Context c) {
        return new File(c.getFilesDir(), "alpine");
    }

    public static File wrappersDir(Context c) {
        File f = new File(c.getFilesDir(), "wrappers");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /** True when the toolkit has been extracted (marker file .ready). */
    public static boolean ready(Context c) {
        return new File(alpineDir(c), ".ready").exists()
                && new File(alpineDir(c), "lib/ld-musl-aarch64.so.1").isFile();
    }

    /** On-disk size of the layer, for the Settings card. */
    public static long sizeOf(File f) {
        if (f == null) return 0;
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        long n = 0;
        if (kids != null) for (File k : kids) n += sizeOf(k);
        return n;
    }

    // --------------------------------------------------- P49: size cards

    /**
     * P49 — the "Settings lags, black screen, crashes" class, cured at the
     * source. The Settings/Diagnostics size cards used to call
     * {@link #sizeOf} ON THE UI THREAD: an unbounded recursive walk of the
     * whole layer. On a grown Debian rootfs (weeks of apt installs — tens
     * of thousands of files) that is SECONDS of main-thread block on every
     * open of Settings: the field's "it lags, black screen and crashes"
     * (an ANR kill). The walk is worth doing — the number is honest — but
     * never on the main thread, and never more often than needed.
     *
     * Process-wide cache: {key → {bytes, measuredAtNano}}. The UI paints
     * the last known number INSTANTLY (or "measuring…" once) and a fresh
     * walk runs on a background pool when the cached one is older than the
     * caller's staleness window. Delivery is ALWAYS off the calling
     * thread, so the caller just ui.post()s the repaint.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]>
            SIZE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** The one background lane for size walks (daemon — never holds the
     *  process open; walks serialize behind each other, which is fine:
     *  they are rare and cheap relative to an ANR). */
    private static final java.util.concurrent.ExecutorService SIZE_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "oc-dirsize");
                t.setDaemon(true);
                return t;
            });

    /** Last known size for a cache key, -1 when never measured. */
    public static long sizeKnown(String key) {
        long[] e = SIZE_CACHE.get(key);
        return e == null ? -1 : e[0];
    }

    /** {@link #sizeAsync(String, File, long, java.util.function.LongConsumer)}
     *  on the app's own background lane. */
    public static long sizeAsync(String key, File root, long staleMs,
                                 java.util.function.LongConsumer onDone) {
        return sizeAsync(key, root, staleMs, onDone, SIZE_POOL);
    }

    /** Test seam: the same contract over a caller-supplied executor (a
     *  same-thread executor makes the walk synchronous and deterministic
     *  in the JVM suite). Returns the cached value WITHOUT waiting:
     *  -1 = never measured, and the fresh number arrives via onDone. */
    static long sizeAsync(String key, File root, long staleMs,
                          java.util.function.LongConsumer onDone,
                          java.util.concurrent.Executor ex) {
        long[] e = SIZE_CACHE.get(key);
        long now = System.nanoTime();
        boolean fresh = e != null && (now - e[1]) < staleMs * 1_000_000L;
        if (!fresh) {
            ex.execute(() -> {
                long v = sizeOf(root);
                SIZE_CACHE.put(key, new long[]{v, System.nanoTime()});
                if (onDone != null) {
                    try { onDone.accept(v); } catch (Throwable ignored) {}
                }
            });
        } else if (onDone != null) {
            final long v = e[0];
            ex.execute(() -> {
                try { onDone.accept(v); } catch (Throwable ignored) {}
            });
        }
        return e == null ? -1 : e[0];
    }

    /** Cache keys the screens share. */
    public static final String SIZE_ALPINE = "alpine-toolkit";
    public static final String SIZE_DEBIAN = "debian-rootfs";

    // ------------------------------------------------------- install flow

    /**
     * Extract + configure the layer. Idempotent; safe to call from any
     * thread. Returns true when the layer is usable afterwards.
     */
    public static boolean ensure(Context c, TarGz.Progress cb) {
        try {
            File al = alpineDir(c);
            if (!ready(c)) {
                if (cb != null) cb.on("unpacking sandbox toolkit…");
                InputStream in = c.getAssets().open("alpine.bin");
                try {
                    TarGz.extractAll(in, al, true,
                            msg -> { if (cb != null) cb.on(msg); },
                            Sandbox::alpineLink);
                } finally {
                    try { in.close(); } catch (IOException ignored) {}
                }
                if (!new File(al, "lib/ld-musl-aarch64.so.1").isFile())
                    throw new IOException("rootfs incomplete (no musl loader)");
                // repo over http (no CA bundle in minirootfs; apk signatures
                // still verify authenticity) + a placeholder resolv.conf.
                writeText(new File(al, "etc/apk/repositories"),
                        "http://dl-cdn.alpinelinux.org/alpine/" + REPO_VER + "/main\n"
                      + "http://dl-cdn.alpinelinux.org/alpine/" + REPO_VER + "/community\n");
                writeText(new File(al, "etc/resolv.conf"),
                        "nameserver 127.0.0.1\n# DNS goes through the in-app proxy (.proxy)\n");
                writeText(new File(al, ".ready"), "ok " + REPO_VER + "\n");
                if (cb != null) cb.on("toolkit installed");
            }
            refreshProxy(c);
            generateWrappers(c, false);
            Shims.writePkgShim(c);
            return true;
        } catch (Exception e) {
            if (cb != null) cb.on("toolkit install failed: " + e);
            return false;
        }
    }

    private static volatile boolean extracting;

    /** Kick extraction in the background (boot path); never blocks. */
    public static void ensureAsync(Context c) {
        if (ready(c)) {
            refreshProxy(c);
            return;
        }
        synchronized (Sandbox.class) {
            if (extracting) return;
            extracting = true;
        }
        Thread t = new Thread(() -> {
            try { ensure(c, null); }
            finally { extracting = false; }
        }, "oc-sandbox");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Alpine link policy (TarGz.LinkHandler): create a REAL symlink with
     * android.system.Os. Absolute targets are rewritten relative to the
     * rootfs root so every path stays inside app storage. Content-copy
     * fallback when symlinks are refused (odd filesystems).
     */
    private static void alpineLink(File destDir, String name, String target, boolean exec) {
        try {
            File dst = new File(destDir, name);
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            if (dst.exists()) dst.delete();
            String t = target;
            if (t.startsWith("/")) t = relFromRoot(name, t.substring(1));
            try {
                if (Build.VERSION.SDK_INT >= 21) Os.symlink(t, dst.getAbsolutePath());
            } catch (Exception linkFail) {
                copyWithinRootfs(destDir, name, t); // fallback: duplicate content
                return;
            }
            if (exec) dst.setExecutable(true, false);
        } catch (Exception ignored) {}
    }

    /** "/bin/busybox" for link at "usr/bin/ls" → "../../bin/busybox". */
    private static String relFromRoot(String linkName, String rootRel) {
        int depth = 0;
        for (int i = 0; i < linkName.length(); i++) if (linkName.charAt(i) == '/') depth++;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) b.append("../");
        b.append(rootRel);
        return b.toString();
    }

    private static void copyWithinRootfs(File destDir, String name, String target) {
        try {
            String resolved = target.startsWith("/")
                    ? TarGz.norm(target.substring(1))
                    : TarGz.norm(TarGz.rel(name, target));
            File src = new File(destDir, resolved);
            if (!src.isFile()) return;
            File dst = new File(destDir, name);
            dst.getParentFile().mkdirs();
            FileInputStream in = new FileInputStream(src);
            try (FileOutputStream o = new FileOutputStream(dst)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            } finally {
                in.close();
            }
            dst.setExecutable(true, false);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ proxy

    /** (Re)write alpine/.proxy with the local proxy port. Cheap, idempotent. */
    public static void refreshProxy(Context c) {
        try {
            int p = ProxyServer.ensureStarted(c);
            if (p <= 0) return;
            String px = "http://127.0.0.1:" + p;
            writeText(new File(alpineDir(c), ".proxy"),
                    "http_proxy=" + px + "\n"
                  + "https_proxy=" + px + "\n"
                  + "HTTP_PROXY=" + px + "\n"
                  + "HTTPS_PROXY=" + px + "\n"
                  + "no_proxy=127.0.0.1,localhost,::1\n"
                  + "NO_PROXY=127.0.0.1,localhost,::1\n"
                  + "export http_proxy https_proxy HTTP_PROXY HTTPS_PROXY no_proxy NO_PROXY\n");
        } catch (Exception ignored) {}
    }

    // --------------------------------------------------------- wrappers

    private static final String[] SCAN = {
            "bin", "usr/bin", "sbin", "usr/sbin", "usr/local/bin"};

    /**
     * Generate files/wrappers/<cmd> for every executable in the rootfs.
     * force=false: only fill gaps (fast rehash). force=true: rewrite all.
     */
    public static int generateWrappers(Context c, boolean force) {
        File al = alpineDir(c);
        if (!new File(al, "lib/ld-musl-aarch64.so.1").isFile()) return 0;
        File wrap = wrappersDir(c);
        if (force) {
            File[] old = wrap.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        File userBin = Shims.binDir(c); // user imports always win
        int made = 0;
        for (String dir : SCAN) {
            File d = new File(al, dir);
            File[] kids = d.listFiles();
            if (kids == null) continue;
            for (File f : kids) {
                try {
                    String name = f.getName();
                    // apk: written below with --root baked in; pkg lives in shims/
                    if (name.equals("apk") || name.equals("pkg")) continue;
                    if (new File(userBin, name).exists()) continue; // user wins
                    File w = new File(wrap, name);
                    if (w.exists()) continue;
                    String script = wrapperFor(c, f);
                    if (script == null) continue;
                    writeText(w, script);
                    w.setExecutable(true, false);
                    w.setReadable(true, false);
                    made++;
                } catch (Exception ignored) {}
            }
        }
        // apk wrapper with --root baked in (so `apk add x` just works too)
        try {
            File w = new File(wrap, "apk");
            if (!w.exists() || force) {
                writeText(w, prolog(c)
                        + "exec \"$LB\" --library-path \"$LP\" \"$AL/sbin/apk\" "
                        + "--root \"$AL\" --no-scripts \"$@\"\n");
                w.setExecutable(true, false);
            }
        } catch (Exception ignored) {}
        return made;
    }

    private static String prolog(Context c) {
        // P42: the rootfs ships a full Mozilla CA bundle at the path
        // musl/gnu tools compiled for Linux expect — point every wrapper
        // at it so apk/curl/git/pip inside the alpine layer get TLS too.
        // P42-check: the app-merged bundle (system store ∪ Mozilla) wins
        // when present — pointing at the rootfs-only set here silently
        // undid Binaries.applyEnv's export for vendor-ROM union certs.
        String merged = CaBundle.bundleFile(c).getAbsolutePath();
        return "#!/system/bin/sh\n"
             + "AL=" + alpineDir(c).getAbsolutePath() + "\n"
             + "MB=" + merged + "\n"
             + "LB=\"$AL/lib/ld-musl-aarch64.so.1\"\n"
             + "LP=\"$AL/lib:$AL/usr/lib\"\n"
             + "[ -r \"$AL/.proxy\" ] && . \"$AL/.proxy\"\n"
             + "if [ -r \"$MB\" ]; then\n"
             + "  export SSL_CERT_FILE=\"$MB\"\n"
             + "elif [ -r \"$AL/etc/ssl/certs/ca-certificates.crt\" ]; then\n"
             + "  export SSL_CERT_FILE=\"$AL/etc/ssl/certs/ca-certificates.crt\"\n"
             + "fi\n"
             + "if [ -n \"$SSL_CERT_FILE\" ]; then\n"
             + "  export CURL_CA_BUNDLE=\"$SSL_CERT_FILE\" GIT_SSL_CAINFO=\"$SSL_CERT_FILE\"\n"
             + "  export REQUESTS_CA_BUNDLE=\"$SSL_CERT_FILE\" PIP_CERT=\"$SSL_CERT_FILE\"\n"
             + "fi\n"
             + "export LD_LIBRARY_PATH=\"$LP\"\n";
    }

    /** Build one wrapper script for a rootfs entry; null to skip. */
    private static String wrapperFor(Context c, File f) {
        try {
            String real = resolveSymlinks(f, 8);
            if (real == null) return null;
            File rf = new File(real);
            if (!rf.isFile()) return null;

            // shebang script? (pip console scripts, sh helpers…)
            String[] she = shebang(rf);
            if (she != null) {
                String interp = she[0];          // e.g. /usr/bin/python3
                String base = interp.substring(interp.lastIndexOf('/') + 1);
                if (base.startsWith("python")) {
                    // run through the rootfs python (whatever the raw path was)
                    File py = rootfsResolve(c, "/usr/bin/" + base);
                    if (py == null) py = rootfsResolve(c, "/usr/local/bin/" + base);
                    if (py != null) return prolog(c)
                            + "exec \"$LB\" --library-path \"$LP\" \"" + py.getAbsolutePath()
                            + "\" \"" + real + "\" \"$@\"\n";
                }
                if (base.equals("sh") || base.equals("ash")) {
                    return prolog(c)
                            + "exec \"$LB\" --library-path \"$LP\" \"$AL/bin/busybox\" "
                            + base + " \"" + real + "\" \"$@\"\n";
                }
                if (base.equals("env")) {
                    return prolog(c)
                            + "exec \"$LB\" --library-path \"$LP\" \"$AL/bin/busybox\" env \""
                            + real + "\" \"$@\"\n";
                }
                // unknown interpreter: fall through and try the loader anyway
            }

            // busybox applet? (resolved final path ends in /bin/busybox)
            if (real.endsWith("/bin/busybox")) {
                String applet = f.getName();
                return prolog(c)
                        + "exec \"$LB\" --library-path \"$LP\" \"$AL/bin/busybox\" "
                        + applet + " \"$@\"\n";
            }

            // plain ELF
            return prolog(c)
                    + "exec \"$LB\" --library-path \"$LP\" \"" + real + "\" \"$@\"\n";
        } catch (Exception e) {
            return null;
        }
    }

    /** Follow real symlinks (created at extraction) to the final file. */
    private static String resolveSymlinks(File f, int depth) {
        try {
            File cur = f.getCanonicalFile();
            while (depth-- > 0) {
                String t;
                try {
                    t = Os.readlink(cur.getAbsolutePath());
                } catch (android.system.ErrnoException notLink) {
                    return cur.getAbsolutePath();
                }
                if (t == null || t.isEmpty()) return cur.getAbsolutePath();
                File next = new File(t);
                if (!next.isAbsolute()) next = new File(cur.getParentFile(), t);
                cur = next.getCanonicalFile();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Resolve an absolute-in-rootfs path (like /usr/bin/python3). */
    private static File rootfsResolve(Context c, String absInRootfs) {
        try {
            File f = new File(alpineDir(c), TarGz.norm(absInRootfs.substring(1)));
            String r = resolveSymlinks(f, 8);
            return (r != null && new File(r).isFile()) ? new File(r) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** First two bytes "#!" → returns {interpreter, rest-of-line}; else null. */
    private static String[] shebang(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[120];
            int n = in.read(b);
            if (n < 2 || b[0] != '#' || b[1] != '!') return null;
            String line = new String(b, 2, Math.max(0, n - 2), "UTF-8");
            int nl = line.indexOf('\n');
            if (nl >= 0) line = line.substring(0, nl);
            line = line.trim();
            if (line.isEmpty()) return null;
            String[] parts = line.split("\\s+");
            return new String[]{parts[0], line};
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ misc

    private static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".part");
        tmp.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes("UTF-8"));
        }
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
    }
}
