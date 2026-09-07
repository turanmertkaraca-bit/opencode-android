package ai.opencode.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P35 — the agent's eyes, pinned: the path guard (the render endpoint
 * must never serve a byte outside the project), the verdict rule, the
 * report caps and shape, the agent-facing note/clause texts, the
 * per-session note arming rule, and the check-state the UI chips read.
 * Pure logic only — no device, no WebView.
 */
public class P35Test {

    // ------------------------------------------------- the path guard

    private static File project() throws Exception {
        File root = Files.createTempDirectory("p35proj").toFile();
        File sub = new File(root, "pages");
        sub.mkdirs();
        write(new File(sub, "page.html"), "<html><body>hi</body></html>");
        write(new File(root, "canvas.html"), "<html><body>canvas</body></html>");
        write(new File(root, "notes.txt"), "not a page");
        return root;
    }

    private static void write(File f, String s) throws Exception {
        FileWriter w = new FileWriter(f);
        w.write(s);
        w.close();
    }

    @Test public void validate_ok_relative_and_nested() throws Exception {
        File root = project();
        RenderCheck.Outcome a = RenderCheck.validate(root, "canvas.html");
        assertTrue(a.ok);
        assertEquals("canvas.html", a.file.getName());
        RenderCheck.Outcome b = RenderCheck.validate(root, "pages/page.html");
        assertTrue(b.ok);
    }

    @Test public void validate_rejects_the_classics() throws Exception {
        File root = project();
        assertEquals("no-file", RenderCheck.validate(root, "   ").error);
        assertEquals("no-file", RenderCheck.validate(root, null).error);
        assertEquals("absolute",
                RenderCheck.validate(root, "/etc/passwd.html").error);
        assertEquals("not-html", RenderCheck.validate(root, "notes.txt").error);
        assertEquals("missing",
                RenderCheck.validate(root, "gone.html").error);
        assertEquals("no-project", RenderCheck.validate(null, "x.html").error);
    }

    @Test public void validate_rejects_traversal_escape() throws Exception {
        File root = project();
        File outside = new File(root.getParent(), "evil.html");
        write(outside, "<html></html>");
        try {
            assertEquals("escape",
                    RenderCheck.validate(root, "../evil.html").error);
            assertEquals("escape",
                    RenderCheck.validate(root, "pages/../../evil.html").error);
        } finally {
            outside.delete();
        }
    }

    @Test public void validate_rejects_oversized_pages() throws Exception {
        File root = project();
        File big = new File(root, "big.html");
        FileWriter w = new FileWriter(big);
        char[] chunk = new char[1024];
        java.util.Arrays.fill(chunk, 'x');
        for (int i = 0; i < (3 * 1024 * 1024 / 1024) + 2; i++) w.write(chunk);
        w.close();
        try {
            assertEquals("too-big", RenderCheck.validate(root, "big.html").error);
        } finally {
            big.delete();
        }
    }

    // ------------------------------------------------- verdict + caps

    @Test public void verdict_any_console_error_fails() {
        List<String> warnOnly = new ArrayList<>();
        warnOnly.add("[warn] deprecated API (line 12)");
        assertEquals("pass", RenderCheck.verdict(warnOnly));
        assertEquals("pass", RenderCheck.verdict(new ArrayList<>()));
        List<String> withError = new ArrayList<>(warnOnly);
        withError.add("[error] Uncaught TypeError: x is not a function (line 3)");
        assertEquals("fail", RenderCheck.verdict(withError));
    }

    @Test public void caps_lines_and_lengths() {
        List<String> in = new ArrayList<>();
        for (int i = 0; i < 30; i++) in.add("line " + i);
        List<String> capped = RenderCheck.capLines(in);
        assertEquals(RenderCheck.MAX_CONSOLE, capped.size());
        StringBuilder long1 = new StringBuilder();
        for (int i = 0; i < 900; i++) long1.append('x');
        List<String> one = new ArrayList<>();
        one.add(long1.toString());
        assertEquals(RenderCheck.MAX_LINE, RenderCheck.capLines(one).get(0).length());
        assertEquals("", RenderCheck.capOne(null));
    }

