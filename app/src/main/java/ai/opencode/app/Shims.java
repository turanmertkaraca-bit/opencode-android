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

    /**
     * P9: `pkg` — the package manager front-end the agent discovers via
     * `command -v pkg`. Maps to apk --root inside the Alpine layer and
     * rehashes PATH wrappers after every mutation, so freshly installed
     * commands are immediately runnable (even in the same shell session).
     */
    public static void writePkgShim(Context c) {
        String al = Sandbox.alpineDir(c).getAbsolutePath();
        String files = c.getFilesDir().getAbsolutePath();
        String sh =
            "#!/system/bin/sh\n" +
            "# pkg — sandbox package manager (Alpine layer, no proot)\n" +
            "AL=" + al + "\n" +
            "BIN=" + files + "/bin\n" +
            "WRAP=" + files + "/wrappers\n" +
            "LB=\"$AL/lib/ld-musl-aarch64.so.1\"\n" +
            "export PATH=\"$BIN:$WRAP:$PATH\"\n" +
            "export HOME=\"$AL/home\" TMPDIR=\"$AL/tmp\"\n" +
            "mkdir -p \"$HOME\" \"$TMPDIR\" \"$AL/var/cache/apk\" \"$WRAP\" 2>/dev/null\n" +
            "[ -r \"$AL/.proxy\" ] && . \"$AL/.proxy\"\n" +
            "export LD_LIBRARY_PATH=\"$AL/lib:$AL/usr/lib\"\n" +
            "if [ ! -x \"$LB\" ]; then\n" +
            "  echo \"pkg: sandbox toolkit not installed yet — Settings > Sandbox > Install toolkit\" >&2\n" +
            "  exit 127\n" +
            "fi\n" +
            "apk() { \"$LB\" --library-path \"$AL/lib:$AL/usr/lib\" \"$AL/sbin/apk\" --root \"$AL\" --no-scripts \"$@\"; }\n" +
            "usage() {\n" +
            "  echo \"pkg - sandbox package manager (apk front-end)\"\n" +
            "  echo \"  pkg update              refresh package index\"\n" +
            "  echo \"  pkg install <pkgs...>   install (e.g. pkg install python3 py3-pip git)\"\n" +
            "  echo \"  pkg remove <pkgs...>    remove\"\n" +
            "  echo \"  pkg search <word>       search the repos\"\n" +
            "  echo \"  pkg list                list installed\"\n" +
            "  echo \"  pkg info <pkg>          details for one package\"\n" +
            "  echo \"  pkg rehash              re-link new commands onto PATH\"\n" +
            "  echo \"Popular: python3 py3-pip git nodejs npm gcc make ripgrep jq curl openssh-client\"\n" +
            "}\n" +
            "rehash() {\n" +
            "  made=0\n" +
            "  for d in bin usr/bin sbin usr/sbin usr/local/bin; do\n" +
            "    [ -d \"$AL/$d\" ] || continue\n" +
            "    for f in \"$AL/$d\"/*; do\n" +
            "      [ -f \"$f\" ] || continue\n" +
            "      n=${f##*/}\n" +
            "      [ \"$n\" = apk ] && continue\n" +
            "      [ -e \"$BIN/$n\" ] && continue\n" +
            "      [ -e \"$WRAP/$n\" ] && continue\n" +
            "      {\n" +
            "        printf '#!/system/bin/sh\\nAL=%s\\n' \"$AL\"\n" +
            "        printf '[ -r \"$AL/.proxy\" ] && . \"$AL/.proxy\"\\n'\n" +
            "        printf 'exec \"$AL/lib/ld-musl-aarch64.so.1\" --library-path \"$AL/lib:$AL/usr/lib\"'\n" +
            "      } > \"$WRAP/$n.part\"\n" +
            "      t=$(readlink -f \"$f\" 2>/dev/null); [ -z \"$t\" ] && t=$f\n" +
            "      case \"$t\" in\n" +
            "        */bin/busybox)\n" +
            "          printf ' \"$AL/bin/busybox\" \"%s\"' \"$n\" >> \"$WRAP/$n.part\" ;;\n" +
            "        *)\n" +
            "          case \"$(dd if=\"$t\" bs=2 count=1 2>/dev/null)\" in\n" +
            "            '#!')\n" +
            "              il=$(head -n 1 \"$t\" 2>/dev/null); il=${il##*#!}; il=${il# }; ip=${il%% *}\n" +
            "              case \"$ip\" in\n" +
            "                */python*) printf ' \"$AL/usr/bin/python3\" \"%s\"' \"$t\" >> \"$WRAP/$n.part\" ;;\n" +
            "                */sh|*/ash) printf ' \"$AL/bin/busybox\" sh \"%s\"' \"$t\" >> \"$WRAP/$n.part\" ;;\n" +
            "                *) printf ' \"%s\"' \"$t\" >> \"$WRAP/$n.part\" ;;\n" +
            "              esac ;;\n" +
            "            *) printf ' \"%s\"' \"$t\" >> \"$WRAP/$n.part\" ;;\n" +
            "          esac ;;\n" +
            "      esac\n" +
            "      printf ' \"$@\"\\n' >> \"$WRAP/$n.part\"\n" +
            "      chmod 755 \"$WRAP/$n.part\" 2>/dev/null && mv \"$WRAP/$n.part\" \"$WRAP/$n\"\n" +
            "      made=$((made+1))\n" +
            "    done\n" +
            "  done\n" +
            "  echo \"pkg: linked $made commands\"\n" +
            "}\n" +
            "rc=0\n" +
            "case \"$1\" in\n" +
            "  install|add) shift; apk add \"$@\"; rc=$?; [ $rc -eq 0 ] && rehash ;;\n" +
            "  remove|del|uninstall) shift; apk del \"$@\"; rc=$?; [ $rc -eq 0 ] && rehash ;;\n" +
            "  update) shift; apk update; rc=$? ;;\n" +
            "  upgrade) shift; apk upgrade; rc=$? ;;\n" +
            "  search) shift; [ $# -gt 0 ] && apk search -v \"$@\"; rc=$? ;;\n" +
            "  list|installed) apk info; rc=$? ;;\n" +
            "  info) shift; apk info -a \"$1\" 2>/dev/null; rc=$? ;;\n" +
            "  rehash) rehash; rc=0 ;;\n" +
            "  version|--version) apk --version; rc=$? ;;\n" +
            "  help|-h|*) usage; rc=0 ;;\n" +
            "esac\n" +
            "exit $rc\n";
        writeShim(new File(shimsDir(c), "pkg"), sh);
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
