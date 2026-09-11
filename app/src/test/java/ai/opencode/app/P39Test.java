package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.opencode.app.AmnesiaGuard.Obs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P39 — the context-integrity release, pinned where each piece lives:
 *
 * 1. THE FIELD TRUTH. The September screenshot proved the model received
 *    NO history (37 flat ~44k turns while it answered "This is a fresh
 *    chat"). The live rig exonerated the server (real opencode 1.18.25
 *    grew every payload, restarts included), so the app must SEE the
 *    signature and WORK AROUND it. The detector's branches are pinned
 *    here: healthy growth never fires; the field's flat pattern fires;
 *    compaction and error-partial turns never fool it; a collapse with
 *    no summary fires immediately.
 * 2. THE REPAIR. While confirmed, every send rides a system-reminder
 *    "Context repair" block carrying the recent thread (tail-capped) —
 *    the model is back in context whatever the device-side mechanism is.
 *    NoteStrip strips it from the user's bubble by the exact signature,
 *    same contract as the three P38 notes.
 * 3. THE HONEST OPEN. A clicked session whose server-side store is empty
 *    (200 + []) now says so once instead of leaving a silently
 *    fresh-looking chat; Diagnostics shows the sandbox's own log file —
 *    the witness for the next field report.
 */
@RunWith(RobolectricTestRunner.class)
public class P39Test {

    private static Obs obs(long tok) { return new Obs(tok, false); }
    private static Obs sum(long tok) { return new Obs(tok, true); }

    private static List<Obs> flat(int n, long base) {
        List<Obs> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(obs(base + (i % 3) * 37L));
        return out;
    }

    private static List<Obs> growing(int n, long base, long step) {
        List<Obs> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(obs(base + i * step));
        return out;
    }

    // ------------------------------------------------- 1. the detector

    @Test public void detector_healthyHistoryNeverFires() {
        // real rig numbers: each turn grows by the new exchange
        assertFalse(AmnesiaGuard.confirmed(
                growing(12, 43_700L, 900L)));
        // terse but honest: small absolute growth still clears the 1% bar
        assertFalse(AmnesiaGuard.confirmed(
                growing(9, 44_000L, 600L)));
    }

    @Test public void detector_fieldSignatureFires() {
        // the screenshot: ~44k every turn, jitter under 1%
        assertTrue(AmnesiaGuard.confirmed(flat(8, 44_000L)));
        // exactly at the window edge
        assertTrue(AmnesiaGuard.confirmed(flat(5, 44_000L)));
        // one turn short of the window: not yet
        assertFalse(AmnesiaGuard.confirmed(flat(4, 44_000L)));
    }

    @Test public void detector_smallSessionsAreNeverJudged() {
        // below MIN_BASE the numbers are noise — a fresh chat's first
        // turns must never trip the repair
        assertFalse(AmnesiaGuard.confirmed(flat(12, 2_400L)));
    }

    @Test public void detector_compactionResetsTheRun() {
        // flat, flat, ... summary (legit shrink), flat, flat — never fires
        List<Obs> seq = new ArrayList<>();
        seq.addAll(flat(4, 44_000L));
        seq.add(sum(12_000L));            // compaction: payload legitimately drops
        seq.addAll(flat(4, 12_500L));     // and 12.5k < MIN_BASE anyway
        assertFalse(AmnesiaGuard.confirmed(seq));
        // compaction mid-window: the run breaks even above the base
        List<Obs> seq2 = new ArrayList<>();
        seq2.addAll(flat(3, 44_000L));
        seq2.add(sum(45_000L));
        seq2.addAll(flat(4, 44_300L));
        assertFalse(AmnesiaGuard.confirmed(seq2));
    }

