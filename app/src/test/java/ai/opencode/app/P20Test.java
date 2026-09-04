package ai.opencode.app;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * P20 "the background survivor" regression suite — the field report:
 *  1. "i let the app work in background when i came back i couldnt look
 *     into a tought buble it looked empty" — the chat unsubscribes from
 *     the feed in onPause, and onResume only refetched an EMPTY list, so
 *     every part that fired while away was lost forever. P20 replays the
 *     session from the server's message store on every resume.
 *  2. "add realtime token by token streaming as well" — the P9 smoother
 *     only drove assistant TEXT rows; reasoning (thinking) rows painted
 *     in raw SSE bursts. P20: the ticker drives thinking rows too, and a
 *     collapsed card grows a live one-line ticker of the freshest thought.
 *  3. a run that finishes (or dies) while away must SETTLE the chat, and
 *     reasoning parts born empty must never leave a dead THINKING card.
 */
public class P20Test {

    // ------------------------------------------------- stable part keys

    @Test
    public void stablePartKey_onlyWhenPartHasAnId() {
        assertTrue(Resilience.stablePartKey("prt_01J"));
        assertTrue(Resilience.stablePartKey("x"));      // 1-char id is still an id
        assertFalse(Resilience.stablePartKey(null));
        assertFalse(Resilience.stablePartKey(""));
        // pid-less parts are keyed with a session-local counter live, so a
        // replay can NOT rebuild the key — reconcile must skip them for
        // messages already on screen (duplicate-row guard).
    }

    // ------------------------------------------------- live think window

    @Test
    public void thinkWindow_emptyAndImmatureInput() {
        assertEquals("", Resilience.thinkWindow(null, 10, 110));
        assertEquals("", Resilience.thinkWindow("", 0, 110));
        assertEquals("", Resilience.thinkWindow("abc", 0, 110));   // nothing revealed yet
        assertEquals("", Resilience.thinkWindow("abc", 5, 0));     // degenerate max
        assertEquals("", Resilience.thinkWindow("abc", -3, 110));
    }

    @Test
    public void thinkWindow_shortTextFitsWhole() {
        String t = "let me check the watchers";
        assertEquals(t, Resilience.thinkWindow(t, t.length(), 110));
        // partial reveal shows only the revealed prefix
        assertEquals("let me", Resilience.thinkWindow(t, 6, 110));
    }

    @Test
    public void thinkWindow_longTextKeepsTheFreshestSlice() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("chunk").append(i).append(' ');
        String t = sb.toString();
        String win = Resilience.thinkWindow(t, t.length(), 110);
        assertTrue(win.length() <= 110);
        assertTrue("window must hold the END (freshest thinking)",
                t.endsWith(win.substring(win.length() - 10)));
    }

    @Test
    public void thinkWindow_cutsForwardPastANewlineInsideTheWindow() {
        // window spans the newline → content after it (clean line start)
        assertEquals("defghijklmno",
                Resilience.thinkWindow("abc\ndefghijklmno", 16, 12));
        // window entirely past the newline → raw tail slice (no newline in it)
        String win = Resilience.thinkWindow(
                "first line of thinking\nsecond line keeps going on", 49, 20);
        assertTrue(win.length() <= 20);
        assertTrue("freshest slice must survive", win.endsWith("going on"));
    }

    @Test
    public void thinkWindow_stripsTrailingNewlines() {
        String t = "thinking out loud\n\n\n";
        String win = Resilience.thinkWindow(t, t.length(), 110);
        assertFalse(win.endsWith("\n"));
    }

    @Test
    public void thinkWindow_allNewlinesNeverBlank() {
        String t = "abc\n\n\n";
        // window of newlines only, but there IS older content → ellipsis
        String win = Resilience.thinkWindow(t, t.length(), 2);
        assertEquals("…", win);
        // same at the very start of the stream → empty (caller paints "…")
        assertEquals("", Resilience.thinkWindow("\n\n", 2, 2));
    }

    @Test
    public void thinkWindow_windowEndingOnANewlineShowsEllipsis() {
        // the freshest revealed char IS a newline → nothing to show yet
        assertEquals("…", Resilience.thinkWindow("hello world\n", 12, 5));
    }

    // ------------------------------------------------- settle after resume

    @Test
    public void settle_onlyForAFinishedAssistantTail() {
        // run finished while away → settle
        assertTrue(Resilience.shouldSettle(true, true, true));
        // still busy (run going) → never settle
        assertFalse(Resilience.shouldSettle(false, true, true));
        // last message is the USER's (assistant msg still pending) → never
        assertFalse(Resilience.shouldSettle(true, false, true));
        // assistant message exists but has no time.completed → running → never
        assertFalse(Resilience.shouldSettle(true, true, false));
    }
}
