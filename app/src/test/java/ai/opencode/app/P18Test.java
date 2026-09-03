package ai.opencode.app;

import org.junit.Test;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.io.IOException;
import static org.junit.Assert.*;

/**
 * P18 "unstoppable" regression suite — the three field bugs:
 *  1. sandbox died → chat dead until cold boot   (supervisor + backoff + streak)
 *  2. raw "send failed: java.net.SocketTimeoutException: timeout"
 *     banner killed a still-running run          (timeout classifier + pretty text)
 *  3. "Σ counter going up way too much"          (formatters + context verdict)
 */
public class P18Test {

    // ------------------------------------------------- send timeout class

    @Test
    public void sendTimeout_directIsTrue() {
        assertTrue(Resilience.isSendTimeout(new SocketTimeoutException("timeout")));
    }

    @Test
    public void sendTimeout_wrappedIsTrue() {
        // OkHttp-style wrapping: the timeout hides behind IOException
        IOException w = new IOException("POST failed",
                new SocketTimeoutException("Read timed out"));
        assertTrue(Resilience.isSendTimeout(w));
    }

    @Test
    public void sendTimeout_refusedIsFalse() {
        assertFalse(Resilience.isSendTimeout(new ConnectException("Connection refused")));
        assertFalse(Resilience.isSendTimeout(new IOException("disk junk")));
        assertFalse(Resilience.isSendTimeout(null));
    }

    @Test
    public void brokenPipe_detected() {
        assertTrue(Resilience.isBrokenPipe(new IOException("Broken pipe")));
        assertTrue(Resilience.isBrokenPipe(new java.net.SocketException("Connection reset")));
        assertFalse(Resilience.isBrokenPipe(new SocketTimeoutException("timeout")));
    }

    // --------------------------------------------------- human error text

    @Test
    public void prettyError_neverLeaksJavaNet() {
        String a = Resilience.prettyNetError(new SocketTimeoutException("timeout"));
        String b = Resilience.prettyNetError(new ConnectException("Connection refused"));
        String c = Resilience.prettyNetError(new IOException("Broken pipe"));
        for (String s : new String[]{a, b, c}) {
            assertFalse("leaked java.net: " + s, s.contains("java.net"));
            assertFalse("leaked exception class: " + s, s.contains("Exception"));
            assertFalse("empty pretty text", s == null || s.isEmpty());
        }
        assertTrue(a.contains("quiet"));
        assertTrue(Resilience.prettyNetError(null).length() > 0);
    }

    // ------------------------------------------------------ crash streak

    @Test
    public void deathsInWindow_countsOnlyRecent() {
        long now = 1_000_000L;
        long[] deaths = {now - 60_000, now - 120_000, now - 61 * 60_000, 0};
        // window 10 min → two recent + one 61-min-old (out) + zero slot
        assertEquals(2, Resilience.deathsInWindow(deaths, now, 10 * 60_000));
    }

    @Test
    public void deathsInWindow_nullAndEmpty() {
        assertEquals(0, Resilience.deathsInWindow(null, 1L, 1000L));
        assertEquals(0, Resilience.deathsInWindow(new long[0], 1L, 1000L));
    }

    @Test
    public void threeDeathsInTenMinutes_surrenders() {
        long now = 2_000_000L;
        long[] deaths = {now - 10_000, now - 20_000, now - 30_000};
        // the supervisor gives up at >= 3 in 10 min (battery guard)
        assertTrue(Resilience.deathsInWindow(deaths, now, 10 * 60_000) >= 3);
    }

    @Test
    public void backoff_growsAndStaysSane() {
        assertTrue(Resilience.restartBackoffMs(0) < Resilience.restartBackoffMs(1));
        assertTrue(Resilience.restartBackoffMs(1) <= Resilience.restartBackoffMs(5));
        assertTrue(Resilience.restartBackoffMs(0) >= 1_000);   // never instant-spam
        assertTrue(Resilience.restartBackoffMs(9) <= 10_000);  // never minutes-long
    }

    // ------------------------------------------------------- diag record

    @Test
    public void diagLine_fourGreppableFields() {
        String l = Resilience.diagLine(1690000000000L, "died", "exit=137 · bun: oom\nsecond", 345_678L);
        assertTrue(l.startsWith("1690000000000 · died · "));
        assertTrue(l.contains("exit=137"));
        assertTrue(l.endsWith("memAvail=345678kB"));
        assertFalse("newlines must be flattened", l.contains("\n"));
    }

    @Test
    public void diagLine_nullSafe() {
        String l = Resilience.diagLine(1L, null, null, -1);
        assertTrue(l.contains(" - "));
        assertTrue(l.endsWith("memAvail=-1kB"));
    }

    @Test
    public void memAvailable_parsedFromProcFormat() {
        String meminfo = "MemTotal:       16384256 kB\n"
                + "MemFree:         1204552 kB\n"
                + "MemAvailable:    3456780 kB\n"
                + "Buffers:          210 K\n";
        assertEquals(3456780L, Resilience.parseMemAvailableKb(meminfo));
        assertEquals(-1L, Resilience.parseMemAvailableKb("no field here"));
        assertEquals(-1L, Resilience.parseMemAvailableKb(null));
    }

    // ------------------------------------------------------ Σ pill clarity

    @Test
    public void fmtTok_scalesLikeTheScreenshot() {
        // 1904.7k in the field → "1.9M" is the honest compact form now
        assertEquals("1.9M", Resilience.fmtTok(1_904_700L));
        assertEquals("86.7k", Resilience.fmtTok(86_700L));
        assertEquals("833", Resilience.fmtTok(833L));
        assertEquals("12.3k", Resilience.fmtTok(12_300L));
    }

    @Test
    public void contextVerdict_escalatesWithDepth() {
        assertEquals("", Resilience.contextVerdict(0));
        assertEquals("", Resilience.contextVerdict(-5));
        assertTrue(Resilience.contextVerdict(10_000).contains("light"));
        assertTrue(Resilience.contextVerdict(30_000).contains("moderate"));
        assertTrue(Resilience.contextVerdict(60_000).contains("heavy"));
        assertFalse(Resilience.contextVerdict(60_000).contains("very heavy"));
        assertTrue(Resilience.contextVerdict(120_000).contains("very heavy"));
        // the field report's 86k context must land in "heavy" (fresh-chat hint)
        String v = Resilience.contextVerdict(86_000);
        assertTrue("86k should advise a fresh chat: " + v, v.contains("Fresh chat"));
    }
}