    @Test public void detector_collapseWithoutSummaryFires() {
        // payload loses more than a third in one summary-free step
        List<Obs> seq = new ArrayList<>();
        seq.addAll(growing(3, 44_000L, 300L));
        seq.add(obs(24_000L));            // the collapse itself (below base? no: 24k >= 10k)
        assertTrue(AmnesiaGuard.confirmed(seq));
        // the same collapse WITH a summary between: legitimate compact
        List<Obs> seq2 = new ArrayList<>();
        seq2.addAll(growing(3, 44_000L, 300L));
        seq2.add(sum(24_000L));
        seq2.add(obs(24_500L));
        assertFalse(AmnesiaGuard.confirmed(seq2));
    }

    @Test public void detector_errorAndEmptyTurnsAreSkipped() {
        // an error-partial turn (tiny tokens) and usage-less messages
        // neither fire nor break the flat chain
        List<Obs> seq = new ArrayList<>();
        seq.add(obs(44_000L));
        seq.add(obs(300L));               // error-partial — skipped entirely
        seq.addAll(flat(5, 44_100L));
        assertTrue(AmnesiaGuard.confirmed(seq));
        // all-zero usage: never judged
        assertFalse(AmnesiaGuard.confirmed(Arrays.asList(
                new Obs(0, false), new Obs(0, false),
                new Obs(0, false), new Obs(0, false), new Obs(0, false))));
        // degenerate inputs
        assertFalse(AmnesiaGuard.confirmed(null));
        assertFalse(AmnesiaGuard.confirmed(new ArrayList<>()));
    }

    // ------------------------------------------------- 2. the repair

    @Test public void recap_signatureMatchesNoteStrip() {
        // the cross-pin: whatever the builder opens with, the strip must
        // recognize — drift on either side fails here
        String block = AmnesiaGuard.recapBlock("[user] hi\n[assistant] hello");
        assertTrue(block.startsWith("<system-reminder>"));
        int sigAt = block.indexOf(AmnesiaGuard.SIGNATURE);
        assertTrue(sigAt > 0);
        // NoteStrip sees it as an app note (open tag + optional newline
        // + signature) — the exact probe display() uses
        String wire = block + "\n\nwhat did I ask before?";
        assertTrue(NoteStrip.appNote(wire, 0));
        assertEquals("what did I ask before?", NoteStrip.display(wire));
        // and the round trip keeps the WIRE bytes untouched
        assertTrue(wire.contains("[user] hi"));
    }