    @Test public void report_shape_and_optional_keys() {
        Map<String, Object> outline = new LinkedHashMap<>();
        outline.put("elements", 42);
        Map<String, Object> rep = RenderCheck.report("pass",
                new ArrayList<>(), outline,
                new ArrayList<>(), "a dark page with one slider", false);
        assertEquals("pass", rep.get("verdict"));
        assertEquals(true, rep.get("ok"));
        assertTrue(rep.containsKey("console"));
        assertTrue(rep.containsKey("notes"));
        assertTrue(rep.containsKey("outline"));
        assertEquals("a dark page with one slider", rep.get("look"));
        assertFalse(rep.containsKey("timedOut"));
        Map<String, Object> t = RenderCheck.report("fail", null, null,
                null, null, true);
        assertTrue(t.containsKey("timedOut"));
        assertFalse(t.containsKey("look"));
        String json = Json.write(rep);
        assertTrue(json.contains("\"verdict\":\"pass\""));
        assertTrue(json.contains("\"elements\":42"));
    }

    @Test public void outline_shaping_caps_and_stability() {
        assertNull(RenderCheck.shapeOutline(null).get("title"));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("title", "demo");
        raw.put("elements", 7);
        raw.put("mystery", "dropped");
        List<Object> headings = new ArrayList<>();
        for (int i = 0; i < 9; i++) headings.add("H1: heading " + i);
        raw.put("headings", headings);
        Map<String, Object> shaped = RenderCheck.shapeOutline(raw);
        assertEquals("demo", shaped.get("title"));
        assertEquals(6, ((List<?>) shaped.get("headings")).size());
        assertFalse(shaped.containsKey("mystery"));
    }

    // ------------------------------------------------- agent-facing text

    @Test public void note_teaches_the_loop_only_when_up() {
        String n = RenderCheck.note(43110, "abcd1234");
        assertTrue(n.startsWith("<system-reminder>"));
        assertTrue(n.endsWith("</system-reminder>"));
        assertTrue(n.contains("127.0.0.1:43110/render"));
        assertTrue(n.contains("X-Render-Key: abcd1234"));
        assertTrue(n.contains("verdict"));
        assertNull(RenderCheck.note(-1, "abcd1234"));
        assertNull(RenderCheck.note(43110, ""));
    }

    @Test public void clause_appends_the_canvas_check() {
        String c = RenderCheck.renderClause(43110, "tok");
        assertTrue(c.contains("curl -s -X POST"));
        assertTrue(c.contains("canvas.html"));
        assertTrue(c.contains("43110"));
        assertEquals("", RenderCheck.renderClause(0, "tok"));
    }

    @Test public void needsNote_only_when_armed() {
        assertTrue(RenderCheck.needsNote(Boolean.FALSE));
        assertFalse(RenderCheck.needsNote(Boolean.TRUE));
        assertFalse(RenderCheck.needsNote(null));
    }

    // ------------------------------------------------- check state (UI)

    @Test public void state_roundtrip_and_suffixes() {
        assertNull(RenderCheck.lastState("/no/such/page.html"));
        RenderCheck.remember("/p/a.html", true);
        RenderCheck.remember("/p/b.html", false);
        assertEquals("pass", RenderCheck.lastState("/p/a.html"));
        assertEquals("fail", RenderCheck.lastState("/p/b.html"));
        assertEquals(" · \u2713 checked", RenderCheck.chipSuffix("/p/a.html"));
        assertEquals(" · \u26A0 issues", RenderCheck.chipSuffix("/p/b.html"));
        assertEquals("", RenderCheck.chipSuffix("/p/never.html"));
        RenderCheck.remember(null, true);   // no-op, no throw
    }

    @Test public void state_lru_capped() {
        for (int i = 0; i < RenderCheck.STATE_CAP + 10; i++)
            RenderCheck.remember("/p/k" + i + ".html", true);
        // the eldest entries are evicted; the newest survive
        assertNull(RenderCheck.lastState("/p/k0.html"));
        assertEquals("pass", RenderCheck.lastState(
                "/p/k" + (RenderCheck.STATE_CAP + 9) + ".html"));
    }

    @Test public void look_prompt_is_agent_shaped() {
        assertTrue(RenderCheck.LOOK_PROMPT.contains("broken"));
        assertTrue(RenderCheck.LOOK_PROMPT.contains("compact"));
    }
}
