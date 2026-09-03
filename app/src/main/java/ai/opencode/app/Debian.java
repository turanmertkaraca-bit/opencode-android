package ai.opencode.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * P12 — the Debian layer: a REAL Debian 12 (bookworm) userland with apt,
 * run through proot (user-space rootfs — no root needed, the Termux
 * proot-distro pattern), answering the user's ask verbatim:
 *
 *   "cant it be debian with apt? and in every difrent project it just
 *    binds the projects folder and only that project so i wont have to
 *    install pacgages all over again"
 *
 * DESIGN
 *   files/debian/rootfs/   ONE shared rootfs for ALL projects. Packages
 *                          install ONCE (apt), every project session just
 *                          binds ITS OWN folder at the real device path.
 *   files/debianbin/       bundled Termux proot + loader + libtalloc +
 *                          libandroid-shmem (bionic binaries, exec-allowed
 *                          private storage — same pattern as opencode).
 *
 * ROOTFS SOURCE (the bits that ship as the official debian:bookworm
 * docker image — Debian's own debuerreotype build):
 *   1. GET https://auth.docker.io/token (anonymous pull scope)
 *   2. GET registry manifest list for library/debian:bookworm → arm64 digest
 *   3. GET arm64 manifest → single layer digest + size
 *   4. GET blob (~48 MB tar.gz) → TarGz.extractAll with the Debian link
 *      policy (absolute symlinks rewritten relative, like Alpine's)
 *
 * SAFETY
 *   Debian mode is OPT-IN and PROBED: after extraction we run
 *   `proot … /bin/echo probe-ok`; Debian shells activate only when the
 *   probe passes. If the device refuses proot (ptrace quirks), the Lite
 *   (Alpine) layer stays the shell and the UI says so plainly — the env
 *   can never get silently worse than P11.
 *
 * NETWORK
 *   Same trick as the Alpine layer, upgraded for glibc: every Debian
 *   shell exports http(s)_proxy pointing at the in-app Java proxy
 *   (ProxyServer — CONNECT + absolute-URI GET, OS-resolver DNS), so apt,
 *   git, curl, pip all work without a real DNS path. /etc/resolv.conf
 *   inside the rootfs is a decoy pointing at 127.0.0.1. apt sources are
 *   forced to http:// until ca-certificates is installed (install flow
 *   does that first), then https works too through the CONNECT tunnel.
 *
 * PROJECT BINDS (per-session, at the REAL device path so tool outputs and
 * shell paths always agree):
 *   -b <projectDir>:<projectDir>        the project — only that project
 *   -b /storage/emulated/0/Download     exports land in Downloads
 *   -b <appHome>:<appHome>              opencode auth/config visible
 *   -b /dev -b /proc -b /sys
 */
public final class Debian {

    private Debian() {}

    public static final String TAG_VER = "bookworm";

    private static final String REG_AUTH =
            "https://auth.docker.io/token?service=registry.docker.io"
                    + "&scope=repository:library/debian:pull";
    private static final String REG =
            "https://registry-1.docker.io/v2/library/debian/";

    private static volatile boolean installing;

    // ------------------------------------------------------------ paths

    public static File dir(Context c) {
        return new File(c.getFilesDir(), "debian");
    }

    public static File rootfsDir(Context c) {
        return new File(dir(c), "rootfs");
    }

    /** proot + loader + libs land here (mode 755, exec-allowed). */
    public static File binDir(Context c) {
        return new File(dir(c), "bin");
    }

    public static File libDir(Context c) {
        return new File(dir(c), "lib");
    }

    /** marker: rootfs fully extracted + configured */
    public static boolean extracted(Context c) {
        return new File(dir(c), ".extracted").exists()
                && new File(rootfsDir(c), "bin/bash").exists();
    }

    /** marker: proot probe passed at least once */
    public static boolean probeOk(Context c) {
        return new File(dir(c), ".probe").exists();
    }

    /** Debian shells active = extracted + probe passed + user switch on. */
    public static boolean active(Context c) {
        return extracted(c) && probeOk(c)
                && c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                        .getBoolean("debian_shell", true);
    }

    /** User-facing one-line status for Settings / Diagnostics. */
    public static String status(Context c) {
        if (!extracted(c)) return "not installed";
        if (!probeOk(c)) {
            boolean rep = new File(dir(c), "probe-report.txt").exists();
            return "installed · proot probe failed — Lite shell active"
                    + (rep ? " · report saved (Sandbox doctor)" : "");
        }
        return c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getBoolean("debian_shell", true)
                ? "active · Debian 12 + apt" : "installed · switched off";
    }

    public static long sizeOf(Context c) {
        return Sandbox.sizeOf(dir(c));
    }

    /**
     * P15 — create EVERY directory the proot layer needs BEFORE anything
     * runs. The field report (via the agent itself) was exact:
     *
     *   "App needs to initialize its directories on first launch — create
     *    these paths before starting proot: files/debian/tmp, files/home …"
     *
     * The killer was files/debian/tmp: PROOT_TMP_DIR pointed there but
     * nothing ever mkdir'd it, so proot's mkdtemp failed on a fresh install
     * and the probe/install died with a temp-dir error that looked like
     * "proot broken". files/home is Binaries.homeDir's job but we belt it
     * here too; rootfs/tmp matters for guest TMPDIR. Never throws.
     */
    public static void ensureDirs(Context c) {
        try {
            if (!dir(c).exists()) dir(c).mkdirs();
            File tmp = new File(dir(c), "tmp");          // PROOT_TMP_DIR target
            if (!tmp.exists()) tmp.mkdirs();
            Binaries.homeDir(c);                          // files/home (mkdirs)
            if (extracted(c)) {                           // guest TMPDIR=/tmp
                File rtmp = new File(rootfsDir(c), "tmp");
                if (!rtmp.exists()) rtmp.mkdirs();
            }
        } catch (Exception ignored) {}
    }

    /**
     * P15 — the environment report the agent asked for: "auto-detect proot,
     * Android, and display a clean summary (/proc/version, uname, pwd,
     * whoami)". Runs INSIDE Debian when active; falls back to a host-side
     * summary otherwise. Also written to files/debian/env.txt so the agent
     * itself can read exactly what it's standing in. Never throws.
     */
    public static String envReport(Context c) {
        ensureDirs(c);
        StringBuilder s = new StringBuilder();
        if (active(c)) {
            StringBuilder out = new StringBuilder();
            runGuest(c,
                    "echo \"kernel : $(uname -r)\"; "
                  + "echo \"arch   : $(uname -m)\"; "
                  + "echo \"user   : $(whoami)\"; "
                  + "echo \"cwd    : $(pwd)\"; "
                  + "echo \"os     : $(cat /etc/os-release 2>/dev/null "
                  + "| grep PRETTY_NAME | cut -d\\\" -f2)\"; "
                  + "echo \"tools  : $(command -v apt >/dev/null && echo apt)"
                  + " $(command -v git >/dev/null && echo git)"
                  + " $(command -v python3 >/dev/null && echo python3)\"",
                    out, 20);
            String body = out.toString().trim();
            if (body.contains("kernel")) {
                s.append("sandbox environment\n").append(body);
            } else {
                s.append("sandbox: Debian present but the probe answer was "
                        + "empty — see Sandbox doctor");
            }
        } else {
            s.append("sandbox: Lite shell (Debian ")
             .append(extracted(c) ? "probe failed" : "not installed").append(")\n")
             .append("host   : Android \u2014 /system/bin/sh\n")
             .append("tip    : Settings \u2192 Sandbox \u2192 Install Debian for apt + git");
        }
        try {   // storage visibility — the other half of the field report
            java.io.File dl = new java.io.File("/storage/emulated/0/Download");
            boolean dlOk = dl.isDirectory();
            File proj = ServerService.servingDir();
            s.append("\nstorage: Download ").append(dlOk ? "reachable" : "NOT visible")
             .append(dlOk ? "" : " — grant \u201cAll files access\u201d for "
                        + "Android Settings \u2192 Apps \u2192 opencode")
             .append("\nproject: ").append(proj == null ? "(none)"
                        : proj.getAbsolutePath());
        } catch (Exception ignored) {}
        String rep = s.toString();
        try { writeText(new File(dir(c), "env.txt"), rep + "\n"); }
        catch (Exception ignored) {}
        return rep;
    }

    // ----------------------------------------------------------- install

    /** Progress sink for the installer UI. */
    public interface Progress { void on(String msg); }

    /**
     * Full install: binaries → download → extract → configure → probe →
     * seed git + repo clone. Blocking; call from a worker thread.
     * Idempotent: skips whatever is already done.
     */
    public static synchronized boolean install(Context c, Progress cb) {
        ensureDirs(c);                          // P15: tmp dirs BEFORE anything
        if (extracted(c)) { probe(c, cb); return probeOk(c); }
        if (installing) return false;
        installing = true;
        try {
            say(cb, "unpacking proot toolkit…");
            unpackBinaries(c);

            File blob = new File(c.getCacheDir(), "debian-rootfs.tar.gz");
            if (!blob.exists() || blob.length() < 30_000_000) {
                say(cb, "resolving debian:bookworm arm64 image…");
                String digest = resolveLayer(c);
                say(cb, "downloading Debian 12 rootfs (~48 MB)…");
                downloadBlob(c, digest, blob);
            }
            say(cb, "extracting rootfs (~150 MB, one-time)…");
            extract(c, blob, cb);
            blob.delete();

            say(cb, "configuring apt + DNS bridge…");
            configure(c);

            // marker BEFORE the probe so a probe crash doesn't re-extract
            writeMarker(new File(dir(c), ".extracted"), "ok\n");

            say(cb, "probing proot on this device…");
            boolean ok = probe(c, cb);
            if (!ok) {
                say(cb, "proot not supported here — Lite shell stays active");
                return false;
            }

            say(cb, "installing git + certificates (apt)…");
            aptBootstrap(c, cb);

            say(cb, "cloning opencode-android for analysis…");
            cloneRepo(c);

            say(cb, "Debian ready — ask the agent to run `apt install …`");
            return true;
        } catch (Exception e) {
            say(cb, "install failed: " + e);
            return false;
        } finally {
            installing = false;
        }
    }

    private static void say(Progress cb, String s) {
        android.util.Log.i("oc-debian", s);
        if (cb != null) cb.on(s);
    }

    /** proot + loader + libs out of assets (renamed to dodge aapt2). */
    private static void unpackBinaries(Context c) throws IOException {
        File bin = binDir(c), lib = libDir(c);
        bin.mkdirs();
        lib.mkdirs();
        copyAssetExec(c, "db_proot", new File(bin, "proot"));
        copyAssetExec(c, "db_loader", new File(bin, "loader"));
        copyAssetExec(c, "db_shmem", new File(lib, "libandroid-shmem.so"));
        // talloc keeps its SONAME so LD_LIBRARY_PATH resolves it
        copyAssetExec(c, "db_talloc", new File(lib, "libtalloc.so.2"));
    }

    // --------------------------------------------------- registry dance

    private static String httpGet(String url, String auth, String accept,
                                  int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setUseCaches(false);
        if (auth != null) conn.setRequestProperty("Authorization", "Bearer " + auth);
        if (accept != null) conn.setRequestProperty("Accept", accept);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " from " + hostOf(url));
        }
        String body = Api.readAll(conn.getInputStream());
        conn.disconnect();
        return body;
    }

    private static String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }

    /** manifest list → arm64 digest → manifest → first layer digest. */
    private static String resolveLayer(Context c) throws IOException {
        String tok = httpGet(REG_AUTH, null, null, 15_000);
        String token = jstr(Json.obj(Json.parse(tok)), "token");
        if (token == null) throw new IOException("registry auth failed");

        String list = httpGet(REG + "manifests/" + TAG_VER, token,
                "application/vnd.docker.distribution.manifest.list.v2+json,"
                        + " application/vnd.oci.image.index.v1+json", 20_000);
        String armDigest = null;
        Map<String, Object> lm = Json.obj(Json.parse(list));
        List<Object> mans = lm == null ? null : Json.arr(lm.get("manifests"));
        if (mans != null) {
            for (Object o : mans) {
                Map<String, Object> m = Json.obj(o);
                if (m == null) continue;
                Map<String, Object> p = Json.map(m, "platform");
                if (p == null) continue;
                if ("linux".equals(p.get("os")) && "arm64".equals(p.get("architecture"))) {
                    armDigest = Json.str(m, "digest");
                    break;
                }
            }
        }
        if (armDigest == null) throw new IOException("no linux/arm64 manifest for " + TAG_VER);

        String man = httpGet(REG + "manifests/" + armDigest, token,
                "application/vnd.docker.distribution.manifest.v2+json,"
                        + " application/vnd.oci.image.manifest.v1+json", 20_000);
        Map<String, Object> mm = Json.obj(Json.parse(man));
        List<Object> layers = mm == null ? null : Json.arr(mm.get("layers"));
        if (layers == null || layers.isEmpty()) throw new IOException("image has no layers");
        Map<String, Object> layer = Json.obj(layers.get(0));
        String digest = Json.str(layer, "digest");
        if (digest == null) throw new IOException("layer digest missing");
        return digest;
    }

    private static void downloadBlob(Context c, String digest, File dst)
            throws IOException {
        String tokBody = httpGet(REG_AUTH, null, null, 15_000);
        String token = jstr(Json.obj(Json.parse(tokBody)), "token");
        if (token == null) throw new IOException("registry auth failed");
        HttpURLConnection conn = (HttpURLConnection)
                new URL(REG + "blobs/" + digest).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.setUseCaches(false);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        if (conn.getResponseCode() != 200)
            throw new IOException("blob HTTP " + conn.getResponseCode());
        long total = conn.getContentLength();
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0 && done % (8 << 20) < buf.length)
                    say(null, "downloading Debian rootfs… "
                            + (done / (1 << 20)) + " MB / " + (total / (1 << 20)) + " MB");
            }
        }
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("blob rename failed");
    }

    private static String jstr(Map<String, Object> m, String k) {
        return m == null ? null : (String) m.get(k);
    }

    // ------------------------------------------------------- extraction

    /** Extract the layer with Debian's symlink policy (absolute links
     *  rewritten relative to the rootfs root — same as Alpine's). */
    private static void extract(Context c, File blob, Progress cb) throws IOException {
        File rootfs = rootfsDir(c);
        rootfs.mkdirs();
        try (InputStream in = new FileInputStream(blob)) {
            // P13: pass a REAL progress sink — the P12 build passed null and
            // the very first cb.on() NPE'd the whole install (the user's
            // "install failed: NullPointerException … TarGz$Progress.on").
            TarGz.extractAll(in, rootfs, true, msg -> say(cb, msg), Debian::debLink);
        }
    }

    private static void debLink(File destDir, String name, String target, boolean exec) {
        try {
            File dst = new File(destDir, name);
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            if (dst.exists()) dst.delete();
            String t = target;
            if (t.startsWith("/")) t = relFromRoot(name, t.substring(1));
            try {
                android.system.Os.symlink(t, dst.getAbsolutePath());
            } catch (Exception linkFail) {
                return; // Debian has no single busybox to copy-fallback to
            }
            if (exec) dst.setExecutable(true, false);
        } catch (Exception ignored) {}
    }

    private static String relFromRoot(String linkName, String rootRel) {
        int depth = 0;
        for (int i = 0; i < linkName.length(); i++)
            if (linkName.charAt(i) == '/') depth++;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) b.append("../");
        b.append(rootRel);
        return b.toString();
    }

    // ------------------------------------------------------- configure

    private static void configure(Context c) throws IOException {
        File rootfs = rootfsDir(c);
        // apt sources: plain http first (no ca bundle yet); the bootstrap
        // step installs ca-certificates, after which https also works via
        // the CONNECT tunnel.
        writeText(new File(rootfs, "etc/apt/sources.list"),
                "deb http://deb.debian.org/debian " + TAG_VER + " main contrib non-free non-free-firmware\n"
              + "deb http://security.debian.org/debian-security " + TAG_VER + "-security main contrib non-free non-free-firmware\n");
        File sld = new File(rootfs, "etc/apt/sources.list.d");
        File[] old = sld.listFiles();
        if (old != null) for (File f : old) f.delete();
        // DNS decoy: nothing listens on 127.0.0.1:53 — every networked tool
        // gets the proxy env from the shell wrapper instead (like Alpine).
        writeText(new File(rootfs, "etc/resolv.conf"),
                "nameserver 127.0.0.1\n# DNS rides on http_proxy (in-app bridge)\n");
        // apt: no docs, no lock chatter, retries
        writeText(new File(rootfs, "etc/apt/apt.conf.d/99opencode"),
                "APT::Install-Recommends \"false\";\n"
              + "Acquire::Retries \"3\";\n"
              + "Dpkg::Options { \"--force-confnew\"; };\n");
        // git safe.directory for the bound project paths (different uid)
        writeText(new File(rootfs, "root/.gitconfig"),
                "[safe]\n\tdirectory = *\n");
        new File(rootfs, "root/project").mkdirs();
        new File(rootfs, "tmp").mkdirs();
    }

    private static void writeMarker(File f, String s) throws IOException {
        writeText(f, s);
    }

    private static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".part");
        tmp.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
    }

    // ------------------------------------------------------------ proot

    /**
     * The proot argv for one guest command — THE single place the launch
     * recipe lives (rehearsed against proot-distro's own assembly).
     * Everything is absolute; binDir is prepped by prepEnv().
     */
    public static ArrayList<String> prootArgv(Context c, String command) {
        File files = c.getFilesDir();
        String rootfs = rootfsDir(c).getAbsolutePath();
        String bin = binDir(c).getAbsolutePath();
        String home = Binaries.homeDir(c).getAbsolutePath();

        ArrayList<String> a = new ArrayList<>();
        a.add(bin + "/proot");
        a.add("--rootfs=" + rootfs);
        a.add("--cwd=/root");
        a.add("-0");                                // fake uid 0 (apt needs it)
        a.add("--kill-on-exit");
        a.add("--link2symlink");                    // Android FS hates hardlinks
        // P13: the experimental "-L" flag is GONE — it was never part of the
        // rehearsed/proven proot-distro recipe, and an unknown flag makes
        // proot print usage and exit 1 (probe would fail as "proot broken").
        a.add("--bind=/dev");
        a.add("--bind=/proc");
        a.add("--bind=/sys");
        // the project — only that project, at its REAL path
        File proj = ServerService.servingDir();
        if (proj != null && proj.isDirectory())
            a.add("--bind=" + proj.getAbsolutePath() + ":" + proj.getAbsolutePath());
        // Downloads for exports
        File dl = new File("/storage/emulated/0/Download");
        if (dl.isDirectory())
            a.add("--bind=" + dl.getAbsolutePath() + ":" + dl.getAbsolutePath());
        // opencode home (auth.json) at the real path
        a.add("--bind=" + home + ":" + home);
        a.add("/bin/bash");
        a.add("-c");
        a.add(command);
        return a;
    }

    /** ProcessBuilder env for a proot run (host side + guest exports). */
    public static ProcessBuilder guestProcess(Context c, String command) {
        ensureDirs(c);                          // P15: belt before every run
        ArrayList<String> argv = prootArgv(c, command);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        java.util.Map<String, String> e = pb.environment();
        String rootfs = rootfsDir(c).getAbsolutePath();
        // --- host-side proot needs ---
        e.put("PROOT_LOADER", binDir(c).getAbsolutePath() + "/loader");
        e.put("PROOT_TMP_DIR", new File(dir(c), "tmp").getAbsolutePath());
        e.put("LD_LIBRARY_PATH", libDir(c).getAbsolutePath());
        e.put("PROOT_IGNORE_MISSING_BINDINGS", "1");
        e.put("PROOT_NO_SECCOMP", "1"); // belt: some kernels misreport seccomp
        // --- guest-side env (proot passes the host env through) ---
        int pp = ProxyServer.ensureStarted(c);
        if (pp > 0) {
            String px = "http://127.0.0.1:" + pp;
            e.put("http_proxy", px);  e.put("https_proxy", px);
            e.put("HTTP_PROXY", px);  e.put("HTTPS_PROXY", px);
            e.put("no_proxy", "127.0.0.1,localhost,::1");
            e.put("NO_PROXY", "127.0.0.1,localhost,::1");
        }
        e.put("DEBIAN_FRONTEND", "noninteractive");
        e.put("HOME", "/root");
        e.put("USER", "root");
        e.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        e.put("TMPDIR", "/tmp");
        e.put("TERM", "xterm-256color");
        String gh = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getString("gh_token", null);
        if (gh != null && !gh.trim().isEmpty())
            e.put("GH_TOKEN", gh.trim());
        return pb;
    }

    // ----------------------------------------------------- launcher script

    /**
     * files/debian/launch — the shell-script twin of guestProcess(): the
     * `bash` shim execs THIS so agent shells land inside Debian. Baked
     * with the current proxy port + project bind; regenerated on every
     * spawn (write-if-different, ~1 ms) so both stay in sync. Same flag
     * set as prootArgv — change both together.
     */
    public static void writeLauncher(Context c) {
        try {
            // P14: mkdir the debian dir FIRST — on a fresh install (or the
            // JVM regression tests) files/debian/ does not exist yet and
            // the FileOutputStream below would silently throw, leaving no
            // launcher at all (caught-and-ignored hid it until now).
            // P15: ensureDirs also creates files/debian/tmp (PROOT_TMP_DIR).
            ensureDirs(c);
            File d = dir(c);
            if (!d.exists()) d.mkdirs();
            File files = c.getFilesDir();
            String bin = binDir(c).getAbsolutePath();
            String lib = libDir(c).getAbsolutePath();
            String rootfs = rootfsDir(c).getAbsolutePath();
            String home = Binaries.homeDir(c).getAbsolutePath();

            StringBuilder s = new StringBuilder();
            s.append("#!/system/bin/sh\n")
             .append("# P12 Debian launcher — generated; edits get overwritten.\n")
             .append("PROOT_LOADER=\"").append(bin).append("/loader\"\n")
             .append("PROOT_TMP_DIR=\"").append(new File(dir(c), "tmp").getAbsolutePath()).append("\"\n")
             .append("LD_LIBRARY_PATH=\"").append(lib).append("\"\n")
             .append("PROOT_IGNORE_MISSING_BINDINGS=1\n")
             .append("PROOT_NO_SECCOMP=1\n")
             .append("export PROOT_LOADER PROOT_TMP_DIR LD_LIBRARY_PATH")
             .append(" PROOT_IGNORE_MISSING_BINDINGS PROOT_NO_SECCOMP\n");
            int pp = ProxyServer.ensureStarted(c);
            if (pp > 0) {
                String px = "http://127.0.0.1:" + pp;
                s.append("http_proxy=\"").append(px).append("\"\n")
                 .append("https_proxy=\"").append(px).append("\"\n")
                 .append("HTTP_PROXY=\"").append(px).append("\"\n")
                 .append("HTTPS_PROXY=\"").append(px).append("\"\n")
                 .append("no_proxy=\"127.0.0.1,localhost,::1\"\n")
                 .append("NO_PROXY=\"127.0.0.1,localhost,::1\"\n")
                 .append("export http_proxy https_proxy HTTP_PROXY HTTPS_PROXY")
                 .append(" no_proxy NO_PROXY\n");
            }
            s.append("DEBIAN_FRONTEND=noninteractive\n")
             .append("HOME=/root\nUSER=root\nTMPDIR=/tmp\nTERM=xterm-256color\n")
             .append("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n")
             .append("export DEBIAN_FRONTEND HOME USER TMPDIR TERM PATH\n");
            String gh = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getString("gh_token", null);
            if (gh != null && !gh.trim().isEmpty())
                s.append("GH_TOKEN=\"").append(gh.trim().replace("\"", "\\\""))
                 .append("\"\nexport GH_TOKEN\n");
            s.append("exec \"").append(bin).append("/proot\"")
             .append(" --rootfs=\"").append(rootfs).append("\"")
             .append(" --cwd=/root")
             .append(" -0 --kill-on-exit --link2symlink")
             .append(" --bind=/dev --bind=/proc --bind=/sys");
            File proj = ServerService.servingDir();
            if (proj != null && proj.isDirectory()) {
                String p = proj.getAbsolutePath();
                s.append(" --bind=\"").append(p).append(":").append(p).append("\"");
            }
            File dl = new File("/storage/emulated/0/Download");
            if (dl.isDirectory())
                s.append(" --bind=\"").append(dl.getAbsolutePath())
                 .append(":").append(dl.getAbsolutePath()).append("\"");
            s.append(" --bind=\"").append(home).append(":").append(home).append("\"");
            s.append(" \"$@\"\n");

            File f = new File(dir(c), "launch");
            File tmp = new File(dir(c), "launch.part");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(s.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (f.exists()) f.delete();
            if (tmp.renameTo(f)) Binaries.makeExec(f);
        } catch (Exception ignored) {}
    }

    /** Run one guest command, capture output. Returns exit code (-1 fail). */
    public static int runGuest(Context c, String command, StringBuilder out,
                               int timeoutSec) {
        try {
            Process p = guestProcess(c, command).start();
            String s = Api.readAll(p.getInputStream());
            if (out != null) out.append(s);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy();
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            if (out != null) out.append(e.toString());
            return -1;
        }
    }

    // -------------------------------------------------- probe + bootstrap

    /**
     * THE gate: can this device trace a guest binary at all? Writes
     * .probe on success so Debian shells activate. Never throws.
     */
    public static boolean probe(Context c, Progress cb) {
        ensureDirs(c);                          // P15: PROOT_TMP_DIR must exist
        try {
            StringBuilder out = new StringBuilder();
            int rc = runGuest(c, "echo probe-ok", out, 30);
            boolean ok = rc == 0 && out.toString().contains("probe-ok");
            if (ok) {
                writeMarker(new File(dir(c), ".probe"), "ok\n");
                new File(dir(c), "probe-report.txt").delete();
                say(cb, "proot probe passed");
            } else {
                new File(dir(c), ".probe").delete();
                // P13: keep the evidence — Settings shows it, the user can
                // paste it, we stop guessing why proot is refused.
                try {
                    ArrayList<String> argv = prootArgv(c, "echo probe-ok");
                    StringBuilder rep = new StringBuilder();
                    rep.append("proot probe failed  rc=").append(rc).append("\n");
                    rep.append("argv: ").append(String.join(" ", argv)).append("\n");
                    rep.append("output:\n").append(out).append('\n');
                    writeText(new File(dir(c), "probe-report.txt"), rep.toString());
                } catch (Exception ignored) {}
                say(cb, "proot probe failed (rc=" + rc + "): " + out);
            }
            return ok;
        } catch (Exception e) {
            new File(dir(c), ".probe").delete();
            try {
                writeText(new File(dir(c), "probe-report.txt"),
                        "proot probe crashed: " + e + "\n");
            } catch (Exception ignored) {}
            say(cb, "proot probe crashed: " + e);
            return false;
        }
    }

    /** apt update + git + ca-certificates (order matters: certs last). */
    private static void aptBootstrap(Context c, Progress cb) {
        StringBuilder out = new StringBuilder();
        runGuest(c, "apt-get update", out, 300);
        say(cb, "apt index refreshed (" + out.length() + " bytes)");
        out.setLength(0);
        runGuest(c, "apt-get install -y --no-install-recommends git ca-certificates", out, 600);
        say(cb, "git installed");
    }

    /** Pre-clone the project repo so "analyse the repo" works instantly. */
    private static void cloneRepo(Context c) {
        StringBuilder out = new StringBuilder();
        runGuest(c,
                "if [ ! -d /root/opencode-android/.git ]; then "
                        + "git clone --depth 1 "
                        + "https://github.com/turanmertkaraca-bit/opencode-android "
                        + "/root/opencode-android 2>&1; fi",
                out, 300);
    }

    // ------------------------------------------------------------ assets

    private static void copyAssetExec(Context c, String name, File dst)
            throws IOException {
        InputStream in = c.getAssets().open(name);
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
        try (OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("rename failed: " + dst);
        Binaries.makeExec(dst);
    }
}
