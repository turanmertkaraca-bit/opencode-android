import ai.opencode.app.TarGz;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Mirrors Debian.debLink but with JVM symlinks; counts everything. */
public class H {
    static int linksMade = 0, filesSeen = 0;
    static final Map<String, String> created = new HashMap<>(); // name -> target

    public static void main(String[] a) throws Exception {
        File blob = new File(a[0]);
        File dest = new File(a[1]);
        boolean nullCb = a.length > 2 && a[2].equals("null-cb");
        dest.mkdirs();
        TarGz.Progress cb = nullCb ? null : msg -> {
            if (msg.startsWith("extracted")) System.out.println("  " + msg);
        };
        try (InputStream in = new FileInputStream(blob)) {
            TarGz.extractAll(in, dest, true, cb, (dir, name, target, exec) -> {
                try {
                    File d = new File(dir, name);
                    if (d.getParentFile() != null) d.getParentFile().mkdirs();
                    if (d.exists()) d.delete();
                    String t = target;
                    if (t.startsWith("/")) {
                        int depth = 0;
                        for (int i = 0; i < name.length(); i++)
                            if (name.charAt(i) == '/') depth++;
                        StringBuilder b = new StringBuilder();
                        for (int i = 0; i < depth; i++) b.append("../");
                        b.append(t.substring(1));
                        t = b.toString();
                    }
                    Files.createSymbolicLink(d.toPath(), Paths.get(t));
                    linksMade++;
                    created.put(name, t);
                } catch (Exception e) { /* mirror app behaviour: swallow */ }
            });
        }
        System.out.println("RESULT files-extracted=" + filesSeen
                + " links-made=" + linksMade);

        // ---- critical-path checks (the things proot/apt/bash need) ----
        ok(new File(dest, "bin/bash"), "regular+exec", "bin/bash");
        ok(new File(dest, "bin/sh"), "resolvable", "bin/sh");
        ok(new File(dest, "usr/bin/apt"), "resolvable", "usr/bin/apt");
        ok(new File(dest, "lib64/ld-linux-aarch64.so.1"), "resolvable",
                "lib64/ld-linux-aarch64.so.1");
        ok(new File(dest, "usr/lib/aarch64-linux-gnu/libc.so.6"), "regular",
                "libc.so.6");
        ok(new File(dest, "var/lib/dpkg"), "dir", "var/lib/dpkg");
        ok(new File(dest, "etc/apt"), "dir", "etc/apt");
    }

    static void ok(File f, String kind, String what) {
        boolean pass = false;
        String detail = "";
        try {
            Path p = f.toPath().toRealPath();
            if (kind.equals("regular+exec")) {
                File rf = p.toFile();
                pass = rf.isFile() && rf.canExecute() && rf.length() > 0;
                detail = rf.length() + " B exec=" + rf.canExecute();
            } else if (kind.equals("resolvable")) {
                pass = Files.exists(f.toPath()) ;
                detail = "-> " + p;
            } else if (kind.equals("regular")) {
                pass = p.toFile().isFile() && p.toFile().length() > 0;
                detail = p.toFile().length() + " B";
            } else if (kind.equals("dir")) {
                pass = Files.isDirectory(f.toPath());
            }
        } catch (Exception e) { detail = e.toString(); }
        System.out.println((pass ? "PASS " : "FAIL ") + what
                + (pass ? " (" + detail + ")" : "  [" + detail + "]"));
    }
}