    @Test public void recap_tailCapHolds() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append("[user] line ").append(i).append('\n');
        String block = AmnesiaGuard.recapBlock(big.toString());
        // the digest body is tail-capped to RECAP_MAX_CHARS (+ head marker)
        assertTrue(block.length() < AmnesiaGuard.RECAP_MAX_CHARS + 800);
        assertTrue(block.contains("line 1999"));     // the RECENT end survives
        assertFalse(block.contains("line 0\n"));      // the oldest head does not
        assertTrue(block.startsWith("<system-reminder>"));
    }

    @Test public void recap_digestLineFlattensAndCaps() {
        assertEquals("[user] hello world !",
                AmnesiaGuard.digestLine("user", "hello\n  world\t!"));
        assertNull(AmnesiaGuard.digestLine("user", "   \n  "));
        assertNull(AmnesiaGuard.digestLine("assistant", null));
        String fat = AmnesiaGuard.digestLine("assistant",
                new String(new char[AmnesiaGuard.TURN_MAX_CHARS + 500]).replace('\0', 'x'));
        // the TEXT is capped at TURN_MAX_CHARS; the kind prefix rides on top
        assertEquals(AmnesiaGuard.TURN_MAX_CHARS + "[assistant] ".length(),
                fat.length());
        assertTrue(fat.endsWith("…"));
        assertTrue(fat.startsWith("[assistant] "));
    }

    // ------------------------------ 3. the hub's pure views (digest/obs)

    @Test public void hub_assistantObsSortsByIdNotByUpdate() throws Exception {
        RunHub.Tx t = new RunHub.Tx();
        t.sid = "ses_x";
        // insert OUT of id order, the way SSE updates refresh recency
        msg(t, "msg_c", 44_002L, false);
        msg(t, "msg_a", 44_000L, false);
        msg(t, "msg_b", 44_001L, false);
        msg(t, "msg_d", 800L, false);      // error-partial assistant
        msg(t, "msg_e", 0L, false);        // user message — filtered by role below
        t.msgs.get("msg_e").role = "user";
        List<Obs> out = RunHub.assistantObs(t);
        assertEquals(4, out.size());
        assertEquals(44_000L, out.get(0).tok);
        assertEquals(44_001L, out.get(1).tok);
        assertEquals(44_002L, out.get(2).tok);
        assertEquals(800L, out.get(3).tok);
    }

    @Test public void hub_threadDigestKeepsOnlyChatRowsInOrder() throws Exception {
        RunHub.Tx t = new RunHub.Tx();
        t.sid = "ses_x";
        row(t, RunHub.K_USER, "hello there");
        row(t, RunHub.K_TOOL, "bash · ok");                 // tool rows stay out
        row(t, RunHub.K_SYS, "■ stop requested");           // system lines too
        row(t, RunHub.K_ASSISTANT, "hi! how can I help?");
        row(t, RunHub.K_USER, "what did I first say?");
        String d = RunHub.threadDigest(t);
        assertTrue(d.contains("[user] hello there"));
        assertTrue(d.contains("[assistant] hi! how can I help?"));
        assertTrue(d.contains("[user] what did I first say?"));
        assertFalse(d.contains("bash"));
        assertFalse(d.contains("stop requested"));
        // order: the first user line comes before the assistant reply
        assertTrue(d.indexOf("[user] hello there")
                < d.indexOf("[assistant] hi! how can I help?"));
    }

    @Test public void hub_contextRepairNeedsMatchingSid() throws Exception {
        RunHub.Tx t = new RunHub.Tx();
        t.sid = "ses_live";
        for (int i = 0; i < 6; i++) {
            msg(t, "msg_00" + i, 44_000L + i * 11L, false);
        }
        RunHub.Tx prev = swapCur(t);
        try {
            // confirmed signature + matching sid → the recap rides
            String recap = RunHub.contextRepair("ses_live");
            assertTrue(recap != null && recap.contains("Context repair"));
            // wrong sid → null (never leak one session's thread into another)
            assertNull(RunHub.contextRepair("ses_other"));
            // healthy growth → null
            RunHub.Tx healthy = new RunHub.Tx();
            healthy.sid = "ses_live";
            for (int i = 0; i < 6; i++) {
                msg(healthy, "msg_10" + i, 44_000L + i * 4_000L, false);
            }
            swapCur(healthy);
            assertNull(RunHub.contextRepair("ses_live"));
        } finally {
            swapCur(prev);
        }
    }

    // ------------------------------------------------------- helpers

    private static void msg(RunHub.Tx t, String id, long tok, boolean summary)
            throws Exception {
        java.lang.reflect.Field f = RunHub.Tx.class.getDeclaredField("msgs");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, RunHub.MsgInfo> msgs =
                (java.util.Map<String, RunHub.MsgInfo>) f.get(t);
        RunHub.MsgInfo mi = new RunHub.MsgInfo();
        mi.role = "assistant";
        mi.tok = tok;
        mi.summary = summary;
        msgs.put(id, mi);
    }

    private static void row(RunHub.Tx t, int kind, String text) throws Exception {
        java.lang.reflect.Field f = RunHub.Tx.class.getDeclaredField("rows");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<RunHub.Row> rows = (java.util.List<RunHub.Row>) f.get(t);
        RunHub.Row r = new RunHub.Row();
        r.kind = kind;
        r.key = "k" + rows.size();
        r.text.append(text);
        rows.add(r);
    }

    /** Swap the hub's displayed transcript (package-private static `cur`). */
    private static RunHub.Tx swapCur(RunHub.Tx t) throws Exception {
        java.lang.reflect.Field f = RunHub.class.getDeclaredField("cur");
        f.setAccessible(true);
        RunHub.Tx prev = (RunHub.Tx) f.get(null);
        f.set(null, t);
        return prev;
    }
}
