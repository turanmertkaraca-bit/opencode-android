package ai.opencode.app;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/**
 * Binary / credential file management for the P1 skeleton.
 *
 * Layout under app-private storage (all writable by us, exec verified by P0):
 *   filesDir/opencode                    — the 175 MB opencode binary (mode 755)
 *   filesDir/home/                       — $HOME handed to the opencode process
 *     home/.local/share/opencode/auth.json   — provider credentials
 *     home/.config/opencode/opencode.json    — server / model config
 */
public final class Binaries {

    private Binaries() {}

    public static File binaryFile(Context c) {
        return new File(c.getFilesDir(), "opencode");
    }

    /** Ready = present + valid ELF (the only gate the server needs). */
    public static boolean binaryReady(Context c) {
        File b = binaryFile(c);
        return b.exists() && isElf(b);
    }

    /**
     * P6: extract the opencode binary BUNDLED in the APK (asset oc_pkg.bin —
     * the same opencode-linux-arm64 tarball, renamed so aapt2 cannot
     * decompress or rename it). Fresh installs skip the SAF import dance
     * entirely. Throws on any failure so the UI can show a real error.
     */
    public static void extractBundled(Context c, TarGz.Progress cb) throws IOException {
        File bin = binaryFile(c);
        if (bin.exists()) bin.delete();
        InputStream in;
        try {
            in = c.getAssets().open("oc_pkg.bin");
        } catch (Exception e) {
            try {
                in = c.getAssets().open("oc_pkg");
            } catch (Exception e2) {
                throw new IOException("bundled package missing from APK");
            }
        }
        boolean gz;
        try {
            byte[] m = new byte[2];
            int n = in.read(m);
            gz = (n == 2 && m[0] == 0x1f && m[1] == (byte) 0x8b);
            in.close();
            in = c.getAssets().open(gz ? "oc_pkg.bin" : "oc_pkg");
        } catch (IOException e) {
            throw new IOException("cannot read bundled package");
        }
        try (InputStream src = in) {
            TarGz.extractAll(src, c.getFilesDir(), gz, cb);
        }
        if (!isElf(bin))
            throw new IOException("bundled package did not produce an ELF binary");
        makeExec(bin);
    }

    public static File homeDir(Context c) {
        File f = new File(c.getFilesDir(), "home");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File authFile(Context c) {
        File d = new File(homeDir(c), ".local/share/opencode");
        if (!d.exists()) d.mkdirs();
        return new File(d, "auth.json");
    }

    public static File configFile(Context c) {
        File d = new File(homeDir(c), ".config/opencode");
        if (!d.exists()) d.mkdirs();
        return new File(d, "opencode.json");
    }

    /** Stream a SAF Uri into dst; returns bytes written. */
    public static long copyFromUri(Context c, Uri uri, File dst) throws IOException {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open " + uri);
        long total = 0;
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("rename failed");
        }
        return total;
    }

    public static boolean isElf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] m = new byte[4];
            if (in.read(m) != 4) return false;
            return m[0] == 0x7f && m[1] == 'E' && m[2] == 'L' && m[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    /** chmod 755 via /system/bin/sh — the exact pattern the P0 probe verified. */
    public static void makeExec(File f) throws IOException {
        Process p = new ProcessBuilder("sh", "-c", "chmod 755 " + f.getAbsolutePath()).start();
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroy(); throw new IOException("chmod timeout"); }
            if (p.exitValue() != 0) throw new IOException("chmod exit " + p.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("chmod interrupted");
        }
    }

    /** First 16 hex chars of the file's sha256 — enough to fingerprint on screen. */
    public static String sha256(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    public static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024) return String.format("%.0f MB", bytes / 1048576.0);
        return String.format("%.0f KB", bytes / 1024.0);
    }

    /**
     * Run `opencode --version` with the same environment ServerService uses,
     * capture stdout. Null on failure. (P0 probe verified this path: 1412 ms.)
     */
    public static String probeVersion(Context c, File bin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "--version");
            pb.redirectErrorStream(true);
            applyEnv(c, pb);
            Process p = pb.start();
            String out = Api.readAll(p.getInputStream());
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroy(); return null; }
            if (p.exitValue() != 0) return null;
            out = out.trim();
            return out.isEmpty() ? null : out.split("\n")[0].trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Build the process environment opencode expects inside the app sandbox. */
    public static void applyEnv(Context c, ProcessBuilder pb) {
        String home = homeDir(c).getAbsolutePath();
        String files = c.getFilesDir().getAbsolutePath();
        java.util.Map<String, String> e = pb.environment();
        e.put("HOME", home);
        e.put("TMPDIR", c.getCacheDir().getAbsolutePath());
        // P7 native shims (no proot): bin/ (user + busybox) → P9 wrappers/ (alpine)
        // → shims/ (bash/git/pkg fallbacks) → system.
        Shims.ensure(c);
        e.put("PATH", files + "/bin:" + files + "/wrappers:" + files
                + "/shims:" + files
                + ":/system/bin:/system/xbin");
        e.put("XDG_DATA_HOME", home + "/.local/share");
        e.put("XDG_CONFIG_HOME", home + "/.config");
        e.put("XDG_CACHE_HOME", home + "/.cache");
        // P9: alpine toolkit — refresh the musl-world proxy file when ready;
        // otherwise kick a one-shot background extraction (never blocks spawn).
        try {
            if (Sandbox.ready(c)) Sandbox.refreshProxy(c);
            else Sandbox.ensureAsync(c);
        } catch (Exception ignored) {}
        // P7 DNS: the bundled binary is a bionic/NDK Android build (interpreter
        // /system/bin/linker64 — verified), so it uses netd DNS natively, like
        // every Termux program. The local CONNECT proxy is an OPT-IN escape
        // hatch (Diagnostics → "DNS bridge") for devices with exotic DNS/VPN
        // setups; it is OFF by default so provider traffic takes the direct,
        // proven path.
        boolean bridge = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                .getBoolean("dns_bridge", false);
        if (bridge) {
            int pp = ProxyServer.ensureStarted(c);
            if (pp > 0) {
                String px = "http://127.0.0.1:" + pp;
                String no = "127.0.0.1,localhost,::1";
                e.put("HTTPS_PROXY", px); e.put("https_proxy", px);
                e.put("HTTP_PROXY", px);  e.put("http_proxy", px);
                e.put("ALL_PROXY", px);   e.put("all_proxy", px);
                e.put("NO_PROXY", no);    e.put("no_proxy", no);
            }
        }
    }
}
