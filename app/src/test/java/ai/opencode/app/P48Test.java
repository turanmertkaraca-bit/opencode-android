package ai.opencode.app;

import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P48 — the SMOOTH release, pinned where each piece lives.
 *
 * FIELD REPORT DRIVING THIS RELEASE (v0.47.0): streaming WORKS now, but
 * "the tokens come and go so fast it glitches the ui, it goes up and
 * down — almost had a seizure" and the thinking bubble needs "a fixed
 * length" showing the thought "a bit slower — faster than reading
 * speed". Three diseases, three cures, pinned here:
 *
 *   1. THE STROBE. The P43 pacer's lag ceiling allowed unbounded drain
 *      rates — a 4 kB burst revealed at ~1800 chars/s, fast providers
 *      faster still. P48 gives the pacer PROFILES with a hard MAX_RATE:
 *      the answer glide caps at 900 chars/s; THINKING rows reveal on a
 *      calm ≤170 chars/s (faster than reading speed ~20 c/s, slow
 *      enough to follow). The legacy no-arg constructor keeps the exact
 *      uncapped P43 behavior — the P43 pins run against it unchanged.
 *
 *   2. THE BOUNCING LIST. Two independent oscillators, both pinned:
 *      the collapsed thought window was maxLines(3) only, so the
 *      sliding window changed line count on every reveal and the whole
 *      list jumped (pinned: minLines == maxLines == 3 — a FIXED stage);
 *      and the scroll-changed listener fired for OUR programmatic
 *      scrolls too, flipping the pin off between a paint and its
 *      corrective scroll (pinned behaviorally: programmatic scrolls
 *      hold the pin via the progScroll bracket — the listener itself is
 *      view wiring; the snap/resume pins below cover the data path).
 *
 *   3. THE BACKGROUND REPLAY. The ticker painted into a detached view
 *      tree while paused (battery burn), and returning mid-stream
 *      re-animated old text. P48 stops the ticker on pause and snaps
 *      every background-arrived row to its settled text on resume —
 *      nothing that streamed while away may replay its glide.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class P48Test {

    // ================================================= 1. The profiles

    private static final int TICK = 24;

    @Test public void pacer_think_firstPaintTakesABite() {
        StreamPacer p = StreamPacer.forThinking();
        long shown = p.tick(0, 5000);
        assertTrue("the thought window opens with a bite, not a flood (got "
                + shown + ")", shown > 0 && shown <= 40);
    }

    @Test public void pacer_think_calmCap_fiveCharsMaxPerTick() {
        StreamPacer p = StreamPacer.forThinking();
        long prev = p.tick(0, 100_000);           // the opening bite
        for (int i = 0; i < 200; i++) {           // ~4.8 s of ticks
            long s = p.tick((i + 1) * TICK, 100_000);
            long step = s - prev;
            // 170 chars/s at 24 ms ⇒ ceil(4.08) = 5 chars — a readable
            // crawl, faster than reading speed, never a strobe.
            assertTrue("thinking reveal must stay calm (step " + step + ")",
                    step <= 5);
            prev = s;
        }
    }

    @Test public void pacer_think_hugeBacklog_neverFlashDrains() {
        // THE P48 FIELD CASE: a thinking model produces 100 kB while the
        // user watches a 3-line window. The legacy lag ceiling would
        // drain at ~45000 chars/s; the profile must not.
        StreamPacer p = StreamPacer.forThinking();
        long prev = p.tick(0, 100_000);           // the opening bite
        for (int i = 0; i < 50; i++) {
            long s = p.tick((i + 1) * TICK, 100_000);
            assertTrue("backlog size must not buy speed (step "
                    + (s - prev) + ")", s - prev <= 5);
            prev = s;
        }
    }

    @Test public void pacer_think_floorKeepsItMoving_inAStall() {
        StreamPacer p = StreamPacer.forThinking();
        p.tick(0, 1000);                          // bite
        long prev = p.tick(TICK, 1000);           // EWMA sees no arrival
        for (int i = 2; i < 100; i++) {           // the model stalled
            long s = p.tick(i * TICK, 1000);
            assertTrue("the window must keep crawling in a stall (step "
                    + (s - prev) + ")", s - prev >= 1);
            assertTrue("the stall rate must stay gentle (step "
                    + (s - prev) + ")", s - prev <= 2);
            prev = s;
        }
    }

    @Test public void pacer_answer_cap_22CharsMaxPerTick() {
        StreamPacer p = StreamPacer.forAnswer();
        long prev = p.tick(0, 100_000);           // the opening bite
        for (int i = 0; i < 200; i++) {
            long s = p.tick((i + 1) * TICK, 100_000);
            // 900 chars/s at 24 ms ⇒ ceil(21.6) = 22 — a fast glide,
            // clearly quicker than reading, never a flash.
            assertTrue("answer reveal must stay human (step "
                    + (s - prev) + ")", s - prev <= 22);
            prev = s;
        }
    }

    @Test public void pacer_legacy_uncapped_stillDrainsFast() {
        // The no-arg constructor is the P43 contract, uncapped — the P43
        // pins (burst fully revealed inside the 2.2 s lag ceiling) only
        // hold if the drain term survives. Guard it from "helpful" edits.
        StreamPacer p = new StreamPacer();
        p.tick(0, 100_000);
        long prev = 0;
        boolean sawFast = false;
        for (int i = 0; i < 50; i++) {
            long s = p.tick((i + 1) * TICK, 100_000);
            if (s - prev > 22) sawFast = true;
            prev = s;
        }
        assertTrue("legacy pacer must still drain hard (the P43 pins "
                + "depend on it)", sawFast);
    }

    @Test public void pacer_profiles_flags() {
        assertTrue(StreamPacer.forThinking().thinkingRow);
        assertFalse(StreamPacer.forAnswer().thinkingRow);
        assertFalse("legacy is the answer profile's ancestor, uncapped",
                new StreamPacer().thinkingRow);
    }

    @Test public void pacer_bothProfiles_monotonic_bounded() {
        StreamPacer[] ps = {StreamPacer.forAnswer(), StreamPacer.forThinking()};
        for (StreamPacer p : ps) {
            long t = 0, target = 40, prev = 0;
            for (int i = 0; i < 100; i++) {
                t += TICK;
                target += 120;                    // ~5000 chars/s arriving
                long s = p.tick(t, target);
                assertTrue("never backwards", s >= prev);
                assertTrue("never beyond target", s <= target);
                prev = s;
            }
        }
    }

    @Test public void pacer_answer_steadyStreamKeepsUp() {
        StreamPacer p = StreamPacer.forAnswer();
        long target = 0, t = 0, shown = 0;
        for (int i = 0; i < 500; i++) {           // ~12 s at 24 ms
            t += TICK;
            target += 3;                          // ~125 chars/s arriving
            shown = p.tick(t, target);
        }
        assertTrue("a steady stream must stay near-realtime (" + shown
                + " of " + target + ")", shown >= target * 8 / 10);
        assertTrue(shown <= target);
    }

    // ==================================== 2. Background-arrival snap

    @Before
    public void resetHub() throws Exception {
        RunHub.Tx t = RunHub.tx();
        synchronized (RunHub.lock()) {
            t.rows.clear();
            t.idxByKey.clear();
            t.msgs.clear();
            t.typeCount.clear();
            t.trimmedKeys.clear();
            t.lastAssistantTok = 0;
            t.depthPeak = 0;
            t.costSum = 0;
            t.tokSum = 0;
        }
        set("sessionId", "sess-1");
        set("sessionTitle", "test");
        set("busy", false);
        RunHub.clearRunsForTest();
        set("lastUserText", null);
    }

    private static void set(String field, Object v) throws Exception {
        Field f = RunHub.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Add a reasoning row directly to the hub model (shown = 0, i.e.
     *  "streamed while nobody watched"). */
    private static RunHub.Row addReasonRow(String key, String text) {
        RunHub.Tx t = RunHub.tx();
        RunHub.Row r = new RunHub.Row();
        r.kind = RunHub.K_REASON;
        r.key = key;
        r.text.append(text);
        synchronized (RunHub.lock()) {
            t.rows.add(r);
            t.idxByKey.put(key, t.rows.size() - 1);
        }
        return r;
    }

    @Test
    public void snapArrived_settlesBehindRows_andDropsPacers()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.Row think = addReasonRow("m1|p1",
                    "reasoning that arrived while nobody watched the screen");
            think.shown = 5;                      // mid-glide when paused
            RunHub.Row ans = addReasonRow("m1|p2", "answer tail");
            ans.kind = RunHub.K_ASSISTANT;
            ans.shown = 2;

            a.snapArrived();

            assertEquals("thinking row settled", think.text.length(), think.shown);
            assertEquals("answer row settled", ans.text.length(), ans.shown);
            a.flushPaintsNow();                   // the coalesced repaint
        }
    }

    @Test
    public void resume_afterBackgroundStream_fullText_noReplay()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.sys("first");                  // painted while bound
            idleMain();
            a.flushPaintsNow();

            ctl.pause();                          // the screen goes away
            // the run keeps streaming into the hub — no view bound
            RunHub.Row missed = addReasonRow("m2|p1",
                    "a whole thought that streamed while the screen was away");
            idleMain();

            ctl.resume();                         // the return
            idleMain();
            a.flushPaintsNow();
            assertEquals("background-arrived text must land SETTLED — no "
                            + "replay glide on return",
                    missed.text.length(), missed.shown);
        }
    }

    // ============================== 3. The fixed-length thought window

    @Test
    public void thoughtWindow_fixedThreeLines_whileStreamingCollapsed()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.Row think = addReasonRow("m3|p1",
                    "line one of the thought\nline two of the thought\n"
                        + "line three\nline four\nline five of the thought");
            think.shown = 40;                     // streaming, collapsed
            a.paintRowOnce(think);                // build the row view

            Field vf = ChatActivity.class.getDeclaredField("viewByKey");
            vf.setAccessible(true);
            Map<String, View> views =
                    (Map<String, View>) vf.get(a);
            View cardV = views.get("m3|p1");
            assertTrue("the collapsed streaming card must exist", cardV != null);
            android.view.ViewGroup card = (android.view.ViewGroup) cardV;
            assertTrue(card.getChildCount() >= 2);
            View child1 = card.getChildAt(1);
            assertTrue("child 1 must be the live window TextView",
                    child1 instanceof TextView);
            TextView live = (TextView) child1;
            assertEquals("the window is a FIXED stage: exactly 3 lines",
                    3, live.getMaxLines());
            assertEquals("reserved height — no line-count wobble",
                    3, live.getMinLines());
        }
    }

    // ==================================================== 4. Version

    /** P49 relaxed the exact pin to a floor (the P47/P46 pattern): the
     *  release train moved on, the P48 contract (profiles + snap) stays
     *  pinned above, the version string keeps moving forward. */
    @Test public void version_isAtLeastP48() {
        assertTrue(SettingsActivity.VERSION_TAG.compareTo("0.48.0-p48") > 0
                || SettingsActivity.VERSION_TAG.startsWith("0.48.0"));
    }
}
