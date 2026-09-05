package ai.opencode.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P28 — pure pins for the peek upgrade:
 *
 *   • peekTailWindow — the big-file tier: the window is built from the
 *     file's LAST bytes, the focus mark sits on the NEWEST line, line
 *     numbers continue the skipped prefix, there is never a "+N more"
 *     footer (the window ends at EOF by construction), a POSIX-final
 *     newline is not a phantom line, and a mid-line slice start is marked.
 *
 *   • countNewlines — the line-number base for the tail window.
 *
 *   • focusRange — the "▸ " range the view paints with the accent
 *     highlight ("shows what the AI is editing right now").
 *
 *   • changed — the (len, mtime) memo: identical stamp → skip the read;
 *     any dimension differs → reload. This is what keeps an append storm
 *     from re-reading a megabyte file every 120 ms.
 */
public class P28Test {

    // ------------------------------------------------------------ tail peek

    private static final String LOREM =
            "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n"
            + "eleven\ntwelve\nthirteen\nfourteen\nfifteen\n";

    @Test
    public void tailWindow_focusIsNewestLine_numberingContinues() {
        byte[] tail = "eleven\ntwelve\nthirteen\nfourteen\nfifteen\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String out = EditPulse.peekTailWindow(tail, 10, 11, false);
        // the newest line carries the focus mark
        int mark = out.indexOf("▸ 15│ fifteen");
        assertTrue("focus on line 15 (10 skipped + 5 in slice), got:\n" + out,
                mark >= 0);
        // no "+N more" footer — the window ends at EOF
        assertFalse(out.contains("more"));
        // header counts the skipped prefix
        assertTrue(out.startsWith("  … 10 lines above"));
        // in-window lines are numbered with the prefix offset
        assertTrue(out.contains("11│ eleven"));
    }

    @Test
    public void tailWindow_finalNewlineIsNotAPhantomLine() {
        byte[] tail = "a\nb\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String out = EditPulse.peekTailWindow(tail, 0, 11, false);
        assertTrue(out.contains("▸ 2│ b"));
        assertFalse(out.contains("3│"));
    }

    @Test
    public void tailWindow_capsAtMaxLines_andCountsHiddenAbove() {
        String[] lines = LOREM.split("\n");
        // last 5 lines of LOREM, prefix = 10 lines
        StringBuilder sb = new StringBuilder();
        for (int i = 10; i < lines.length; i++) sb.append(lines[i]).append('\n');
        byte[] tail = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String out = EditPulse.peekTailWindow(tail, 10, 3, false);
        // only 3 lines rendered, newest focus
        assertEquals(3, out.split("\n").length - 1); // header + 3 lines... verify via marks
        assertTrue(out.contains("▸ 15│ fifteen"));
        assertTrue(out.contains("13│ thirteen"));
        assertFalse(out.contains("12│"));
        // header: 10 skipped + 2 hidden by the cap
        assertTrue(out.contains("12 lines above"));
    }

    @Test
    public void tailWindow_headCutMarksThePartialFirstLine() {
        byte[] tail = "ven\neleven\ntwelve\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String out = EditPulse.peekTailWindow(tail, 9, 11, true);
        assertTrue("partial first line carries the … marker", out.contains("…ven"));
        assertTrue(out.contains("▸ 12│ twelve"));
    }

    @Test
    public void tailWindow_noHeadCut_noMarker() {
        byte[] tail = "\neleven\ntwelve\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String out = EditPulse.peekTailWindow(tail, 9, 11, false);
        assertFalse(out.contains("…ven"));
        assertTrue(out.contains("11│ eleven"));
    }

    @Test
    public void tailWindow_emptyAndDegenerate() {
        assertEquals("", EditPulse.peekTailWindow(new byte[0], 0, 11, false));
        assertEquals("", EditPulse.peekTailWindow(null, 0, 11, false));
        byte[] tail = "solo".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String out = EditPulse.peekTailWindow(tail, 41, 11, true);
        assertTrue("headCut marks the single (partial) line",
                out.contains("▸ 42│ …solo"));
    }

    // -------------------------------------------------------- countNewlines

    @Test
    public void countNewlines_countsOnlyUpToLen() {
        byte[] b = "a\nb\nc\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(3, EditPulse.countNewlines(b, b.length));
        assertEquals(1, EditPulse.countNewlines(b, 2));
        assertEquals(0, EditPulse.countNewlines(b, 0));
        assertEquals(0, EditPulse.countNewlines(null, 5));
        assertEquals(0, EditPulse.countNewlines(b, -1));
    }

    // ------------------------------------------------------------ focusRange

    @Test
    public void focusRange_findsTheMarkedLine() {
        String peek = "  … 10 lines above\n  11│ eleven\n▸ 12│ twelve\n  13│ x";
        int[] r = EditPulse.focusRange(peek);
        assertEquals("▸ 12│ twelve",
                peek.substring(r[0], r[1]));
    }

    @Test
    public void focusRange_singleLineWindow_noTrailingNewline() {
        String peek = "▸ 42│ solo";
        int[] r = EditPulse.focusRange(peek);
        assertEquals("▸ 42│ solo", peek.substring(r[0], r[1]));
    }

    @Test
    public void focusRange_nullWhenNoMark() {
        assertNull(EditPulse.focusRange("  1│ plain\n  2│ rows"));
        assertNull(EditPulse.focusRange(null));
        assertNull(EditPulse.focusRange(""));
    }

    // ----------------------------------------------------------- the memo

    @Test
    public void changed_memoSemantics() {
        assertFalse("identical stamp → skip the read",
                EditPulse.changed(100, 200, 100, 200));
        assertTrue("length differs → reload",
                EditPulse.changed(100, 200, 101, 200));
        assertTrue("mtime differs → reload",
                EditPulse.changed(100, 200, 100, 201));
        assertTrue("both differ → reload",
                EditPulse.changed(100, 200, 105, 205));
    }
}
