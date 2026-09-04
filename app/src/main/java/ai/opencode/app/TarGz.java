package ai.opencode.app;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Full-archive pure-Java tar extractor — the P3a fallback when busybox tar
 * fails on-device (e.g. chown-to-root errors on /data). Handles GNU tar,
 * POSIX ustar, long names (typeflag 'L'), pax path records ('x'), regular
 * files ('0'/'\0'), directories ('5'), and symlinks ('2').
 *
 * Android's Java API cannot create symlinks, so links are emulated:
 *  - /bin/busybox applet links → tiny "#!/bin/sh" shim scripts that exec
 *    the real busybox (bytes-cheap: 334 links ≈ 50 KB total);
 *  - /bin/sh and /bin/ash → REAL busybox copies (shebangs must hit a real
 *    executable);
 *  - everything else → copy of the link target's content.
 *
 * Fully streaming; paths are sanitized (no leading '/', no '..').
 */
public final class TarGz {

    private TarGz() {}

    public interface Progress { void on(String msg); }

    /**
     * P9: pluggable symlink policy. The P5 rootfs emulates links with
     * proot-style "#!/bin/sh" shims; the P9 Alpine layer needs
     * musl-loader wrapper scripts instead. Implementations receive the
     * archive-relative name, raw target, and exec bit.
     */
    public interface LinkHandler { void link(File destDir, String name, String target, boolean exec); }

    private static final class Link {
        final String name, target;
        Link(String n, String t) { name = n; target = t; }
    }

    /** Legacy entry point (P5 proot-style link emulation). */
    public static void extractAll(InputStream raw, File destDir, boolean gz, Progress cb)
            throws IOException {
        extractAll(raw, destDir, gz, cb, null);
    }

