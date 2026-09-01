package ai.opencode.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * P3a: the agent sandbox — "a full termux inside the app that only the
 * agent has access to" (the user's original vision).
 *
 * Mechanics:
 *  - assets ship a static proot (5.3.0 aarch64), a static busybox, and an
 *    Alpine 3.20.9 minirootfs tarball (~4 MB).
 *  - install(): copy assets into files/sandbox/, extract the rootfs with
 *    busybox tar (Android's Java API cannot create the symlinks a Linux
 *    rootfs needs — busybox can), then write PATH shims.
 *  - shims: filesDir/shims/{bash,git} — bare-name lookups from the opencode
 *    server process hit these first (PATH order), so the agent's bash tool
 *    runs INSIDE alpine via proot while opencode itself stays on the host.
 *    Same-path binds keep host-written files visible to guest commands.
 *  - installTools(): apk add bash git nodejs npm python3 openssh-client
 *    inside the sandbox (needs device network).
 *
 * targetSdk-28 exec policy applies to proot/busybox exactly as to the
 * opencode binary — P0 verified on device.
 */
public final class Sandboxes {

    private Sandboxes() {}

    public static final String ALPINE_VERSION = "3.20.9";
    public static final String PROOT_VERSION = "5.3.0";
    public static final String TOOLS_LIST = "bash git nodejs npm python3 ssh";

    public interface Progress { void on(String msg); }

