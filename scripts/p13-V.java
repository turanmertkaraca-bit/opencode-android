import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Compiles the APP's real TarGz.java + H.java and runs the extraction
 *  against the genuine debian:bookworm arm64 layer, twice:
 *   1) with a progress callback  2) with NULL callback (the P12 crash path)
 *  then compares the created symlink set against `tar -tvzf` ground truth. */
public class V {
    public static void main(String[] a) throws Exception {
        Path here = Paths.get("/home/z/my-project/p13-verify");
        Path appSrc = Paths.get(
                "/home/z/my-project/opencode-mobile-p1/app/src/main/java/ai/opencode/app/TarGz.java");
        Path out = here.resolve("out");
        out.toFile().mkdirs();

        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(null, null, null)) {
            List<File> sources = Arrays.asList(
                    appSrc.toFile(), here.resolve("H.java").toFile());
            Iterable<? extends JavaFileObject> units =
                    fm.getJavaFileObjectsFromFiles(sources);
            List<String> opts = Arrays.asList("-d", out.toString());
            boolean ok = jc.getTask(null, fm, null, opts, null, units).call();
            if (!ok) { System.out.println("COMPILE FAIL"); System.exit(2); }
            System.out.println("compile: OK");
        }

        File blob = here.resolve("debian-layer.tar.gz").toFile();

        // ---- run 1: with callback (normal path) ----
        System.out.println("== run 1: progress callback present ==");
        run(out, blob, here.resolve("rootfs1").toFile(), "live-cb");

        // ---- run 2: NULL callback — the exact path that NPE'd the P12 install
        System.out.println("== run 2: NULL callback (P12 crash reproduction) ==");
        run(out, blob, here.resolve("rootfs2").toFile(), "null-cb");

        // ---- ground truth comparison against tar ----
        System.out.println("== ground truth vs rootfs1 ==");
        compareSymlinks(here.resolve("rootfs1").toFile(), blob);
    }

    static void run(Path out, File blob, File dest, String mode) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("java", "-cp",
                out + ":/usr/lib/jvm/java-21-openjdk-amd64/lib", "H",
                blob.getAbsolutePath(), dest.getAbsolutePath(), mode);
        pb.environment().putIfAbsent("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream()));
        String l;
        while ((l = r.readLine()) != null) System.out.println("  " + l);
        p.waitFor();
        System.out.println("  exit=" + p.exitValue());
    }

    static void compareSymlinks(File rootfs, File blob) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("tar", "-tvzf", blob.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream()));
        int tarFiles = 0, tarLinks = 0, tarDirs = 0, longTargets = 0, mismatches = 0;
        String l;
        while ((l = r.readLine()) != null) {
            // drwxr-xr-x root/root  0 2024-03-10 01:23 name
            // lrwxrwxrwx root/root  0 ... name -> target
            if (l.startsWith("l")) {
                int ar = l.indexOf(" -> ");
                String left = l.substring(0, ar).trim();
                String name = left.substring(left.lastIndexOf(' ') + 1);
                String target = l.substring(ar + 4);
                tarLinks++;
                if (target.length() > 100) longTargets++;
                Path link = Paths.get(rootfs.getAbsolutePath(), name);
                if (!Files.isSymbolicLink(link)) {
                    mismatches++;
                    if (mismatches < 8) System.out.println("  MISSING-LINK " + name);
                    continue;
                }
                Path createdTgt = Files.readSymbolicLink(link);
                Path resolved = link.getParent().resolve(createdTgt).normalize();
                Path expected;
                if (target.startsWith("/"))
                    expected = Paths.get(rootfs.getAbsolutePath(), target.substring(1));
                else
                    expected = link.getParent().resolve(target).normalize();
                if (!Files.exists(link) || !resolved.equals(expected.normalize())) {
                    mismatches++;
                    if (mismatches < 8) System.out.println("  WRONG-TARGET " + name
                            + " created=" + createdTgt + " expected=" + target);
                }
            } else if (l.startsWith("-")) tarFiles++;
            else if (l.startsWith("d")) tarDirs++;
        }
        System.out.println("tar ground truth: files=" + tarFiles + " links="
                + tarLinks + " dirs=" + tarDirs + " (links>100chars=" + longTargets + ")");
        System.out.println("symlink comparison mismatches: " + mismatches
                + (mismatches == 0 ? "  — ALL LINKS CORRECT" : "  — MISMATCHES ABOVE"));
    }
}
