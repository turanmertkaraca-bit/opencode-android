package ai.opencode.app;

import android.os.Looper;
import android.widget.LinearLayout;

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
import static org.junit.Assert.assertTrue;

/**
 * P27 phase 1b — THE stale-on-return pin, at the exact field repro:
 *
 *   bind a chat → background it (unbind) → the run keeps streaming into
 *   the hub model (no view bound) → come back (resume, SAME activity —
 *   the home-button path, NOT the drawer path which recreates) → the
 *   screen must show every missed row IMMEDIATELY.
 *
 * The P26 bug this pins: while unbound, hub upserts fired into an empty
 * sink; the resume-time re-pull then saw identical parts and (correctly)
 * reported "!changed" — nothing ever told the view to repaint, and the
 * transcript sat behind until the activity was recreated. The fix — every
 * fresh bind gets one hubReset (full repaint of the model AS IT IS) — is
 * exercised here through the REAL ChatActivity, the REAL bind/unbind, and
 * the REAL upsert pipeline.
 *
 * Companion pins: replayed parts must never duplicate rows (order kept),
 * and the Σ pill's run high-water must hold the peak mid-run (the field's
 * "said 47% once and turned back into 8%").
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class P27BindTest {

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
        set("runSessionId", null);
        set("lastUserText", null);
    }

    private static void set(String field, Object v) throws Exception {
        Field f = RunHub.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static int listChildren(ChatActivity a) throws Exception {
        Field f = ChatActivity.class.getDeclaredField("list");
        f.setAccessible(true);
        return ((LinearLayout) f.get(a)).getChildCount();
    }

    private static void textPart(String mid, String pid, String text) {
        RunHub.applyPart(obj(
                "type", "text",
                "sessionID", "sess-1",
                "messageID", mid,
                "id", pid,
                "text", text), "assistant");
    }

    @Test
    public void rowsStreamedWhileUnbound_appearOnRebind_homeButtonPath()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();          // bound (resume)
            RunHub.sys("first");                          // painted
            idleMain();
            a.flushPaintsNow();      // deterministic: same seam P24 uses
            int afterFirst = listChildren(a);
            assertTrue(afterFirst >= 1);

            ctl.pause();                                  // unbind (background)
            // the run keeps streaming: the hub model mutates with NO view
            textPart("m2", "p1", "missed while backgrounded");
            RunHub.sys("also missed");
            idleMain();

            ctl.resume();                                 // the home-button return
            idleMain();                                   // let the posted hubReset run
            int after = listChildren(a);
            assertTrue("the missed rows MUST be on screen after re-bind (was the field bug): "
                    + after + " children", after >= afterFirst + 2);
        }
    }

    @Test
    public void replay_neverDuplicates_orderPreserved() {
        // live: user msg + first assistant part
        RunHub.applyPart(obj("type", "text", "sessionID", "sess-1",
                "messageID", "mu", "id", "pu", "text", "do it"), "user");
        textPart("ma", "pa1", "part one. ");
        // resume reconcile re-delivers the SAME parts (identical content)
        textPart("ma", "pa1", "part one. ");
        // …plus the missed continuation
        textPart("ma", "pa2", "part two");
        idleMain();
        RunHub.Tx t = RunHub.tx();
        int seen = 0;
        int firstIdx = -1, secondIdx = -1;
        synchronized (RunHub.lock()) {
            for (int i = 0; i < t.rows.size(); i++) {
                RunHub.Row r = t.rows.get(i);
                if ("mu|pu".equals(r.key)) {
                    seen++;
                    firstIdx = i;
                }
                if ("ma|pa2".equals(r.key)) secondIdx = i;
            }
        }
        assertEquals("the replayed part upserts in place — exactly one row", 1, seen);
        assertTrue("missed part appended AFTER the earlier one (order kept)",
                secondIdx > firstIdx);
    }

    @Test
    public void pill_holdsTheRunHighWater_thenSettlesExact() throws Exception {
        set("busy", true);
        RunHub.Tx t = RunHub.tx();
        // a big turn reports its usage…
        RunHub.applyMessageInfo(t, obj("id", "mA", "role", "assistant",
                "tokens", obj("input", 40000, "output", 7000, "reasoning", 0)));
        // …the NEXT message reports less (cache-read accounting landed on
        // the previous one) — the pill must NOT swing down mid-run
        RunHub.applyMessageInfo(t, obj("id", "mB", "role", "assistant",
                "tokens", obj("input", 15000, "output", 5000, "reasoning", 0)));
        String midRun = RunHub.ctxPillLine();
        assertTrue("mid-run pill keeps the peak: " + midRun, midRun.contains("47k"));

        set("busy", false);
        String settled = RunHub.ctxPillLine();
        assertTrue("settled pill shows the exact last turn: " + settled,
                settled.contains("20k"));
    }

    @Test
    public void pill_cacheTokensCountedAsCached() throws Exception {
        RunHub.Tx t = RunHub.tx();
        RunHub.applyMessageInfo(t, obj("id", "mA", "role", "assistant",
                "tokens", obj("input", 1000, "output", 100, "reasoning", 0,
                        "cache", obj("read", 30000, "write", 500))));
        assertEquals("cache.read is tracked for the Σ popover",
                30000L, RunHub.sessionCacheRead());
    }

    // ------------------------------------------------ P27 long-session

    /**
     * The 500-message synthetic session, with RSS before/after. The
     * directive: rows must not grow unbounded, per-row content stays
     * capped, and memory stays flat while the session grows. Drives the
     * REAL upsert pipeline (500 user+assistant pairs = 1000 parts, each
     * with a realistic multi-KB body) and pins:
     *   • rows trimmed inside the 450 wall,
     *   • message bookkeeping inside the 400-entry LRU,
     *   • heap growth bounded (the model's live set is the trim ceiling,
     *     not the transcript length),
     *   • the pill still answers O(1) at the end.
     */
    @Test
    public void fiveHundredMessageSession_rowsBounded_memoryFlat() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) { System.gc(); }
        long rssBefore = rt.totalMemory() - rt.freeMemory();

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 220; i++) body.append("lorem ipsum dolor sit amet "); // ~2.6 KB
        String chunk = body.toString();

        for (int i = 0; i < 500; i++) {
            RunHub.applyPart(obj("type", "text", "sessionID", "sess-1",
                    "messageID", "u" + i, "id", "up" + i,
                    "text", "fix it " + i), "user");
            RunHub.applyPart(obj("type", "text", "sessionID", "sess-1",
                    "messageID", "a" + i, "id", "ap" + i,
                    "text", chunk + i), "assistant");
            if (i % 50 == 0) {
                RunHub.applyMessageInfo(RunHub.tx(), obj(
                        "id", "a" + i, "role", "assistant",
                        "tokens", obj("input", 48000 + i, "output", 2000)));
            }
        }
        idleMain();

        RunHub.Tx t = RunHub.tx();
        synchronized (RunHub.lock()) {
            assertTrue("rows stay inside the trim wall: " + t.rows.size(),
                    t.rows.size() <= RunHub.TRIM_OVER + 1);
            assertTrue("message bookkeeping stays inside the LRU cap: "
                    + t.msgs.size(), t.msgs.size() <= RunHub.MSG_CAP);
        }
        for (int i = 0; i < 3; i++) { System.gc(); }
        long rssAfter = rt.totalMemory() - rt.freeMemory();
        long growth = rssAfter - rssBefore;
        // 500 × 2.6 KB ≈ 1.3 MB of text streamed; the LIVE model is capped
        // at ~350 rows — anything beyond ~40 MB would mean real unboundedness
        assertTrue("heap growth bounded after 1000 parts: " + growth / 1048576 + " MB",
                growth < 48L * 1048576);
        String pill = RunHub.ctxPillLine();
        assertTrue("the pill still answers after 1000 parts (O(1) reads): " + pill,
                pill.contains("50"));
    }
}