    public static File dir(Context c) {
        File f = new File(c.getFilesDir(), "sandbox");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File prootFile(Context c) { return new File(dir(c), "proot"); }
    public static File busyboxFile(Context c) { return new File(dir(c), "busybox"); }
    public static File rootfsDir(Context c) { return new File(dir(c), "rootfs"); }
    public static File shimsDir(Context c) {
        File f = new File(c.getFilesDir(), "shims");
        if (!f.exists()) f.mkdirs();
        return f;
    }
    public static File toolsMarker(Context c) { return new File(dir(c), "tools.txt"); }
    public static File apkLog(Context c) { return new File(dir(c), "apk.log"); }

    public static boolean installed(Context c) {
        return prootFile(c).exists()
                && new File(rootfsDir(c), "etc/alpine-release").exists();
    }

    public static String status(Context c) {
        if (!installed(c)) return "not installed";
        String s = "ready · alpine " + ALPINE_VERSION + " · proot " + PROOT_VERSION;
        File t = toolsMarker(c);
        if (t.exists()) {
            try {
                String tools = new String(readAll(t)).trim();
                if (!tools.isEmpty()) s += " · tools: " + tools;
            } catch (Exception ignored) {}
        }
        return s;
    }

    // ------------------------------------------------------------- install

    /** Blocking sandbox install. Call from a background thread. */
    public static void install(Context c, Progress cb) throws Exception {
        cb.on("copying proot…");
        copyAsset(c, "proot", prootFile(c));
        Binaries.makeExec(prootFile(c));

        cb.on("copying busybox…");
        copyAsset(c, "busybox", busyboxFile(c));
        Binaries.makeExec(busyboxFile(c));

        File pkg = new File(dir(c), "rootfs.pkg");
        cb.on("copying rootfs package…");
        // AGP strips the .gz suffix (and may decompress) for asset packaging —
        // accept both names, then detect gzip by magic when extracting.
        copyAssetAny(c, new String[]{"rootfs.tar.gz", "rootfs.tar"}, pkg);

        File rf = rootfsDir(c);
        if (rf.exists()) deleteTree(rf);
        rf.mkdirs();

        boolean gz;
        {
            FileInputStream in = new FileInputStream(pkg);
            byte[] m = new byte[2];
            int got = in.read(m);
            in.close();
            gz = got == 2 && m[0] == 0x1f && m[1] == (byte) 0x8b;
        }

        boolean done = false;
        String tarErr = "";
        try {
            cb.on("extracting rootfs with busybox tar…");
            Process p = new ProcessBuilder(busyboxFile(c).getAbsolutePath(), "tar",
                    gz ? "-xzf" : "-xf", pkg.getAbsolutePath(), "-C", rf.getAbsolutePath()).start();
            tarErr = Api.readAll(p.getErrorStream());
            if (!p.waitFor(180, TimeUnit.SECONDS)) p.destroy();
            // busybox tar can exit non-zero on harmless chown-to-root errors
            // while the extraction itself is complete — verify by content.
            done = rootfsLooksComplete(rf);
        } catch (Exception e) {
            tarErr = (tarErr == null ? "" : tarErr) + "\n" + e;
        }

        if (!done) {
            cb.on("busybox tar unusable — extracting in Java…");
            deleteTree(rf);
            rf.mkdirs();
            FileInputStream in = new FileInputStream(pkg);
            try {
                TarGz.extractAll(in, rf, gz, cb::on); // method ref bridges Progress types
            } finally {
                try { in.close(); } catch (Exception ignored) {}
            }
            done = rootfsLooksComplete(rf);
        }

        // always keep the raw tar stderr for diagnosis
        try (FileOutputStream tl = new FileOutputStream(new File(dir(c), "tar.log"))) {
            tl.write(("gzip=" + gz + "\n---- busybox tar stderr ----\n" + tarErr)
                    .getBytes("UTF-8"));
        } catch (Exception ignored) {}

        if (!done) throw new Exception("rootfs incomplete — see tar.log");
        pkg.delete();

        cb.on("writing shims…");
        ensureShims(c);

        // P4: verify proot can actually enter the rootfs BEFORE claiming ready.
        // The glue-rootfs temp-dir bug shipped as "sandbox ready" and then
        // broke every single guest command — catch it at install time now.
        cb.on("verifying proot…");
        File pt = new File(c.getCacheDir(), "proot");
        pt.mkdirs();
        int code = -1;
        String pout;
        try {
            ProcessBuilder pb = new ProcessBuilder(prootFile(c).getAbsolutePath(),
                    "-R", rf.getAbsolutePath(), "/bin/echo", "oc-proot-ok");
            pb.redirectErrorStream(true);
            pb.environment().put("PROOT_TMP_DIR", pt.getAbsolutePath());
            Binaries.applyEnv(c, pb);
            Process pr = pb.start();
            pout = Api.readAll(pr.getInputStream());
            if (!pr.waitFor(60, TimeUnit.SECONDS)) {
                pr.destroy();
                pout = "(timed out) " + pout;
            } else {
                code = pr.exitValue();
            }
        } catch (Exception e) {
            pout = String.valueOf(e);
        }
        try (FileOutputStream pl = new FileOutputStream(new File(dir(c), "proot.log"))) {
            pl.write(("PROOT_TMP_DIR=" + pt.getAbsolutePath() + "\nexit=" + code
                    + "\n---- output ----\n" + pout).getBytes("UTF-8"));
        } catch (Exception ignored) {}
        if (code != 0 || !pout.contains("oc-proot-ok")) {
            throw new Exception("proot self-test failed — see sandbox/proot.log");
        }

        cb.on("sandbox ready — restart the server");
    }

    /** Blocking tool install inside the sandbox. Background thread + network. */
    public static void installTools(Context c, Progress cb) throws Exception {
        if (!installed(c)) throw new Exception("install the sandbox first");
        cb.on("apk add " + TOOLS_LIST + "…");
        File pt = new File(c.getCacheDir(), "proot");
        pt.mkdirs();
        ProcessBuilder pb = new ProcessBuilder(
                prootFile(c).getAbsolutePath(),
                "-R", rootfsDir(c).getAbsolutePath(),
                "-b", Binaries.homeDir(c).getAbsolutePath(),
                "-b", c.getCacheDir().getAbsolutePath(),
                "/sbin/apk", "add", "--no-cache",
                "bash", "git", "nodejs", "npm", "python3", "openssh-client");
        pb.redirectErrorStream(true);
        Binaries.applyEnv(c, pb);
        // P4 fix: proot needs a writable glue-rootfs temp dir (see shims above)
        pb.environment().put("PROOT_TMP_DIR", pt.getAbsolutePath());
        Process p = pb.start();
        String log = Api.readAll(p.getInputStream());
        try (OutputStream out = new FileOutputStream(apkLog(c))) {
            out.write(log.getBytes("UTF-8"));
        }
        if (!p.waitFor(600, TimeUnit.SECONDS)) {
            p.destroy();
            throw new Exception("apk timed out (partial log in apk.log)");
        }
        if (p.exitValue() != 0) throw new Exception("apk failed — see apk.log");
        FileOutputStream fm = new FileOutputStream(toolsMarker(c));
        fm.write(TOOLS_LIST.getBytes("UTF-8"));
        fm.close();
        cb.on("tools installed");
    }

    // --------------------------------------------------------------- shims

    /**
     * Idempotently (re)writes the PATH shims. The bash shim routes the
     * agent's shell into the sandbox when installed; sh and git shims cover
     * the other bare-name lookups opencode may make. Same-path binds make
     * host-side files visible inside the guest.
     */
    public static void ensureShims(Context c) {
        String f = c.getFilesDir().getAbsolutePath();
        String cache = c.getCacheDir().getAbsolutePath();
        String pt = cache + "/proot";
        String bash =
                "#!/system/bin/sh\n" +
                "S=" + f + "/sandbox\n" +
                "if [ -x \"$S/proot\" ] && [ -f \"$S/rootfs/etc/alpine-release\" ]; then\n" +
                "  export HOME=" + f + "/home\n" +
                "  # P4 fix: proot builds its glue rootfs in PROOT_TMP_DIR (falls\n" +
                "  # back to TMPDIR). /tmp is NOT writable on Android, so exporting\n" +
                "  # TMPDIR=/tmp made every proot run die with:\n" +
                "  #   can't create temporary directory: Permission denied\n" +
                "  export PROOT_TMP_DIR=" + pt + "\n" +
                "  mkdir -p \"$PROOT_TMP_DIR\" 2>/dev/null\n" +
                "  export TMPDIR=/tmp\n" +
                "  export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "  exec \"$S/proot\" -R \"$S/rootfs\" \\\n" +
                "    -b \"" + f + "/home\" \\\n" +
                "    -b \"" + cache + "\" \\\n" +
                "    -b /sdcard \\\n" +
                "    -w \"" + f + "/home\" /bin/sh \"$@\"\n" +
                "fi\n" +
                "exec /system/bin/sh \"$@\"\n";
        String git =
                "#!/system/bin/sh\n" +
                "S=" + f + "/sandbox\n" +
                "if [ -x \"$S/proot\" ] && [ -x \"$S/rootfs/usr/bin/git\" ]; then\n" +
                "  export HOME=" + f + "/home\n" +
                "  export PROOT_TMP_DIR=" + pt + "\n" +
                "  mkdir -p \"$PROOT_TMP_DIR\" 2>/dev/null\n" +
                "  exec \"$S/proot\" -R \"$S/rootfs\" \\\n" +
                "    -b \"" + f + "/home\" \\\n" +
                "    -b \"" + cache + "\" \\\n" +
                "    -b /sdcard \\\n" +
                "    -w \"" + f + "/home\" /usr/bin/git \"$@\"\n" +
                "fi\n" +
                "echo \"git unavailable (sandbox tools not installed)\" >&2\n" +
                "exit 127\n";
        writeShim(c, "bash", bash);
        writeShim(c, "git", git);
    }

    private static void writeShim(Context c, String name, String script) {
        try {
            File s = new File(shimsDir(c), name);
            FileOutputStream o = new FileOutputStream(s);
            o.write(script.getBytes("UTF-8"));
            o.close();
            Binaries.makeExec(s);
        } catch (Exception ignored) {}
    }

    /** Sanity check after extraction: marker + busybox + plausible file count. */
    private static boolean rootfsLooksComplete(File rf) {
        if (!new File(rf, "etc/alpine-release").exists()) return false;
        if (!new File(rf, "bin/busybox").exists()) return false;
        int[] count = {0};
        countTree(rf, count);
        return count[0] > 300; // rootfs has 87 files + 334 links + 97 dirs
    }

    private static void countTree(File f, int[] count) {
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            count[0]++;
            if (count[0] > 10000) return;
            if (k.isDirectory()) countTree(k, count);
        }
    }

    // --------------------------------------------------------------- utils

    private static void copyAssetAny(Context c, String[] names, File dst) throws Exception {
        Exception last = null;
        for (String name : names) {
            try {
                copyAsset(c, name, dst);
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw (last != null) ? last : new Exception("asset not found");
    }

    private static void copyAsset(Context c, String name, File dst) throws Exception {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
        InputStream in = c.getAssets().open(name);
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) throw new Exception("rename failed for " + name);
    }

    public static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }
}
