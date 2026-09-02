package ai.opencode.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * P7: native command shims — the "small Termux" layer, WITHOUT proot.
 *
 * The old P3-P6 sandbox wrapped every agent shell command in proot+Alpine.
 * proot's self-test failed on the user's device, so P7 runs everything
 * natively in the app's exec-allowed private storage (the Termux pattern,
 * targetSdk 28):
 *
 *   files/bin/     — real binaries: bundled busybox + anything the user
 *                    imports via Diagnostics (static arm64 builds). This dir
 *                    is FIRST on PATH so user binaries win.
 *   files/shims/   — tiny #! scripts for names Android lacks. `bash` runs
 *                    a user-imported bash when present, else /system/bin/sh
 *                    (mksh — decent bash compatibility). `git` runs a
 *                    user-imported git when present, else a clear error.
 *
 * Everything that opencode's bash tool spawns resolves through this PATH.
 */
public final class Shims {

    private Shims() {}

    public static File binDir(Context c) {
        File f = new File(c.getFilesDir(), "bin");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File shimsDir(Context c) {
        File f = new File(c.getFilesDir(), "shims");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /** Idempotent; called from Binaries.applyEnv before every spawn. */
    public static void ensure(Context c) {
        try {
            File bin = binDir(c);
            File shims = shimsDir(c);

            // bundled busybox (tar/gzip/curl-ish applets) — copy once
            File bb = new File(bin, "busybox");
            if (!bb.exists()) {
                try {
                    copyAsset(c, "busybox", bb);
                    Binaries.makeExec(bb);
                } catch (Exception ignored) {}
            }

            String f = c.getFilesDir().getAbsolutePath();
            File realBash = new File(bin, "bash");
            String bash =
                    "#!/system/bin/sh\n" +
                    "if [ -x \"" + f + "/bin/bash.real\" ]; then\n" +
                    "  exec \"" + f + "/bin/bash.real\" \"$@\"\n" +
                    "fi\n" +
                    "exec /system/bin/sh \"$@\"\n";
            writeShim(new File(shims, "bash"), bash);

            String git =
                    "#!/system/bin/sh\n" +
                    "if [ -x \"" + f + "/bin/git\" ]; then\n" +
                    "  export HOME=" + f + "/home\n" +
                    "  export TMPDIR=" + c.getCacheDir().getAbsolutePath() + "\n" +
                    "  exec \"" + f + "/bin/git\" \"$@\"\n" +
                    "fi\n" +
                    "echo \"git is not installed in this app. Import a static\" >&2\n" +
                    "echo \"arm64 git binary via the app's Diagnostics screen\" >&2\n" +
                    "echo \"(it lands in bin/ and this shim will use it).\" >&2\n" +
                    "exit 127\n";
            writeShim(new File(shims, "git"), git);

            // keep timestamps fresh so exec is always permitted
            try { bin.setExecutable(true, false); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static void writeShim(File f, String content) {
        try {
            if (f.exists() && content.equals(new String(read(f), "UTF-8"))) return;
            File tmp = new File(f.getParentFile(), f.getName() + ".part");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(content.getBytes("UTF-8"));
            }
            if (f.exists()) f.delete();
            tmp.renameTo(f);
            f.setExecutable(true, false);
            f.setReadable(true, false);
        } catch (Exception ignored) {}
    }

    private static void copyAsset(Context c, String name, File dst) throws IOException {
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
        if (!tmp.renameTo(dst)) throw new IOException("rename failed");
    }

    private static byte[] read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return b;
        } finally {
            in.close();
        }
    }
}
