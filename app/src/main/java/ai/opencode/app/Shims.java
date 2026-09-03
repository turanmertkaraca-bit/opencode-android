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
import java.util.concurrent.TimeUnit;

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

    /**
     * Idempotent; called from Binaries.applyEnv before every spawn.
     *
     * P12 hardening — the "Bad system call" fix. The P9 wrappers ran core
     * commands (ls, cat, …) through the DYNAMIC musl loader, and on the
     * user's Android 16 device that dies with SIGSYS the moment `ls` runs
     * (their screenshot: `pwd && ls` → "Bad system call"). The bundled
     * busybox is musl-STATIC and has worked since P7, so every applet it
     * supports is now symlinked into bin/ (FIRST on PATH) and shadows the
     * dynamic wrappers. Core shell can no longer crash regardless of the
     * loader situation, and the Alpine layer stays for what apk installs.
     */
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

            // P12: static applets first on PATH — user imports still win
            installApplets(c, bb);

            String f = c.getFilesDir().getAbsolutePath();
            File realBash = new File(bin, "bash");
            writeBashShim(c, f);

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

    // ------------------------------------------------- P12 static applets

    /** Applets requested even when `busybox --list` is unavailable. */
    private static final String[] CORE_APPLETS = {
            "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "echo",
            "printf", "grep", "egrep", "fgrep", "sed", "awk", "find",
            "tar", "gzip", "gunzip", "zcat", "head", "tail", "wc",
            "touch", "chmod", "chown", "ln", "which", "uname", "env",
            "cut", "tr", "sort", "uniq", "date", "du", "df", "stat",
            "sleep", "basename", "dirname", "sha256sum", "md5sum", "wget",
            "od", "dd", "ps", "id", "whoami", "expr", "less", "more",
            "diff", "patch", "vi", "clear", "free", "netstat", "pgrep",
            "kill", "nice", "nohup", "seq", "yes", "true", "false",
            "xargs", "realpath", "readlink", "fold", "comm",
            "expand", "unexpand", "sum", "sync", "time", "timeout", "tty"};

    /**
     * Symlink every applet the bundled busybox supports into bin/.
     * The supported set comes from `busybox --list` (cached); falls back
     * to CORE_APPLETS. bin/ is first on PATH, so these shadow the
     * musl-dynamic wrappers — the SIGSYS killer — while user imports
     * (checked first) still win over everything.
     */
    private static void installApplets(Context c, File bb) {
        if (bb == null || !bb.isFile()) return;
        File bin = binDir(c);
        File flag = new File(bin, ".applets-p12");
        if (flag.exists()) return;              // one-time per install
        java.util.List<String> supported = busyboxList(c, bb);
        int made = 0;
        for (String a : (supported.isEmpty()
                ? java.util.Arrays.asList(CORE_APPLETS) : supported)) {
            if (a.equals("busybox") || a.equals("sh") || a.equals("ash")
                    || a.equals("bash") || a.equals("git") || a.equals("pkg"))
                continue;
            File dst = new File(bin, a);
            if (dst.exists()) continue;         // user import or existing link
            try {
                Os.symlink("busybox", dst.getAbsolutePath());
                dst.setExecutable(true, false);
                made++;
            } catch (Exception ignored) {}
        }
        try {
            File tmp = new File(bin, ".applets-p12.part");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            fo.write(("made " + made + "\n").getBytes("UTF-8"));
            fo.close();
            tmp.renameTo(flag);
        } catch (Exception ignored) {}
    }

    /** `busybox --list` output, cached under files/. Empty on any failure. */
    private static java.util.List<String> busyboxList(Context c, File bb) {
        File cache = new File(c.getFilesDir(), "busybox-applets.txt");
        if (cache.isFile()) {
            try {
                String[] lines = new String(read(cache), "UTF-8").split("\n");
                java.util.List<String> out = new java.util.ArrayList<>();
                for (String l : lines) if (!l.trim().isEmpty()) out.add(l.trim());
                return out;
            } catch (Exception ignored) {}
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(bb.getAbsolutePath(), "--list");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String s = Api.readAll(p.getInputStream());
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
                for (String l : s.split("\n"))
                    if (!l.trim().isEmpty()) out.add(l.trim());
                try (OutputStream o = new FileOutputStream(cache)) {
                    o.write(s.getBytes("UTF-8"));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // --------------------------------------------------- P12 bash routing

    /**
     * bash = the agent's main entry point. Priority:
     *   1. user-imported bash.real (pre-existing behavior)
     *   2. Debian 12 via proot (when installed + probed + switch on) —
     *      `apt`, real gcc, python, node all work; the project folder is
     *      bound at its real device path
     *   3. /system/bin/sh (mksh) as ever
     * The Debian launcher is regenerated on every spawn so a proxy-port
     * change is picked up without ceremony (write-if-different).
     */
    private static void writeBashShim(Context c, String f) {
        Debian.writeLauncher(c);
        File launcher = new File(Debian.dir(c), "launch");
        String bash =
                "#!/system/bin/sh\n" +
                "if [ -x \"" + f + "/bin/bash.real\" ]; then\n" +
                "  exec \"" + f + "/bin/bash.real\" \"$@\"\n" +
                "fi\n" +
                "if [ -x \"" + launcher.getAbsolutePath() + "\" ]\n" +
                "    && [ -f \"" + new File(Debian.dir(c), ".probe") + "\" ]; then\n" +
                "  exec \"" + launcher.getAbsolutePath() + "\" bash \"$@\"\n" +
                "fi\n" +
                "exec /system/bin/sh \"$@\"\n";
        writeShim(new File(shimsDir(c), "bash"), bash);
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
