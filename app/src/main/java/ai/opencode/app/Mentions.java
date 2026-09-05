package ai.opencode.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P27 phase 4 — tappable file mentions. PURE static logic, JVM-pinned:
 * no Android imports, so P27Test can run every rule on the host.
 *
 * When the assistant mentions a file it (or a tool) actually touched, the
 * user can tap it and the app opens that file in the EXISTING Files
 * viewer (reuse, never rebuild). The rules that make that safe:
 *
 *   • detection runs on assistant text only, never inside FENCED code
 *     blocks (``` toggles fencing) — fenced code stays inert;
 *   • inline `code` spans ARE linkable (they are how models usually
 *     mention paths), bare relative paths too, and project-absolute
 *     paths;
 *   • the EXISTENCE gate is the app side: a candidate only becomes a
 *     link when it resolves to a real file under the serving directory
 *     (resolve() + caller's isFile check) — everything else stays plain
 *     text, so no dead taps;
 *   • URLs, markdown link targets, half-paths like "a/" and junk never
 *     survive the shape filter + existence gate.
 */
public final class Mentions {

    private Mentions() {}

    /** One mention candidate found in text. */
    public static final class Hit {
        /** The candidate as written — the markdown layer REUSES this field
         *  to carry the resolved absolute path back to the span. */
        public String path;
        public final int start;     // index into the ORIGINAL text
        public final int end;       // exclusive
        Hit(String path, int start, int end) {
            this.path = path; this.start = start; this.end = end;
        }
    }

    /** Characters a path token may contain (no whitespace, no quotes,
     *  no markdown punctuation that would trail into the token). */
    private static boolean pathChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '/' || c == '.' || c == '_' || c == '-'
                || c == '+' || c == '#';
    }

    /** Quick shape filter BEFORE the (caller-side) existence check. Pure. */
    public static boolean plausible(String tok) {
        if (tok == null) return false;
        int n = tok.length();
        if (n < 3 || n > 200) return false;
        if (tok.startsWith("/") && tok.indexOf('/', 1) < 0) return false; // "/x"
        String low = tok.toLowerCase(Locale.US);
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("ftp://")) return false;              // URLs
        boolean hasSep = false, hasName = false, endsSep = false;
        for (int i = 0; i < n; i++) {
            char c = tok.charAt(i);
            if (!pathChar(c)) return false;
            if (c == '/') { hasSep = true; endsSep = true; }
            else { hasName = hasName || (c != '.'); endsSep = false; }
        }
        if (!hasName) return false;                 // "/", "..", "./"
        if (endsSep) return false;                  // "src/" — a dir stub, not a file
        if (tok.startsWith("./")) return true;      // ./src/App.tsx — fine
        // bare tokens (not backticked) must look pathy: a separator, or an
        // extension at the tail (README.md) — single words stay text.
        if (!hasSep) {
            int dot = tok.lastIndexOf('.');
            return dot > 0 && dot < n - 1 && (n - 1 - dot) <= 6;
        }
        return true;
    }

    /**
     * Extract mention candidates from assistant text. Fenced code blocks
     * are skipped entirely; everything else (plain text and inline code)
     * yields candidates for backticked spans and path-shaped tokens.
     * Pure; the caller decides existence.
     */
    public static List<Hit> extract(String text) {
        List<Hit> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int n = text.length();
        boolean inFence = false;
        int i = 0;
        int lineStart = 0;
        while (i <= n) {
            // find end of this line
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? n : nl;
            String line = text.substring(lineStart, lineEnd);
            String trimmed = line.trim();

            if (inFence) {
                if (trimmed.startsWith("```")) inFence = false; // closing fence
            } else if (trimmed.startsWith("```")) {
                inFence = true;                                  // opening fence
            } else {
                scanLine(line, lineStart, out);
            }

            if (nl < 0) break;
            lineStart = nl + 1;
            i = lineStart;
        }
        return out;
    }

    /** One line: backticked spans first (exact ranges), then bare tokens. */
    private static void scanLine(String line, int base, List<Hit> out) {
        int n = line.length();
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (c == '`') {
                int close = line.indexOf('`', i + 1);
                if (close > i + 1) {
                    String inner = line.substring(i + 1, close).trim();
                    // single well-formed token inside backticks
                    if (!inner.isEmpty() && isOneToken(inner)
                            && plausible(inner)) {
                        // span covers the backticks' content — the renderer
                        // already styles inline code; we link its exact range
                        out.add(new Hit(inner, base + i + 1, base + close));
                    }
                    i = close + 1;
                    continue;
                }
                i++;
                continue;
            }
            if (pathChar(c) && c != '.') {
                int j = i;
                while (j < n && pathChar(line.charAt(j))) j++;
                // sentence punctuation is not path: "util.go." at the end
                // of a sentence must extract as "util.go" — trailing dots
                // and slashes are stripped before the shape filter
                while (j > i && (line.charAt(j - 1) == '.')) j--;
                String tok = line.substring(i, j);
                if (tok.contains("/") && !tok.startsWith(".")
                        && plausible(tok)) {
                    out.add(new Hit(tok, base + i, base + j));
                }
                i = Math.max(j, i + 1);
                continue;
            }
            i++;
        }
    }

    /** True when s has no whitespace/backticks (one clean token). */
    private static boolean isOneToken(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '`' || Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /**
     * Resolve a candidate against the serving directory. Accepts
     * project-relative ("src/App.tsx", "./src/App.tsx"), sandbox-absolute
     * paths (used as-is when they exist under root) — returns the absolute
     * path, or null when the candidate cannot live inside the project.
     * Pure string work; the caller does the isFile gate.
     */
    public static String resolve(String root, String candidate) {
        if (candidate == null || candidate.isEmpty()) return null;
        String c = candidate;
        while (c.startsWith("./")) c = c.substring(2);
        if (c.isEmpty() || c.equals(".") || c.equals("..")) return null;
        if (c.startsWith("/")) {
            // absolute: must already sit inside the project to be ours
            if (root == null) return null;
            String r = root.endsWith("/") ? root : root + "/";
            return c.startsWith(r) ? c : null;
        }
        if (root == null) return null;
        String r = root.endsWith("/") ? root : root + "/";
        if (c.contains("../")) return null;      // never climb out
        return r + c;
    }

    /**
     * Middle ellipsize that keeps the FILENAME tail visible: the head is
     * shortened first, the last {@code tail} chars (the name) always stay.
     * "very/long/source/…/KubernetesClientFactory.java" reads as a file,
     * never as a headless string. Pure.
     */
    public static String middleEllipsize(String path, int max) {
        if (path == null) return null;
        int n = path.length();
        if (max <= 0 || n <= max) return path;
        int slash = path.lastIndexOf('/');
        String tail = slash >= 0 ? path.substring(slash + 1) : path;
        if (tail.length() > max - 1) tail = tail.substring(0, Math.max(1, max - 1));
        // grow the tail backward to fill the budget with real path chars
        int tailStart = slash >= 0 ? slash + 1 : 0;
        int avail = max - tail.length() - 1;              // -1 for the "…"
        if (avail > 0 && tailStart > avail) {
            return path.substring(0, avail) + "…" + tail;
        }
        // path head too short to matter: just clamp
        return path.substring(0, Math.max(1, max - 1)) + "…";
    }

    /**
     * The file path a tool card touched (for the "open in Files" chip):
     * edit/write/read/patch cards carry it in the title (opencode state
     * title = the path for file tools). Returns the RAW candidate or null;
     * the caller runs the existence gate. Pure.
     */
    public static String toolFilePath(String tool, String title) {
        if (tool == null || title == null || title.isEmpty()) return null;
        String t = tool.toLowerCase(Locale.US);
        if (!"edit".equals(t) && !"write".equals(t) && !"read".equals(t)
                && !"patch".equals(t)) return null;
        String s = title.trim();
        // multi-file summaries carry a " + N more" tail — strip it first,
        // then the whole remaining title IS the path (paths may contain
        // spaces: "/proj/a b.txt + 2 more" → "/proj/a b.txt")
        int more = s.lastIndexOf(" + ");
        if (more > 0) {
            String tail = s.substring(more + 3);
            if (tail.matches("more( \\d+)?") || tail.matches("\\d+ more?")) {
                s = s.substring(0, more).trim();
            }
        }
        String cand = s.trim();
        while (cand.startsWith("./")) cand = cand.substring(2);
        // NOTE: plausible() is NOT used here — tool titles carry real paths
        // that may contain SPACES ("/proj/a b.txt"), which extraction's
        // shape filter rightly forbids. A lighter shape check + the
        // caller's file-exists gate is the safety net instead.
        if (cand.isEmpty() || cand.length() > 300) return null;
        boolean pathy = cand.contains("/") || cand.lastIndexOf('.') > 0;
        if (!pathy) return null;
        for (int i = 0; i < cand.length(); i++) {
            char ch = cand.charAt(i);
            if (ch == '`' || ch == '\n' || ch == '\t') return null;
        }
        return cand.startsWith("/") || cand.contains("/") ? cand
                : (cand.lastIndexOf('.') > 0 ? cand : null);
    }
}
