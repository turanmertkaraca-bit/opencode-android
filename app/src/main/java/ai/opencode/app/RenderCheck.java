package ai.opencode.app;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P35 — the agent's eyes. The agent could write code and run it, but it
 * could not LOOK at a page it wrote: the sandbox has no browser, tool
 * results are text, and the canvas WebView was a one-way window. P35
 * closes the loop: the app serves a localhost render endpoint (the
 * browser the agent can use — RenderServer), an offscreen WebView renders
 * the page (HtmlRenderer), and the JSON report (console errors, DOM
 * outline, layout notes, an optional one-paragraph visual description
 * through the free vision ladder) travels back to the agent as plain
 * text it can read and act on. Write → render-check → fix → present.
 *
 * Everything decidable WITHOUT a device lives here, pure and JVM-pinned:
 * request validation (the path guard), the verdict rule, report shaping,
 * the caps, the per-session note texts, and the check-state the UI reads.
 * No AGENTS.md block — the P30 field lesson killed that channel (git
 * leaks + mid-session inertness); the workflow reaches the agent as a
 * <system-reminder> riding the next message after an .html write, and as
 * a clause appended to every canvas ask.
 */
public final class RenderCheck {

    private RenderCheck() {}

    /** RenderServer port ladder (loopback-only, app-owned). */
    public static final int PORT_BASE = 43110;
    public static final int PORT_SPAN = 7;

    /** Report caps: console lines, line length, remembered check states. */
    public static final int MAX_CONSOLE = 20;
    public static final int MAX_LINE = 500;
    public static final int STATE_CAP = 64;

    /** The instruction the visual-description model gets. */
    public static final String LOOK_PROMPT =
            "You are checking a web page a coding agent just wrote. Describe "
            + "what the page shows and how it looks: layout, visible text, "
            + "controls, styling, and anything that looks broken, empty or "
            + "unstyled. Be concrete and compact.";

    // ------------------------------------------------- request validation

    /** validate() outcome: ok with the resolved file, or an error code. */
    public static final class Outcome {
        public final boolean ok;
        public final String error;
        public final String detail;
        public final File file;
        Outcome(boolean ok, String error, String detail, File file) {
            this.ok = ok; this.error = error; this.detail = detail;
            this.file = file;
        }
    }

    private static Outcome bad(String code, String detail) {
        return new Outcome(false, code, detail, null);
    }

    /**
     * The path guard: relative, renderable, INSIDE the served project
     * (canonical — symlinks and .. cannot walk out), present, readable,
     * and inside the canvas size cap. Pure in decision; touches the
     * filesystem only to canonicalize and stat.
     */
    public static Outcome validate(File root, String rel) {
        if (root == null || !root.isDirectory())
            return bad("no-project", "no project is being served");
        if (rel == null || rel.trim().isEmpty())
            return bad("no-file", "the request carries no file");
        String p = rel.trim();
        if (p.indexOf('\u0000') >= 0) return bad("bad-path", "bad path");
        if (p.startsWith("/") || p.matches("^[A-Za-z]:[\\\\/].*"))
            return bad("absolute", "use a path relative to the project root");
        if (!CanvasDoc.isRenderable(p))
            return bad("not-html", "only .html / .htm pages can be rendered");
        File canonRoot, canonF;
        try {
            canonRoot = root.getCanonicalFile();
            canonF = new File(root, p).getCanonicalFile();
        } catch (Exception e) {
            return bad("bad-path", "path cannot be resolved");
        }
        String rp = canonRoot.getPath();
        String fp = canonF.getPath();
        if (!fp.startsWith(rp + File.separator))
            return bad("escape", "the page must live inside the project");
        if (!canonF.isFile()) return bad("missing", "that file does not exist");
        if (canonF.length() > CanvasDoc.MAX_BYTES)
            return bad("too-big", "the page caps at "
                    + (CanvasDoc.MAX_BYTES / 1024) + " kB");
        if (!canonF.canRead()) return bad("unreadable", "the page is not readable");
        return new Outcome(true, null, null, canonF);
    }

    // ------------------------------------------------- report shaping

    /**
     * The verdict rule: any [error] console line fails the check — the
     * agent must fix it before presenting. Warnings pass with the line
     * still visible in the report. Pure.
     */
    public static String verdict(List<String> console) {
        if (console != null) for (String c : console)
            if (c != null && c.startsWith("[error]")) return "fail";
        return "pass";
    }

    /** Cap a report list: at most MAX_CONSOLE lines, MAX_LINE chars each. */
    public static List<String> capLines(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (String s : in) {
            if (out.size() >= MAX_CONSOLE) break;
            out.add(capOne(s));
        }
        return out;
    }

    /** One line, null-safe, MAX_LINE-capped. */
    public static String capOne(String s) {
        if (s == null) return "";
        return s.length() > MAX_LINE ? s.substring(0, MAX_LINE) : s;
    }

