package ai.opencode.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * P27 phase 2 — the CURATED rootfs. An agent doing agentic coding needs
 * ONLY: bash, coreutils, findutils, sed/grep/awk, tar/gzip, git, curl +
 * ca-certificates, and apt for on-demand installs. Everything else in the
 * stock debian:bookworm layer is trimmable weight on the device's storage.
 *
 * Measured inside the sandbox (~530 MB rootfs): /usr/share/doc 16.6 MB,
 * /usr/share/man 8.6 MB, zoneinfo 3.9 MB, perl ~25 MB, locale archives up
 * to 43 MB once generated. This class is the PURE DECISION LAYER (which
 * paths go, which zoneinfo stays) so the JVM suite pins every rule; the
 * actual deletion walk lives in Debian.curate().
 *
 * Guardrails:
 *   • apt must keep working (on-demand installs are the escape hatch) —
 *     dpkg/apt binaries and their libc are NEVER in the trim set;
 *   • git core (clone/add/commit/branch/log) works perl-less — only
 *     interactive `add -p` / `rebase -i` shell out to perl, agents don't;
 *   • the trim is one apt-visible removal set, logged to trim-report.txt,
 *     and can be disabled in Settings (curate_rootfs, default on).
 */
public final class DebianTrim {

    private DebianTrim() {}

    /** Zoneinfo files that survive the trim (a handful of real zones +
     *  UTC). Everything else under /usr/share/zoneinfo goes. */
    public static final Set<String> KEEP_ZONES = new HashSet<>(Arrays.asList(
            "UTC", "localtime", "Etc/UTC", "Etc/GMT", "Etc/GMT-0", "Etc/GMT+0",
            "America/New_York", "America/Chicago", "America/Denver",
            "America/Los_Angeles", "America/Sao_Paulo",
            "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Istanbul",
            "Europe/Moscow", "Asia/Dubai", "Asia/Kolkata", "Asia/Shanghai",
            "Asia/Tokyo", "Asia/Singapore", "Australia/Sydney"));

    /**
     * The trim decision for one path RELATIVE TO THE ROOTFS ROOT (leading
     * "/" optional, no trailing slash). Pure, order-independent, JVM-pinned:
     *   • /usr/share/doc and anything under it          → go (16.6 MB)
     *   • /usr/share/man and anything under it          → go (8.6 MB)
     *   • /usr/share/info and /usr/share/groff          → go (man machinery)
     *   • /usr/share/zoneinfo/X                         → go unless X is in KEEP_ZONES
     *   • /usr/lib/locale/locale-archive                → go (C.UTF-8 stays;
     *     the archive only appears once locales were generated — up to 43 MB)
     *   • perl: the executable(s), /usr/share/perl trees, /usr/lib/perl
     *     trees, and any libperl.so                     → go (~25 MB)
     * Everything else — especially apt, dpkg, /bin, libc, and
     * /usr/share/ca-certificates — STAYS.
     */
    public static boolean trimmed(String relPath) {
        if (relPath == null) return false;
        String p = relPath.startsWith("/") ? relPath.substring(1) : relPath;
        if (p.isEmpty()) return false;

        if (p.equals("usr/share/doc") || p.startsWith("usr/share/doc/")) return true;
        if (p.equals("usr/share/man") || p.startsWith("usr/share/man/")) return true;
        if (p.equals("usr/share/info") || p.startsWith("usr/share/info/")) return true;
        if (p.equals("usr/share/groff") || p.startsWith("usr/share/groff/")) return true;

        if (p.startsWith("usr/share/zoneinfo/")) {
            String zone = p.substring("usr/share/zoneinfo/".length());
            // whole legacy subdirs (right/, posix/, SystemV/) go — the keep
            // list addresses real zones directly
            if (zone.startsWith("posix/") || zone.startsWith("right/")
                    || zone.equals("posix") || zone.equals("right")
                    || zone.equals("SystemV")) return true;
            return !KEEP_ZONES.contains(zone);
        }
        // locale archives (up to 43 MB once locales were generated) — the
        // C.UTF-8 builtin stays; the archive is regenerable and agents run
        // fine with the default LANG
        if (p.equals("usr/lib/locale/locale-archive")
                || (p.startsWith("usr/lib/") && p.contains("/locale/locale-archive"))) {
            return true;
        }

        // perl — executable, modules, and the shared lib (any arch triplet)
        if (p.startsWith("usr/bin/perl")) return true;
        if (p.startsWith("usr/share/perl/") || p.startsWith("usr/share/perl5/")) return true;
        if (p.startsWith("usr/lib/perl/")) return true;
        if (p.startsWith("usr/lib/perl5/")) return true;
        if (p.startsWith("usr/lib/") && p.contains("/libperl.so")) return true;
        if (p.equals("usr/bin/js") || p.startsWith("usr/bin/js")) return false; // never ours

        return false;
    }

    /** True when a path is a DIRECTORY we should prune wholesale (the walk
     *  uses this to skip recursion into doomed subtrees fast). Pure. */
    public static boolean trimmedDir(String relPath) {
        if (relPath == null) return false;
        String p = relPath.startsWith("/") ? relPath.substring(1) : relPath;
        return p.equals("usr/share/doc") || p.equals("usr/share/man")
                || p.equals("usr/share/info") || p.equals("usr/share/groff")
                || p.equals("usr/share/perl") || p.equals("usr/share/perl5")
                || p.equals("usr/lib/perl") || p.equals("usr/lib/perl5")
                || p.startsWith("usr/lib/") && p.endsWith("/perl")
                || p.startsWith("usr/share/zoneinfo/posix")
                || p.startsWith("usr/share/zoneinfo/right");
    }

    // ------------------------------------------------- P27: boot hygiene

    /**
     * Boot-time hygiene — the ~108 MB that comes back EVERY session.
     * Measured in the field: /root/.npm/_cacache 93 MB after the first
     * npm-using run, /var/lib/apt/lists ~15 MB after any apt touch.
     * Both are pure caches: npm re-fetches on demand, apt re-downloads
     * indexes on the next `apt update`. Returned as the ROOTFS-RELATIVE
     * paths the Java-side cleaner removes (no guest shell needed — this
     * runs even before Debian's first probe). Pure.
     */
    public static String[] bootHygieneTargets() {
        return new String[]{
                "root/.npm",                 // 93 MB measured
                "root/.cache/node",          // node's fallback cache
                "var/lib/apt/lists/partial", // regenerated by apt update
                "var/lib/apt/lists",         // 15 MB measured (dir itself stays, emptied)
                "var/cache/apt/archives",    // downloaded .deb cache
        };
    }

    /**
     * /root scratch files from PREVIOUS sessions (probe logs, dump files)
     * — maxdepth-1 FILES only, matching these prefixes; directories are
     * NEVER touched (the seeded opencode-android clone lives there).
     * Pure; the walk lives in Debian.bootHygiene().
     */
    public static boolean scratchFile(String name) {
        if (name == null) return false;
        return name.startsWith("probe") && (name.endsWith(".log") || name.endsWith(".txt"))
                || name.endsWith(".log") && name.startsWith("oc-")
                || name.equals(".wget-hsts");
    }
}
