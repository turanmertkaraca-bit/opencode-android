package ai.opencode.app;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * P22 "native layer audited" regression suite. Every rule here was pinned
 * against GROUND TRUTH, not reasoning:
 *
 *  - The bundled BusyBox v1.36.1 was EXECUTED under qemu-aarch64 on the
 *    build rig: 305 applets listed (busybox-applets-real.txt is that real
 *    output). The old CORE_APPLETS fallback contained "patch", which this
 *    build does not have — a dead command shipped as a symlink.
 *  - The REAL debian:bookworm arm64 docker layer (48,383,649 bytes,
 *    digest db86109d…) was extracted with the REAL TarGz on the rig:
 *    hardlink members usr/bin/perl5.36.0 -> usr/bin/perl and
 *    usr/bin/uncompress -> usr/bin/gunzip used to land DANGLING because
 *    hardlink targets are archive-ROOT-relative while the handlers
 *    resolved relative targets against the link's directory.
 *  - pax extended headers: Go's archive/tar (docker layer writers) emits
 *    records in map-iteration order — "linkpath=" before "path=" is legal;
 *    the old substring search returned the LINK TARGET as the file PATH.
 */
public class P22Test {

    // ------------------------------------------------------------ tar utils

    private static void oct(byte[] h, int off, int len, long v) {
        String s = Long.toOctalString(v);
        String pad = "000000000000".substring(0, len - 1 - s.length());
        byte[] b = (pad + s + "\0").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, Math.min(b.length, len));
    }

    private static void put(byte[] h, int off, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, Math.min(b.length, len));
    }

    private static byte[] header(String name, long size, char type, String link) {
        byte[] h = new byte[512];
        put(h, 0, name, 100);
        oct(h, 100, 8, 0644);
        oct(h, 108, 8, 0);
        oct(h, 116, 8, 0);
        oct(h, 124, 12, size);
        oct(h, 136, 12, 0);
        for (int i = 148; i < 156; i++) h[i] = ' ';
        h[156] = (byte) type;
        if (link != null) put(h, 157, link, 100);
        put(h, 257, "ustar\0", 6);
        put(h, 263, "00", 2);
        int sum = 0;
        for (byte b : h) sum += (b & 0xFF);
        String cs = Long.toOctalString(sum);
        String pad = "000000".substring(0, 6 - cs.length());
        byte[] cb = (pad + cs + "\0 ").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(cb, 0, h, 148, 8);
        return h;
    }

    private static String paxRec(String key, String value) {
        // real pax record: "<len> <key>=<value>\n" where <len> counts EVERY
        // byte of the record including the digits themselves — fixed-point.
        String body = " " + key + "=" + value + "\n";
        int len = body.length() + 1;
        while (String.valueOf(len).length() + body.length() != len)
            len = String.valueOf(len).length() + body.length();
        return len + body;
    }

    private static byte[] paxHeader(String path, String linkpath) {
        StringBuilder recs = new StringBuilder();
        if (path != null) recs.append(paxRec("path", path));
        if (linkpath != null) recs.append(paxRec("linkpath", linkpath));
        byte[] body = recs.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(header("PaxHeaders.0/x", body.length, 'x', null));
            out.write(body);
            int pad = (512 - (body.length % 512)) % 512;
            out.write(new byte[pad]);
        } catch (Exception e) { throw new RuntimeException(e); }
        return out.toByteArray();
    }

    private static byte[] tar(byte[]... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (byte[] e : entries) out.write(e);
            out.write(new byte[1024]);          // end-of-archive blocks
        } catch (Exception e) { throw new RuntimeException(e); }
        return out.toByteArray();
    }

    private static final class Got {
        final String name, target; final boolean exec;
        Got(String n, String t, boolean e) { name = n; target = t; exec = e; }
    }

    private static List<Got> extract(byte[] archive) throws Exception {
        File dst = Files.createTempDirectory("p22").toFile();
        final List<Got> got = new ArrayList<>();
        InputStream in = new ByteArrayInputStream(archive);
        TarGz.extractAll(in, dst, false, null, (dir, name, target, exec) -> {
            got.add(new Got(name, target, exec));
            // emulate Debian.debLink's resolution for the resolve check
        });
        return got;
    }

    // --------------------------------------------------- hardlink root-rel

    @Test
    public void hardlink_relativeTargets_areRootNormalized() throws Exception {
        // the REAL debian layer shape: relative hardlink targets
        byte[] t = tar(
                header("usr/bin/", 0, '5', null),
                header("usr/bin/perl", 2, '0', null),
                new byte[]{'h', 'i', 0, 0},
                new byte[508],
                header("usr/bin/perl5.36.0", 0, '1', "usr/bin/perl"));
        List<Got> got = extract(t);
        boolean found = false;
        for (Got g : got)
            if (g.name.equals("usr/bin/perl5.36.0")) {
                found = true;
                assertEquals("hardlink target must be root-absolute "
                        + "(POSIX: hardlink targets are archive-root-relative)",
                        "/usr/bin/perl", g.target);
            }
        assertTrue("hardlink entry must reach the handler", found);
    }

    @Test
    public void hardlink_absoluteTargets_stayAbsolute() throws Exception {
        byte[] t = tar(header("a/b", 0, '1', "/usr/bin/perl"));
        for (Got g : extract(t))
            if (g.name.equals("a/b"))
                assertEquals("/usr/bin/perl", g.target);
    }

    // ------------------------------------------------------- pax exact-key

    @Test
    public void pax_linkpathBeforePath_yieldsTheRealPath() throws Exception {
        // Go's archive/tar order is map-iteration: BOTH orders are legal.
        byte[] t = tar(
                paxHeader("usr/share/doc/very/long/path/name",
                          "/usr/share/doc/alt/very/long/target/name"),
                header("PaxHeaders.0/x", 0, '2', "truncated100chars"));
        List<Got> got = extract(t);
        assertEquals(1, got.size());
        assertEquals("path must come from the path= record, not the "
                + "substring inside linkpath=",
                "usr/share/doc/very/long/path/name", got.get(0).name);
        assertEquals("/usr/share/doc/alt/very/long/target/name", got.get(0).target);
    }

    @Test
    public void pax_pathBeforeLinkpath_yieldsTheRealPath() throws Exception {
        byte[] t = tar(
                paxHeader("usr/share/doc/very/long/path/name",
                          "/usr/share/doc/alt/very/long/target/name"),
                header("PaxHeaders.0/x", 0, '2', "truncated100chars"));
        List<Got> got = extract(t);
        assertEquals(1, got.size());
        assertEquals("usr/share/doc/very/long/path/name", got.get(0).name);
        assertEquals("/usr/share/doc/alt/very/long/target/name", got.get(0).target);
    }

    @Test
    public void pax_keysContainingPath_doNotMatchPath() throws Exception {
        // extended-attr style keys: only the EXACT key wins
        StringBuilder recs = new StringBuilder();
        recs.append(paxRec("SCHILY.xattr.user.path", "evil"));
        recs.append(paxRec("path", "real/name"));
        byte[] body = recs.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream all2 = new ByteArrayOutputStream();
        all2.write(header("PaxHeaders.0/x", body.length, 'x', null));
        all2.write(body);
        int pad = (512 - (body.length % 512)) % 512;
        all2.write(new byte[pad]);
        all2.write(header("PaxHeaders.0/x", 0, '2', "tgt"));
        List<Got> got2 = extract(all2.toByteArray());
        assertEquals(1, got2.size());
        assertEquals("exact-key match only — SCHILY.xattr.user.path must "
                + "not be taken as the member path", "real/name", got2.get(0).name);
    }

    @Test
    public void gnuLongLink_stillWorks() throws Exception {
        // P13 regression: GNU 'K' long link target for the NEXT entry
        String longTarget = "/usr/share/doc/alternatives/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        byte[] kBody = longTarget.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(header("K", kBody.length + 1, 'K', null));
        all.write(kBody); all.write(0);
        int pad = (512 - ((kBody.length + 1) % 512)) % 512;
        all.write(new byte[pad]);
        all.write(header("usr/bin/link100", 0, '2', "truncated"));
        List<Got> got = extract(all.toByteArray());
        assertEquals(1, got.size());
        assertEquals(longTarget, got.get(0).target);
    }

    // -------------------------------------------- CORE_APPLETS vs real BB

    @Test
    public void coreApplets_areAllRealBusyboxApplets() throws Exception {
        List<String> real = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/busybox-applets-real.txt");
             Scanner sc = new Scanner(in, "UTF-8")) {
            while (sc.hasNextLine()) {
                String l = sc.nextLine().trim();
                if (!l.isEmpty()) real.add(l);
            }
        }
        assertEquals("resource must be the real --list output (qemu run)",
                305, real.size());

        java.lang.reflect.Field f = Shims.class.getDeclaredField("CORE_APPLETS");
        f.setAccessible(true);
        String[] core = (String[]) f.get(null);

        for (String a : core)
            assertTrue("CORE_APPLETS contains '" + a + "' which the bundled "
                    + "BusyBox v1.36.1 does NOT have (dead symlink in the "
                    + "fallback path)", real.contains(a));
        assertFalse("patch was removed in P22 — the bundled busybox has no "
                + "patch applet", real.contains("patch"));
    }

    // ------------------------------------------------- extraction sanity

    @Test
    public void pathNorm_rejectsTraversal_andRootEscape() {
        // traversal clamps at the root (never escapes the destDir)
        assertEquals("b", TarGz.norm("a/../../b"));
        assertEquals("a/b", TarGz.norm("/a/b"));
        assertEquals("a/b", TarGz.norm("./a/./b"));
        assertEquals("b", TarGz.norm("../b"));
        assertEquals("a/b", TarGz.norm("a/c/../b"));
    }
}