    /** Append a report line, enforcing the console cap. */
    public static void addLine(List<String> to, String line) {
        if (to.size() < MAX_CONSOLE) to.add(capOne(line));
    }

    /**
     * The JSON response body. Pure — the suite asserts its shape and the
     * agent reads it as text.
     */
    public static Map<String, Object> report(String verdict, List<String> console,
            Map<String, Object> outline, List<String> notes, String look,
            boolean timedOut) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("verdict", verdict);
        m.put("console", console == null ? new ArrayList<String>() : console);
        m.put("outline", outline == null
                ? new LinkedHashMap<String, Object>() : outline);
        m.put("notes", notes == null ? new ArrayList<String>() : notes);
        if (look != null && !look.isEmpty()) m.put("look", capOne(look));
        if (timedOut) m.put("timedOut", true);
        return m;
    }

    /** Stable-key outline shaping: known keys survive, headings cap at 6. */
    public static Map<String, Object> shapeOutline(Map<String, Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String k : new String[]{"title", "elements", "buttons", "links",
                "inputs", "canvases", "svgs", "textLen", "scrollW", "scrollH",
                "viewportW", "viewportH", "overflowX"})
            if (raw.get(k) != null) m.put(k, raw.get(k));
        Object h = raw.get("headings");
        if (h instanceof List) {
            List<?> hs = (List<?>) h;
            List<Object> kept = new ArrayList<>();
            for (int i = 0; i < hs.size() && i < 6; i++)
                kept.add(capOne(String.valueOf(hs.get(i))));
            m.put("headings", kept);
        }
        return m;
    }

    // ------------------------------------------------- agent-facing text

    /**
     * The per-session capability note. It rides ONE message as a
     * <system-reminder> after the agent wrote an .html page (success-marked
     * exactly like the terse note). Null when the render endpoint is down —
     * never teach a door that does not open.
     */
    public static String note(int port, String token) {
        if (port <= 0 || token == null || token.isEmpty()) return null;
        return "<system-reminder>App capability (use when writing or editing "
                + "HTML pages): verify your page before calling it done. Run: "
                + "curl -s -X POST http://127.0.0.1:" + port + "/render "
                + "-H 'X-Render-Key: " + token + "' "
                + "-d '{\"file\":\"RELATIVE/path.html\",\"describe\":true}' "
                + "(from the project root). The JSON reply carries verdict "
                + "pass|fail, console errors, a DOM outline and a short "
                + "visual description of the page. Fix every console error "
                + "and re-run until the verdict is pass, then tell the user "
                + "the page is ready. Pages must be self-contained: inline "
                + "CSS/JS, no external resources — the checker refuses "
                + "network loads.</system-reminder>";
    }

    /**
     * The clause appended to every canvas ask: the verification loop,
     * spelled out for THIS page. Empty when the endpoint is down — the
     * ask then behaves exactly like P34's.
     */
    public static String renderClause(int port, String token) {
        if (port <= 0 || token == null || token.isEmpty()) return "";
        return " Before you stop: render-check the page — curl -s -X POST "
                + "http://127.0.0.1:" + port + "/render "
                + "-H 'X-Render-Key: " + token + "' "
                + "-d '{\"file\":\"" + CanvasDoc.FILE_NAME
                + "\",\"describe\":true}' — fix every console error it "
                + "reports and re-run until verdict is pass; only then tell "
                + "me it is ready.";
    }

    /**
     * The P30 lesson, applied: FALSE (an .html write was observed in this
     * session) → the next send carries the note once. TRUE → already
     * delivered. Absent → no HTML activity, no tokens spent. Pure.
     */
    public static boolean needsNote(Boolean told) {
        return Boolean.FALSE.equals(told);
    }

    // ------------------------------------------------- check state (UI)

    /**
     * Last check verdict per absolute page path — the quiet UI signal on
     * the ▶ interactive chips. In-memory by design (a process restart
     * clears it; the agent can always re-check). LRU-capped.
     */
    private static final LinkedHashMap<String, Boolean> STATE =
            new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Boolean> e) {
                    return size() > STATE_CAP;
                }
            };

    /** Record a check result (called by the render path). */
    public static synchronized void remember(String abs, boolean pass) {
        if (abs == null || abs.isEmpty()) return;
        STATE.put(abs, pass);
    }

    /** "pass" / "fail" / null. */
    public static synchronized String lastState(String abs) {
        Boolean b = abs == null ? null : STATE.get(abs);
        return b == null ? null : b.booleanValue() ? "pass" : "fail";
    }

    /** The quiet chip suffix: nothing, a green tick, or an issues nudge. */
    public static String chipSuffix(String abs) {
        String s = lastState(abs);
        if ("pass".equals(s)) return " · \u2713 checked";
        if ("fail".equals(s)) return " · \u26A0 issues";
        return "";
    }
}
