package ai.opencode.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P24 "flush surgeon" pins — the pure halves of the per-row paint fault
 * isolation. The field device ran a build where ONE row that could not
 * paint froze the whole transcript (P23's batch-level guard aborted the
 * coalesced flush, and the feed re-dirtied the same row on every delta —
 * banner wall + dead chat while the run stayed healthy). These pins hold
 * the contract: a row fails ALONE, repeat offenders quarantine into a
 * can't-fail fallback line, and every contained failure can name itself
 * in one bounded line.
 */
public class P24Test {

    // ------------------------------------------------------- flush shape

    /**
     * THE P24 CONTRACT, in its purest form: the per-row guard shape the
     * flush now runs — a poison task in the middle of a batch must not
     * stop the tasks behind it. (P23's shape ran the whole batch under
     * ONE guard: first failure = batch over.)
     */
    @Test
    public void flushShape_poisonRowDoesNotBlockSiblings() {
        List<String> painted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<Runnable> batch = Arrays.asList(
                () -> painted.add("row-a"),
                () -> { throw new IllegalStateException("poison row"); },
                () -> painted.add("row-c"));
        for (Runnable task : batch) {
            Throwable t = Resilience.guard(task);
            if (t == null) continue;
            failed.add(String.valueOf(t.getMessage()));
        }
        assertEquals("siblings AFTER the poison row must still paint",
                Arrays.asList("row-a", "row-c"), painted);
        assertEquals("the poison is reported exactly once", 1, failed.size());
        assertEquals("poison row", failed.get(0));
    }

    // ------------------------------------------------------- quarantine

    @Test
    public void quarantine_policyIsTwoStrikes() {
        assertEquals("one transient failure gets a second chance; the "
                        + "second failure quarantines",
                2, Resilience.paintFailQuarantineAfter());
    }

    @Test
    public void quarantineLine_boundedAndPointsAtTheTrail() {
        String line = Resilience.quarantineLine();
        assertNotNull(line);
        assertTrue("must point at the diagnostics trail",
                line.contains("Logs & shell"));
        assertTrue("must stay a single quiet line", line.length() <= 120);
        assertFalse(line.contains("\n"));
    }

    // -------------------------------------------------------- traceLine

    @Test
    public void traceLine_carriesClassMessageAndTopAppFrame() {
        Throwable boom = new IllegalStateException("field boom");
        StackTraceElement[] st = {
                new StackTraceElement("android.view.View", "measure", "View.java", 23),
                new StackTraceElement("ai.opencode.app.ChatActivity",
                        "paintRowOnce", "ChatActivity.java", 490),
        };
        boom.setStackTrace(st);
        String line = Resilience.traceLine(boom);
        assertTrue("class name", line.contains("java.lang.IllegalStateException"));
        assertTrue("message", line.contains("field boom"));
        assertTrue("skips framework frames, lands on the first app frame",
                line.contains("ChatActivity.paintRowOnce:490"));
    }

    @Test
    public void traceLine_nullSafeAndBounded() {
        assertEquals("-", Resilience.traceLine(null));

        Throwable noMsg = new OutOfMemoryError();
        noMsg.setStackTrace(new StackTraceElement[0]);
        assertEquals("java.lang.OutOfMemoryError", Resilience.traceLine(noMsg));

        char[] big = new char[5000];
        Arrays.fill(big, 'x');
        Throwable loud = new RuntimeException(new String(big));
        loud.setStackTrace(new StackTraceElement[0]);
        String line = Resilience.traceLine(loud);
        assertTrue("long identities get truncated, never flood a note row",
                line.length() <= 201);
        assertTrue(line.endsWith("…"));
    }

    @Test
    public void traceLine_stripsNewlinesFromMessages() {
        Throwable multi = new RuntimeException("line1\nline2\rline3");
        multi.setStackTrace(new StackTraceElement[0]);
        String line = Resilience.traceLine(multi);
        assertFalse("notes are single-line rows", line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertTrue(line.contains("line1 line2 line3"));
    }

    // --------------------------------------------- guard regression pin

    /** P23's guard must keep holding Errors at Throwable breadth — P24
     *  builds the quarantine on top of exactly this behavior. */
    @Test
    public void guard_catchesErrorsNotJustExceptions() {
        Throwable t = Resilience.guard(() -> {
            throw new LinkageError("verifier");
        });
        assertNotNull(t);
        assertTrue(t instanceof LinkageError);
        assertEquals(null, Resilience.guard(() -> {}));
    }
}
