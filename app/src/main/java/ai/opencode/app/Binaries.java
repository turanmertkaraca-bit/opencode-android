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
        // shims FIRST: bare `bash`/`git` from the opencode server resolve to
        // the sandbox shims (Sandboxes.ensureShims); everything else falls
        // through to the host. Idempotent + cheap.
        Sandboxes.ensureShims(c);
        e.put("PATH", files + "/shims:" + files + ":/system/bin:/system/xbin");
        e.put("XDG_DATA_HOME", home + "/.local/share");
        e.put("XDG_CONFIG_HOME", home + "/.config");
        e.put("XDG_CACHE_HOME", home + "/.cache");
    }
}