    public static void extractAll(InputStream raw, File destDir, boolean gz, Progress cb,
                                  LinkHandler handler)
            throws IOException {
        // P13: cb is now OPTIONAL — Debian's installer passes null (it logs
        // through its own channel). The NPE the user hit on the P12 install
        // ("TarGz$Progress.on on a null object reference") died exactly here.
        final Progress p = (cb == null) ? msg -> { } : cb;

        InputStream in = raw;
        if (gz) in = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16));
        else in = new BufferedInputStream(raw, 1 << 16);

        List<Link> links = new ArrayList<>();
        List<String> execFiles = new ArrayList<>();
        int files = 0;
        byte[] hdr = new byte[512];
        // P13: GNU long-link ('K') and pax "linkpath=" carry the target of
        // the NEXT entry (Debian rootfs has symlink targets > 100 chars,
        // e.g. /usr/share/doc alternates) — without this the link lands
        // with a truncated/empty target and the guest tooling breaks.
        String pendingLinkTarget = null;

        try {
            while (readFully(in, hdr)) {
                if (isZero(hdr)) break;
                int type = hdr[156] & 0xFF;
                long size = parseSize(hdr, 124, 12);
                String name = norm(cstr(hdr, 0, 100));
                int mode = (int) parseSize(hdr, 100, 8);
                boolean regular = (type == '0' || type == 0);

                if (type == 'K') {
                    // P13: GNU long link target for the NEXT entry.
                    if (size >= 1 && size <= 65536) {
                        byte[] lb = new byte[(int) size];
                        if (!readFully(in, lb)) break;
                        pendingLinkTarget = cstr(lb, 0, lb.length);
                    }
                    skipPad(in, size);
                    continue;
                }
                if (type == 'L') {
                    if (size < 1 || size > 65536) throw new IOException("bad longname size " + size);
                    byte[] nb = new byte[(int) size];
                    if (!readFully(in, nb)) break;
                    name = norm(cstr(nb, 0, nb.length));
                    skipPad(in, size);
                    if (!readFully(in, hdr) || isZero(hdr)) break;
                    type = hdr[156] & 0xFF;
                    size = parseSize(hdr, 124, 12);
                    regular = (type == '0' || type == 0);
                } else if (type == 'x') {
                    if (size >= 1 && size <= (1 << 20)) {
                        byte[] pb = new byte[(int) size];
                        if (!readFully(in, pb)) break;
                        String path = paxField(pb, "path");
                        String lp = paxField(pb, "linkpath");
                        if (lp != null) pendingLinkTarget = lp;
                        skipPad(in, size);
                        if (!readFully(in, hdr) || isZero(hdr)) break;
                        type = hdr[156] & 0xFF;
                        size = parseSize(hdr, 124, 12);
                        if (path != null) name = norm(path);
                        regular = (type == '0' || type == 0);
                    } else {
                        skipFully(in, size);
                        skipPad(in, size);
                        continue;
                    }
                } else if (hdr[257] == 'u' && hdr[262] == 0 && hdr[263] == '0') {
                    String prefix = norm(cstr(hdr, 345, 155));
                    if (prefix.length() > 0 && name.length() > 0) name = prefix + "/" + name;
                }

                if (name.isEmpty() || name.equals(".")) {
                    skipFully(in, size);
                    skipPad(in, size);
                    continue;
                }

                if (regular) {
                    File out = new File(destDir, name);
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream o = new FileOutputStream(out)) {
                        copyN(in, o, size);
                    }
                    files++;
                    if ((mode & 0111) != 0) execFiles.add(name);
                } else if (type == '5') {
                    new File(destDir, name).mkdirs();
                } else if (type == '2') {
                    String target = pendingLinkTarget != null
                            ? pendingLinkTarget : cstr(hdr, 157, 100);
                    pendingLinkTarget = null;
                    links.add(new Link(name, target));
                } else if (type == '1' && handler != null) {
                    // P9: hardlinks appear in some Alpine packages. Same
                    // treatment as a symlink to a regular file.
                    String target = pendingLinkTarget != null
                            ? pendingLinkTarget : cstr(hdr, 157, 100);
                    pendingLinkTarget = null;
                    // P22: hardlink targets are ARCHIVE-ROOT-relative (POSIX;
                    // GNU tar writes them without a leading slash), while
                    // symlink targets are LINK-DIR-relative. The real
                    // debian:bookworm arm64 layer carries usr/bin/perl5.36.0
                    // -> usr/bin/perl and usr/bin/uncompress -> usr/bin/gunzip;
                    // without this normalization both landed as
                    // <rootfs>/usr/bin/usr/bin/<name> — dangling — because
                    // the handlers resolve relative targets against the
                    // link's own directory. Absolute-in-rootfs goes through
                    // the handlers' existing relFromRoot rewrite instead.
                    if (!target.startsWith("/")) target = "/" + target;
                    handler.link(destDir, name, target, (mode & 0111) != 0);
                } else if (type != 'x' && type != 'K' && type != 'L') {
                    pendingLinkTarget = null;               // stale guard
                }
                // types '3','4','6' (dev nodes), 'g': not present
                // in the Alpine minirootfs — skip data if any size attached.
                skipPad(in, size);
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }

        p.on("resolving " + links.size() + " symlinks…");

        if (handler != null) {
            // P9: custom policy (Alpine musl-loader wrappers).
            int made = 0;
            for (Link l : links) {
                if (made++ % 60 == 0) p.on("linking… " + made + "/" + links.size());
                handler.link(destDir, l.name, l.target, false);
            }
            finish(destDir, links, execFiles, p, files);
            return;
        }

        // Real copies first: shebangs must land on genuine executables.
        copyWithin(destDir, "bin/busybox", "bin/sh");
        copyWithin(destDir, "bin/busybox", "bin/ash");

        int made = 0;
        for (Link l : links) {
            if (made++ % 40 == 0) p.on("resolving symlinks… " + made + "/" + links.size());
            resolveLink(destDir, l.name, l.target);
        }
        finish(destDir, links, execFiles, p, files);
    }

    private static void finish(File destDir, List<Link> links, List<String> execFiles,
                               Progress cb, int files) {

        for (String e : execFiles) {
            File f = new File(destDir, e);
            f.setExecutable(true, false);
        }
        // applet shim scripts must be executable too (they were written after
        // execFiles was collected)
        for (Link l : links) {
            File f = new File(destDir, l.name);
            if (f.exists() && f.isFile()) f.setExecutable(true, false);
        }

        cb.on("extracted " + files + " files, " + links.size() + " links");
    }

    /** Pull one "key=" record out of a pax extended-header block.
     *  P22: record-EXACT parse — each "<len> <key>=<value>\n" record is
     *  split and the key compared WHOLE. The old substring search for
     *  "path=" also matched INSIDE "linkpath=", so a header ordered
     *  <linkpath> before <path> returned the LINK TARGET as the member
     *  path and extracted the file under the wrong name. Go's archive/tar
     *  (what docker layers use) emits pax records in map-iteration order,
     *  so both orderings are legal in the wild. */
    private static String paxField(byte[] rec, String key) {
        String s = new String(rec);
        int pos = 0;
        while (pos < s.length()) {
            int nl = s.indexOf('\n', pos);
            if (nl < 0) nl = s.length();
            String r = s.substring(pos, nl);
            pos = nl + 1;
            int sp = r.indexOf(' ');
            int eq = r.indexOf('=', sp + 1);
            if (sp <= 0 || eq < 0) continue;
            if (r.substring(sp + 1, eq).equals(key)) return r.substring(eq + 1);
        }
        return null;
    }

    // ------------------------------------------------------------- links

    private static void resolveLink(File destDir, String name, String target) {
        try {
            if (target.equals("/bin/busybox")) {
                // busybox applet: tiny script, ~50 bytes instead of 1.1 MB copy
                String applet = new File(name).getName();
                writeShim(destDir, name, applet);
                return;
            }
            String resolved = target.startsWith("/")
                    ? norm(target.substring(1))
                    : norm(rel(name, target));
            File src = new File(destDir, resolved);
            if (src.isDirectory()) {
                new File(destDir, name).mkdirs();
            } else if (src.isFile() && src.length() <= 12 * 1024 * 1024) {
                File dst = new File(destDir, name);
                dst.getParentFile().mkdirs();
                FileInputStream in = new FileInputStream(src);
                try (FileOutputStream o = new FileOutputStream(dst)) {
                    byte[] b = new byte[1 << 16];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                } finally {
                    in.close();
                }
                dst.setExecutable(true, false);
            }
            // missing target: skip silently (guest tooling will not use it)
        } catch (Exception ignored) {}
    }

    private static void writeShim(File destDir, String name, String applet) {
        try {
            File f = new File(destDir, name);
            f.getParentFile().mkdirs();
            FileOutputStream o = new FileOutputStream(f);
            o.write(("#!/bin/sh\nexec /bin/busybox " + applet + " \"$@\"\n")
                    .getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) {}
    }

    private static void copyWithin(File destDir, String from, String to) {
        try {
            File src = new File(destDir, from);
            File dst = new File(destDir, to);
            dst.getParentFile().mkdirs();
            FileInputStream in = new FileInputStream(src);
            try (FileOutputStream o = new FileOutputStream(dst)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            } finally {
                in.close();
            }
            dst.setExecutable(true, false);
        } catch (Exception ignored) {}
    }

    /** Resolve a relative link target against the link's directory. */
    static String rel(String name, String target) {
        int slash = name.lastIndexOf('/');
        String dir = slash >= 0 ? name.substring(0, slash + 1) : "";
        return dir + target;
    }

    /** Normalize: strip leading './', collapse 'x/../y', reject escapes. */
    static String norm(String p) {
        if (p == null) return "";
        String[] parts = p.split("/");
        java.util.Deque<String> st = new java.util.ArrayDeque<>();
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!st.isEmpty()) st.removeLast();
                continue;
            }
            st.addLast(s);
        }
        StringBuilder b = new StringBuilder();
        for (String s : st) {
            if (b.length() > 0) b.append('/');
            b.append(s);
        }
        return b.toString();
    }

    // ---------------------------------------------------------- tar core

    private static boolean isZero(byte[] b) {
        for (int k = 0; k < 512; k++) if (b[k] != 0) return false;
        return true;
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int got = 0, n;
        while (got < b.length && (n = in.read(b, got, b.length - got)) != -1) got += n;
        return got == b.length;
    }

    static long parseSize(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7F;
            for (int k = 1; k < len; k++) v = (v << 8) | (b[off + k] & 0xFF);
            return v;
        }
        long v = 0;
        for (int k = 0; k < len; k++) {
            byte c = b[off + k];
            if (c == 0 || c == ' ') {
                if (v > 0) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            v = (v << 3) | (c - '0');
        }
        return v;
    }

    private static String cstr(byte[] b, int off, int maxLen) {
        int end = off;
        int stop = off + maxLen;
        while (end < stop && b[end] != 0) end++;
        return new String(b, off, end - off);
    }

    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) skipFully(in, pad);
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() == -1) return;
                s = 1;
            }
            left -= s;
        }
    }

    private static void copyN(InputStream in, FileOutputStream out, long n) throws IOException {
        byte[] b = new byte[1 << 16];
        long left = n;
        while (left > 0) {
            int want = (int) Math.min(b.length, left);
            int got = in.read(b, 0, want);
            if (got == -1) throw new IOException("unexpected EOF in entry data");
            out.write(b, 0, got);
            left -= got;
        }
    }
}
