package ai.opencode.app;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P23 "blast-radius zero" regression suite — the on-send crash answer.
 *
 * Field evidence: the device died on message send with an unhandled Java
 * exception (ApplicationExitInfo reason 4 = CRASH, 1 kB last-crash.txt
 * written by the app's own handler) while every audited send-path stage
 * had a catch(Exception). The hole: catch(Exception) does NOT stop Errors
 * (OutOfMemoryError, linkage, verifier throws), and the app crosses many
 * thread boundaries (worker pool, feed dispatch, SSE reader, drain,
 * posted UI runnables) where an uncaught Throwable kills the PROCESS.
 *
 * The P23 contract, pinned here:
 *  - Resilience.guard runs a body at Throwable breadth and RETURNS the
 *    Throwable instead of letting it escape — null on a clean run.
 *  - guardLine renders one bounded, greppable line: ts · what · class
 *    [: message] · first ai.opencode.app frame — never throws, never
 *    exceeds its caps, safe on nulls.
 *  - Trail.record appends with tail rotation at the 24 kB cap and never
 *    throws — a forensics writer that dies with the log is worthless.
 */
public class P23Test {

    // ------------------------------------------------------------ guard

    @Test
    public void guard_returnsNullOnCleanRun() {
        AtomicInteger ran = new AtomicInteger();
        Throwable t = Resilience.guard(ran::incrementAndGet);
        assertNull(t);
        assertEquals(1, ran.get());
    }

    @Test
    public void guard_returnsTheExceptionInsteadOfThrowing() {
        RuntimeException boom = new RuntimeException("field crash");
        Throwable t = Resilience.guard(() -> { throw boom; });
        assertSame(boom, t);
    }

    /** THE regression: catch(Exception) let Errors kill the process. */
    @Test
    public void guard_containsErrors_notJustExceptions() {
        OutOfMemoryError oom = new OutOfMemoryError("catalog parse burst");
        Throwable t = Resilience.guard(() -> { throw oom; });
        assertSame(oom, t);

        NoClassDefFoundError ncd = new NoClassDefFoundError("x");
        assertSame(ncd, Resilience.guard(() -> { throw ncd; }));

        StackOverflowError soe = new StackOverflowError("deep json");
        assertSame(soe, Resilience.guard(() -> { throw soe; }));
    }

    @Test
    public void guard_exceptionStillContained_andBodyRunsOnce() {
        AtomicInteger ran = new AtomicInteger();
        IllegalStateException ise = new IllegalStateException("late body");
        Throwable t = Resilience.guard(() -> {
            ran.incrementAndGet();
            throw ise;
        });
        assertSame(ise, t);
        assertEquals(1, ran.get());
    }

    // --------------------------------------------------------- guardLine

    @Test
    public void guardLine_nullThrowableIsSafe() {
        String line = Resilience.guardLine(1234L, "send", null);
        assertTrue(line, line.startsWith("1234 · send · -"));
    }

    @Test
    public void guardLine_carriesClassMessageAndTopAppFrame() {
        Exception e = new IllegalStateException("the message");
        StackTraceElement[] st = {
            new StackTraceElement("android.os.Handler", "handleCallback", "Handler.java", 995),
            new StackTraceElement("ai.opencode.app.ChatActivity", "send", "ChatActivity.java", 881),
        };
        e.setStackTrace(st);
        String line = Resilience.guardLine(42L, "send", e);
        assertTrue(line, line.startsWith("42 · send · java.lang.IllegalStateException: the message"));
        assertTrue(line, line.endsWith("ChatActivity.send:881"));
        assertFalse("framework frames must be skipped for the app frame",
                line.contains("Handler.handleCallback"));
    }

    @Test
    public void guardLine_boundsHugeMessages_andHandlesNullMessage() {
        Exception big = new RuntimeException("x".repeat(5000));
        String line = Resilience.guardLine(1L, "w", big);
        assertTrue("bounded", line.length() < 400);
        assertTrue(line, line.contains("…"));

        Exception nm = new RuntimeException();
        String l2 = Resilience.guardLine(1L, "w", nm);
        assertTrue(l2, l2.contains("java.lang.RuntimeException"));
        assertFalse(l2, l2.contains("null"));
    }

    @Test
    public void guardLine_newlinesInMessagesAreFlattened() {
        Exception e = new RuntimeException("line1\nline2\rline3");
        String line = Resilience.guardLine(1L, "w", e);
        assertFalse(line, line.contains("\n"));
        assertFalse(line, line.contains("\r"));
    }

    // ------------------------------------------------------------- Trail

    private static List<String> lines(File f) throws Exception {
        List<String> out = new ArrayList<>();
        for (String s : Files.readAllLines(f.toPath())) out.add(s);
        return out;
    }

    @Test
    public void trail_appendsOneLinePerRecord_andNeverThrows() throws Exception {
        File f = Files.createTempFile("trail", ".log").toFile();
        f.deleteOnExit();
        Trail.record(f, "send", new RuntimeException("boom"));
        Trail.record(f, "paint flush", null);
        List<String> ls = lines(f);
        assertEquals(2, ls.size());
        assertTrue(ls.get(0), ls.get(0).contains("· send · java.lang.RuntimeException: boom"));
        assertTrue(ls.get(1), ls.get(1).contains("· paint flush · -"));
    }

    @Test
    public void trail_rotatesKeepingTheTail_whenCapExceeded() throws Exception {
        File f = Files.createTempFile("trail-rot", ".log").toFile();
        f.deleteOnExit();
        // pad to just under the cap, then push over it
        StringBuilder pad = new StringBuilder();
        while (pad.length() < Trail.capBytes() - 2048) pad.append("pad\n");
        Files.write(f.toPath(), pad.toString().getBytes("UTF-8"));
        for (int i = 0; i < 60; i++)
            Trail.record(f, "rot" + i, new RuntimeException("r" + i));
        List<String> ls = lines(f);
        assertTrue("rotated down", f.length() <= Trail.capBytes() + 512);
        assertTrue("recent entries survive rotation",
                ls.get(ls.size() - 1).contains("rot59"));
    }

    @Test
    public void trail_nullFileIsNullSafe() {
        Trail.record((File) null, "x", new RuntimeException("y"));  // must not throw
    }

    // ------------------------------------------ send-path shape (pure)

    /** The send worker's new catch shape, replayed on the JVM: an Error in
     *  the POST stage must leave `busy` cleared and `sending` released —
     *  the exact invariant the field crash violated. */
    @Test
    public void sendWorkerShape_errorLeavesLatchReleased() {
        final AtomicInteger busy = new AtomicInteger(1);
        final java.util.concurrent.atomic.AtomicBoolean sending =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final List<String> notes = new ArrayList<>();
        Throwable caught = Resilience.guard(() -> {
            try {
                throw new OutOfMemoryError("mid-send");
            } catch (Throwable e) {
                notes.add("contained");     // Trail.record in the real path
                busy.set(0);                // setBusy(false)
            } finally {
                sending.set(false);         // the P22 latch MUST release
            }
        });
        assertNull("containment must swallow it", caught);
        assertEquals(0, busy.get());
        assertFalse(sending.get());
        assertEquals(1, notes.size());
    }
}
