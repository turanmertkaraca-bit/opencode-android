package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P43 — the realtime-feel release, pinned where each piece lives.
 *
 * FIELD REPORT DRIVING THIS RELEASE (v0.42.0): the token stream flashed
 * in bursts instead of gliding ("it thinks it's realtime streaming but
 * it's just a burst"), the collapsed thinking card showed one flashing
 * line instead of a real part of the thought, the keyboard still popped
 * open by itself in sheets, very long chats lost their older tool cards
 * ("the tools keep going away"), a mid-task "hello world" got a
 * fresh-chat "Hello! What would you like help with?" back, a folder
 * touch printed a raw EISDIR exception in the live card, and idle
 * sessions paid the cold-cache tax the cachebeat project taught to
 * avoid.
 *
 * Pure logic here; the view wiring is in the worklog file:line map.
 * Robolectric runner: RunHub/EnvNote static init needs the shadow Looper
 * (the P25HubTest pattern) — the pure classes run unchanged under it.
 */
@RunWith(RobolectricTestRunner.class)
public class P43Test {

    // ================================================= 1. StreamPacer

    @Test public void pacer_firstPaintTakesABite_notTheWholeBurst() {
        StreamPacer p = new StreamPacer();
        long shown = p.tick(0, 5000);
        assertTrue("first paint must show an opening bite (got " + shown + ")",
                shown > 0 && shown <= 40);
    }

    @Test public void pacer_stableBacklogGlides_notFlashes() {
        StreamPacer p = new StreamPacer();
        p.tick(0, 4000);
        long shown = p.tick(48, 4000);   // 48 ms after the burst lands
        // the old rule would paint ~500 chars this tick; pacing must stay
        // readable and keep going — not empty the backlog in one frame
        assertTrue("a burst must not flash in one tick (got " + shown + ")",
                shown < 1000);
    }

    @Test public void pacer_burstFullyRevealedWithinLagCeiling() {
        StreamPacer p = new StreamPacer();
        p.tick(0, 4000);
        long shown = 0;
        long t = 0;
        for (int i = 0; i < 300 && shown < 4000; i++) {
            t += 24;
            shown = p.tick(t, 4000);
        }
        assertEquals("the whole burst must finish revealing ~2 s in",
                4000, shown);
        assertTrue("and it must take more than one tick (no flash)",
                t >= 48);
    }

    @Test public void pacer_steadyStreamStaysNearRealtime() {
        StreamPacer p = new StreamPacer();
        long target = 0;
        long t = 0;
        long shown = 0;
        for (int i = 0; i < 500; i++) {          // ~12 s at 24 ms
            t += 24;
            target += 3;                          // ~125 chars/s arriving
            shown = p.tick(t, target);
        }
        assertTrue("display must keep up with a steady stream ("
                        + shown + " of " + target + ")",
                shown >= target * 8 / 10);
        assertTrue("display must not run far ahead of arrival",
                shown <= target);
    }

    @Test public void pacer_shownNeverExceedsTarget_andShrinksSafely() {
        StreamPacer p = new StreamPacer();
        p.tick(0, 1000);
        long s1 = p.tick(24, 1000);
        assertTrue(s1 <= 1000);
        long s2 = p.tick(48, 10);        // a rebuild shrank the text
        assertTrue("shown collapses to the smaller target", s2 <= 10);
    }

    @Test public void pacer_monotonicWhileGrowing() {
        StreamPacer p = new StreamPacer();
        long t = 0, target = 40, prev = 0;
        for (int i = 0; i < 100; i++) {
            t += 24;
            target += 120;
            long s = p.tick(t, target);
            assertTrue("shown must never go backwards", s >= prev);
            prev = s;
        }
    }

    // ================================================== 2. CacheBeat

    private static final long NOW = 1_700_000_000_000L;

    @Test public void cachebeat_policyGates() {
        long quiet = NOW - CacheBeat.IDLE_MS - 1;
        assertTrue(CacheBeat.shouldFire(NOW, NOW - CacheBeat.IDLE_MS - 1,
                0, 0, 50_000, false, true));
        // the switch owns it
        assertFalse(CacheBeat.shouldFire(NOW, quiet, 0, 0, 50_000,
                false, false));
        // never while a run streams
        assertFalse(CacheBeat.shouldFire(NOW, quiet, 0, 0, 50_000,
                true, true));
        // per-stretch cap
        assertFalse(CacheBeat.shouldFire(NOW, quiet,
                CacheBeat.MAX_BEATS, 0, 50_000, false, true));
        // a tiny context re-reads for pennies anyway — no beat
        assertFalse(CacheBeat.shouldFire(NOW, quiet, 0, 0,
                CacheBeat.MIN_CONTEXT_TOK - 1, false, true));
        // unknown depth (0) is allowed to beat
        assertTrue(CacheBeat.shouldFire(NOW, quiet, 0, 0, 0,
                false, true));
        // not quiet long enough
        assertFalse(CacheBeat.shouldFire(NOW, NOW - CacheBeat.IDLE_MS / 2,
                0, 0, 50_000, false, true));
    }

    @Test public void cachebeat_failureBacksOff() {
        long base = NOW - CacheBeat.IDLE_MS - CacheBeat.FAIL_BACKOFF_MS / 2;
        // one failed beat: inside the backoff window → hold
        assertFalse(CacheBeat.shouldFire(NOW, base, 0, 1, 50_000,
                false, true));
        // well past the backoff → fire again
        long late = NOW - CacheBeat.IDLE_MS - CacheBeat.FAIL_BACKOFF_MS * 8;
        assertTrue(CacheBeat.shouldFire(NOW, late, 0, 1, 50_000,
                false, true));
    }

    @Test public void cachebeat_blockCarriesSignatureAndPinnedReply() {
        String b = CacheBeat.beatBlock();
        assertTrue(b.startsWith("<system-reminder>"));
        assertTrue(b.contains("Cache beat:"));
        assertTrue(b.contains("Reply with exactly: still here"));
        assertTrue(b.contains("Do not run tools"));
    }

    @Test public void cachebeat_bubbleRendersAsLabel_notBlock() {
        assertEquals(CacheBeat.rowLabel(),
                NoteStrip.display(CacheBeat.beatBlock()));
        assertEquals("♡ cache beat", CacheBeat.rowLabel());
    }

    @Test public void cachebeat_popoverLine_honestWhenOn_silentWhenOff() {
        assertTrue(CacheBeat.popoverLine(true, 7_800_000)
                .contains("Cache beats: armed"));
        assertTrue(CacheBeat.popoverLine(true, 7_800_000)
                .contains("7.8M"));
        assertEquals("", CacheBeat.popoverLine(false, 7_800_000));
    }

    // ================================================== 3. GreetGuard

    @Test public void greet_bareGreetingsDetected() {
        assertTrue(GreetGuard.isBareGreeting("hello world"));
        assertTrue(GreetGuard.isBareGreeting("hi"));
        assertTrue(GreetGuard.isBareGreeting("hey there"));
        assertTrue(GreetGuard.isBareGreeting("Hello!"));
        assertTrue(GreetGuard.isBareGreeting("test 123"));
        assertTrue(GreetGuard.isBareGreeting("  yo  "));
    }

    @Test public void greet_realMessagesNeverMatch() {
        assertFalse(GreetGuard.isBareGreeting("can you fix the build"));
        assertFalse(GreetGuard.isBareGreeting("hello, can you help?"));
        assertFalse(GreetGuard.isBareGreeting("clone the repo"));
        assertFalse(GreetGuard.isBareGreeting("hello https://x.com"));
        assertFalse(GreetGuard.isBareGreeting("hi `ls`"));
        assertFalse(GreetGuard.isBareGreeting(""));
        assertFalse(GreetGuard.isBareGreeting(null));
        // a long message is never a bare greeting, even if it opens hi
        assertFalse(GreetGuard.isBareGreeting(
                "hello there, I need you to finish the fly-brain clone now"));
    }

    @Test public void greet_recapBlockNamesTheThread() {
        String b = GreetGuard.recapBlock(212,
                "clone fly-brain into the playground",
                "wait what happened?",
                "The environment was repaired — HTTPS to github.com works.");
        assertTrue(b.startsWith("<system-reminder>"));
        assertTrue(b.contains(GreetGuard.SIG));
        assertTrue(b.contains("212 messages deep"));
        assertTrue(b.contains("NOT a new conversation"));
        assertTrue(b.contains("clone fly-brain into the playground"));
        assertTrue(b.contains("wait what happened?"));
        assertTrue(b.contains("offer to continue the ongoing task"));
        assertTrue(b.endsWith("</system-reminder>"));
    }

    @Test public void greet_clipTrimsToOneBoundedLine() {
        assertEquals("short", GreetGuard.clip("short", 100));
        assertEquals("first line", GreetGuard.clip("first line\nsecond", 100));
        assertNull(GreetGuard.clip("   \n  ", 100));
        assertNull(GreetGuard.clip(null, 100));
        String long1 = repeat("x", 300);
        String got = GreetGuard.clip(long1, 110);
        assertNotNull(got);
        assertTrue(got.length() <= 110);
        assertTrue(got.endsWith("…"));
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    // ================================== 4. RunHub.threadContextBlockTx

    @Test public void greet_threadBlock_requiresRealHistory() {
        RunHub.Tx t = new RunHub.Tx();
        assertNull("a fresh chat must greet normally",
                RunHub.threadContextBlockTx(t, "hello world"));
        t.rows.add(userRow("hello world"));
        assertNull("one turn is not a thread",
                RunHub.threadContextBlockTx(t, "hello world"));
    }

    @Test public void greet_threadBlock_anchorsAndSkipsTheLiveSend() {
        RunHub.Tx t = new RunHub.Tx();
        t.rows.add(userRow("clone fly-brain into the playground"));
        t.rows.add(asstRow("On it — cloning now."));
        t.rows.add(userRow("is it done yet?"));
        t.rows.add(asstRow("Still fetching — the pack is large."));
        t.rows.add(userRow("ok"));
        t.rows.add(asstRow("Working."));
        String block = RunHub.threadContextBlockTx(t, "hello world");
        assertNotNull(block);
        assertTrue(block.contains("clone fly-brain into the playground"));
        assertTrue(block.contains("the user's latest real message was: \"ok\""));
        // the CURRENT send must not be quoted as the previous message
        assertFalse(block.contains("\"hello world\""));
    }

    private static RunHub.Row userRow(String s) {
        RunHub.Row r = new RunHub.Row();
        r.kind = RunHub.K_USER;
        r.key = "u" + System.nanoTime();
        r.text.append(s);
        return r;
    }

    private static RunHub.Row asstRow(String s) {
        RunHub.Row r = new RunHub.Row();
        r.kind = RunHub.K_ASSISTANT;
        r.key = "a" + System.nanoTime();
        r.text.append(s);
        return r;
    }

    // =================================================== 5. NoteStrip

    @Test public void strip_greetBlockStripsSilently_beatLabelStays() {
        String greet = GreetGuard.recapBlock(30, "a", "b", "c")
                + "\n\nhello world";
        assertEquals("hello world", NoteStrip.display(greet));
        // beat block ahead of real text: label first, text kept
        String both = CacheBeat.beatBlock() + "\n\nwhat's next?";
        String shown = NoteStrip.display(both);
        assertTrue(shown.contains("what's next?"));
        assertTrue(shown.startsWith(CacheBeat.rowLabel()));
    }

    @Test public void strip_signaturesCrossPinned() {
        // P38 pattern: the builders and the strip must never drift
        List<String> sigs = new ArrayList<>();
        for (String s : NoteStrip.SIGNATURES) sigs.add(s);
        assertTrue(sigs.contains(GreetGuard.SIG));
        assertTrue(sigs.contains("Cache beat"));
        assertTrue(sigs.contains("Environment:"));
        assertTrue(sigs.contains("Context repair"));
    }

    // ============================================ 6. Resilience.pillMeter

    @Test public void pill_compactMeterFormat() {
        assertEquals("48k · 24%", Resilience.pillMeter(48_000, 200_000));
        assertEquals("48k", Resilience.pillMeter(48_000, 0));
        assertEquals("", Resilience.pillMeter(0, 200_000));
        assertEquals("99%+ still reads with its depth prefix",
                "500k · 99%+", Resilience.pillMeter(500_000, 200_000));
        // the popover keeps the FULL meter (window + percent)
        assertEquals("48k / 200k · 24%",
                Resilience.contextMeter(48_000, 200_000));
    }

    // ========================================= 7. EnvNote git resilience

    @Test public void envnote_teachesDepthOneAndFetchResume() {
        String note = EnvNote.note(EnvNote.Mode.DEBIAN, true, false);
        assertTrue(note.contains("--depth 1"));
        assertTrue(note.contains("git fetch --unshallow"));
        assertTrue(note.contains("do NOT delete and re-clone"));
        assertTrue(note.contains("re-probing it wastes the user's money"));
        // the note still closes as one block and strips clean
        assertTrue(note.endsWith("</system-reminder>"));
        assertEquals("", NoteStrip.display(note));
    }
}
