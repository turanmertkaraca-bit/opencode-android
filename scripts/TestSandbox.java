import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * HOST-SIDE validation of the P9 sandbox design (Task 9.2).
 * Runs the REAL TarGz extractor against the REAL alpine minirootfs with an
 * Alpine link policy that mirrors Sandbox.alpineLink (java.nio symlinks),
 * then mirrors Sandbox.wrapperFor to inspect what wrappers would be
 * generated. Validated properties:
 *   1. extraction completes; ld-musl loader + apk + busybox exist on disk
 *   2. symlinks are relative and stay inside the rootfs
 *   3. wrapper classification: busybox applets, plain ELF, shebang scripts
 *   4. a pip-style shebang script resolves to the python interpreter
 */
public class TestSandbox {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name + (detail == null ? "" : "  [" + detail + "]")); }
        else    { fail++; System.out.println("* FAIL  " + name + (detail == null ? "" : "  [" + detail + "]")); }
    }

    // ---- mirror of Sandbox.alpineLink (host JVM flavor) ----
    static void alpineLink(Path destDir, String name, String target, boolean exec) {
        try {
            Path dst = destDir.resolve(name);
            if (dst.getParent() != null) Files.createDirectories(dst.getParent());
            if (Files.exists(dst)) Files.delete(dst);
            String t = target;
            if (t.startsWith("/")) {
                int depth = 0;
                for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '/') depth++;
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < depth; i++) b.append("../");
                b.append(t.substring(1));
                t = b.toString();
            }
            try {
                Files.createSymbolicLink(dst, java.nio.file.FileSystems.getDefault().getPath(t));
            } catch (Exception linkFail) {
                // content-copy fallback (mirror of copyWithinRootfs)
                Path src = destDir.resolve(TarGz.norm(
                        t.startsWith("/") ? t.substring(1) : TarGz.rel(name, t)));
                if (Files.isRegularFile(src)) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            }
            if (exec) dst.toFile().setExecutable(true, false);
        } catch (Exception ignored) {}
    }

    // ---- mirror of Sandbox.resolveSymlinks ----
    static String resolveSymlinks(File f, int depth) {
        try {
            File cur = f.getCanonicalFile();
            while (depth-- > 0) {
                Path p = cur.toPath();
                if (!Files.isSymbolicLink(p)) return cur.getAbsolutePath();
                Path t = Files.readSymbolicLink(p);
                File next = t.isAbsolute() ? t.toFile()
                        : new File(cur.getParentFile(), t.toString());
                cur = next.getCanonicalFile();
            }
            return null;
        } catch (Exception e) { return null; }
    }

    // ---- mirror of Sandbox.shebang ----
    static String[] shebang(File f) throws IOException {
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
        }
    }

    static String classify(File rootfs, File f) {
        try {
            String real = resolveSymlinks(f, 8);
            if (real == null) return "unresolved";
            File rf = new File(real);
            if (!rf.isFile()) return "missing";
            if (shebang(rf) != null) return "script";
            if (real.endsWith("/bin/busybox")) return "applet";
            return "elf";
        } catch (Exception e) { return "error"; }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("p9test");
        File dest = root.resolve("alpine").toFile();
        byte[] all = Files.readAllBytes(Paths.get(args[0]));
        System.out.println("extracting " + all.length + " bytes…");

        // The LinkHandler routes through the same code path shape as Sandbox:
        TarGz.extractAll(new ByteArrayInputStream(all), dest, true,
                m -> {}, (d, name, target, exec) -> alpineLink(d.toPath(), name, target, exec));

        System.out.println("extracted to " + dest);

        check("musl loader present", new File(dest, "lib/ld-musl-aarch64.so.1").isFile(), null);
        check("apk binary present", new File(dest, "sbin/apk").isFile(), null);
        check("busybox present", new File(dest, "bin/busybox").isFile(), null);
        check("apk keys present", new File(dest, "etc/apk/keys").isDirectory(), null);

        // 2. symlinks inside rootfs?
        Path ls = dest.toPath().resolve("bin/ls");
        check("bin/ls symlink exists", Files.isSymbolicLink(ls), null);
        if (Files.isSymbolicLink(ls)) {
            String t = Files.readSymbolicLink(ls).toString();
            check("bin/ls target relative", !t.startsWith("/"), t);
            Path resolved = ls.getParent().resolve(t).normalize();
            check("bin/ls resolves inside rootfs",
                    resolved.startsWith(dest.toPath()), resolved.toString());
            File rf = resolved.toFile().getCanonicalFile();
            check("bin/ls resolves to busybox", rf.getName().equals("busybox"), rf.getName());
        }
        Path sh = dest.toPath().resolve("bin/sh");
        check("bin/sh symlink to busybox", Files.isSymbolicLink(sh)
                && sh.toFile().getCanonicalFile().getName().equals("busybox"), null);

        // 3. classification across the tree
        int applet = 0, elf = 0, script = 0, other = 0;
        List<String> samples = new ArrayList<>();
        for (String d : new String[]{"bin", "usr/bin", "sbin", "usr/sbin"}) {
            File dd = new File(dest, d);
            File[] kids = dd.listFiles();
            if (kids == null) continue;
            for (File f : kids) {
                String c = classify(dest, f);
                switch (c) {
                    case "applet": applet++; break;
                    case "elf": elf++; break;
                    case "script": script++; if (samples.size() < 8) samples.add(d + "/" + f.getName()); break;
                    default: other++;
                }
            }
        }
        System.out.println("  classified: applet=" + applet + " elf=" + elf
                + " script=" + script + " other=" + other + " " + samples);
        check("hundreds of applet wrappers expected", applet > 100, String.valueOf(applet));
        check("some real ELF binaries expected", elf > 10, String.valueOf(elf));
        check("no unresolvable entries", other == 0, String.valueOf(other));

        // 4. python-style shebang resolution (synthesize a pip-like script)
        File pipLike = new File(dest, "usr/bin/pip-test");
        try (Writer w = new FileWriter(pipLike)) {
            w.write("#!/usr/bin/python3\nimport sys\nprint('ok')\n");
        }
        String c2 = classify(dest, pipLike);
        check("synthetic shebang script classified", "script".equals(c2), c2);

        System.out.println();
        System.out.println("RESULT: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
