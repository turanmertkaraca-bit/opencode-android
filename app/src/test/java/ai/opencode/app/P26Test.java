package ai.opencode.app;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P26 — the evergreen pins: the app must behave the same on day 300 of a
 * run as on day one. Every growth path the audit found (per-message
 * bookkeeping, pid-less part counters, edit-focus snippets, paint-fault
 * maps) now carries a hard cap, and the session totals move by DELTA so
 * they survive eviction exactly. The suite pins: sums survive the cap,
 * corrections move totals by the delta, stable-key parts no longer grow
 * the counter map, the focus map evicts its eldest, the bind-time replay
 * plants a retry flag when the server is silent, and a 12k-part soak
 * stays inside every wall.
 */
@RunWith(RobolectricTestRunner.class)
public class P26Test {

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
            t.costSum = 0;
            t.tokSum = 0;
        }
        set("sessionId", null);
        set("sessionTitle", null);
        set("busy", false);
        set("runSessionId", null);
        set("interruptedNotePending", false);
        set("replayNeeded", false);
        synchronized (editFocus()) { editFocus().clear(); }
    }

    private static void set(String field, Object v) throws Exception {
        Field f = RunHub.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> editFocus() throws Exception {
        Field f = RunHub.class.getDeclaredField("editFocus");
        f.setAccessible(true);
        return (Map<String, String>) f.get(null);
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // --------------------------------------------- P26 running session sums

    @Test
    public void sessionSums_surviveMsgEviction() {
        // more messages than MSG_CAP — the oldest MsgInfo entries are
        // evicted, but the session totals must still count every one
        int n = RunHub.MSG_CAP + 100;
        for (int i = 0; i < n; i++) {
            Map<String, Object> info = obj(
                    "id", "m" + i, "role", "assistant",
                    "tokens", obj("input", 10, "output", 5),
                    "cost", 0.001);
            RunHub.applyMessageInfo(RunHub.tx(), info);
        }
        assertEquals("every message's cost counted, evicted or not",
                n * 0.001, RunHub.sessionCost(), 1e-9);
        assertEquals("every message's tokens counted, evicted or not",
                (long) n * 15L, RunHub.sessionTok());
        synchronized (RunHub.lock()) {
            assertTrue("bookkeeping stays under the cap",
                    RunHub.tx().msgs.size() <= RunHub.MSG_CAP);
            assertEquals("depth input = the LAST assistant message",
                    15L, RunHub.tx().lastAssistantTok);
        }
    }

    @Test
    public void sessionSums_correctionMovesByDelta() {
        // the server re-reports the same message with a corrected cost —
        // the total must move by the DELTA, never double-count
        Map<String, Object> info = obj("id", "mA", "role", "assistant",
                "tokens", obj("input", 100), "cost", 0.5);
        RunHub.applyMessageInfo(RunHub.tx(), info);
        assertEquals(0.5, RunHub.sessionCost(), 1e-9);
        Map<String, Object> fixed = obj("id", "mA", "role", "assistant",
                "tokens", obj("input", 100), "cost", 0.2);
        RunHub.applyMessageInfo(RunHub.tx(), fixed);
        assertEquals(0.2, RunHub.sessionCost(), 1e-9);
        assertEquals(100L, RunHub.sessionTok());
        // and a different message adds on top
        RunHub.applyMessageInfo(RunHub.tx(), obj("id", "mB", "role", "assistant",
                "tokens", obj("input", 50), "cost", 0.1));
        assertEquals(0.3, RunHub.sessionCost(), 1e-9);
        assertEquals(150L, RunHub.sessionTok());
    }

    // ------------------------------------------------- P26 counter gating

    @Test
    public void stableKeyParts_noLongerGrowTheCounterMap() {
        // real v1.18.25 traffic carries a part id — the disambiguation
        // counter has nothing to do and must stay EMPTY on a long run
        for (int i = 0; i < 200; i++) {
            RunHub.applyPart(obj(
                    "type", "text", "sessionID", (Object) null,
                    "messageID", "msg" + (i / 2), "id", "prt" + i,
                    "text", "chunk " + i), "assistant");
        }
        synchronized (RunHub.lock()) {
            assertTrue("stable-key parts never touch typeCount",
                    RunHub.tx().typeCount.isEmpty());
        }
    }

    @Test
    public void pidlessParts_stillGetDistinctKeys_andStayCapped() {
        for (int i = 0; i < 50; i++) {
            RunHub.applyPart(obj(
                    "type", "text", "sessionID", (Object) null,
                    "messageID", "solo", "text", "chunk " + i), "assistant");
        }
        synchronized (RunHub.lock()) {
            assertEquals("50 pid-less parts of one message = 50 rows",
                    50, RunHub.tx().rows.size());
            assertTrue(RunHub.tx().typeCount.size() <= RunHub.TYPE_COUNT_CAP);
        }
        // and the hard cap holds under a flood
        for (int i = 0; i < RunHub.TYPE_COUNT_CAP + 500; i++) {
            RunHub.applyPart(obj(
                    "type", "text", "sessionID", (Object) null,
                    "messageID", "flood" + i, "text", "x"), "assistant");
        }
        synchronized (RunHub.lock()) {
            assertTrue("counter map stays capped",
                    RunHub.tx().typeCount.size() <= RunHub.TYPE_COUNT_CAP);
        }
    }

    // ------------------------------------------------- P26 focus map cap

    @Test
    public void editFocus_evictsItsEldest() throws Exception {
        Map<String, String> focus = editFocus();
        for (int i = 0; i < RunHub.EDIT_FOCUS_CAP + 60; i++) {
            synchronized (focus) { focus.put("/p/file" + i + ".ts", "snippet " + i); }
        }
        synchronized (focus) {
            assertTrue("focus map never exceeds the cap",
                    focus.size() <= RunHub.EDIT_FOCUS_CAP);
            // eldest evicted: the very first file is gone…
            assertFalse(focus.containsKey("/p/file0.ts"));
            // …the freshest is still here
            assertNotNull(focus.get("/p/file" + (RunHub.EDIT_FOCUS_CAP + 59) + ".ts"));
        }
    }

    // ------------------------------------------------- P26 replay retry

    @Test
    public void replayFlagsRetryWhenServerIsSilent() throws Exception {
        // no server on the loopback: the bind-time pull cannot reach it —
        // the OLD code returned silently (the stale-chat field bug); the
        // new code plants replayNeeded for the healthy flip to retry.
        set("sessionId", "sess-boot-race");
        assertFalse(RunHub.replayPending());
        RunHub.reconcileOnBind();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!RunHub.replayPending() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue("a failed re-pull must arm the healthy-flip retry",
                RunHub.replayPending());
    }

    // ------------------------------------------------- P26 try-anyway pick

    @Test
    public void keepPick_pureRule() {
        // the rule the sheet + validateSelectedModel now follow:
        assertFalse("a dead, un-forced pick is cleared pre-send",
                RunHub.keepPick(false, false));
        assertTrue("a server-listed pick is kept", RunHub.keepPick(false, true));
        assertTrue("a forced catalog pick is tried anyway",
                RunHub.keepPick(true, false));
        assertTrue(RunHub.keepPick(true, true));
    }

    @Test
    public void forcedFlag_roundTripsThroughPrefs() {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        Models.save(c, "opencode-go", "glm-5", false);
        assertFalse(Models.forced(c));
        Models.save(c, "opencode-go", "glm-5", true);
        assertTrue(Models.forced(c));
        assertEquals("glm-5", Models.selected(c)[1]);
        Models.clear(c);
        assertFalse(Models.forced(c));
        assertEquals(null, Models.selected(c));
    }

    // ------------------------------------------------- P26 project switch

    @Test
    public void projectSwitch_resetsTheHubOnce() throws Exception {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        RunHub.init(c);                      // appCtx for saveRunState
        // a session "in flight" for project A
        set("sessionId", "sess-A");
        set("sessionTitle", "A chat");
        RunHub.sys("row belonging to project A");
        synchronized (RunHub.lock()) { assertEquals(1, RunHub.tx().rows.size()); }
        // the server comes up for project A (same as any boot) → no reset
        RunHub.onProjectRoot("/storage/emulated/0/opencode-projects/a");
        assertEquals("same-root server start keeps the session",
                "sess-A", RunHub.sessionId());
        // the deck switches to project B: a NEW server owns the sandbox —
        // the hub must reset (the old session 404s there forever otherwise)
        RunHub.onProjectRoot("/storage/emulated/0/opencode-projects/b");
        long deadline = System.currentTimeMillis() + 10_000;
        while (RunHub.sessionId() != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("switch must clear the foreign session", null, RunHub.sessionId());
        assertEquals("fresh chat title", "New chat", RunHub.sessionTitle());
        synchronized (RunHub.lock()) {
            assertEquals("transcript starts empty", 0, RunHub.tx().rows.size());
        }
        assertFalse("no retry armed against a server that never had sess-A",
                RunHub.replayPending());
        // idempotent: more servers for B change nothing
        RunHub.onProjectRoot("/storage/emulated/0/opencode-projects/b");
        assertEquals(null, RunHub.sessionId());
    }

    // ------------------------------------------------- P26 the long soak

    /**
     * The month/year simulation: 4,000 messages × 3 parts (12k upserts)
     * flood one session. Every structure the audit flagged must end the
     * soak INSIDE its wall, and the session sums must still be exact —
     * the same behavior on day 300 as on minute one.
     */
    @Test
    public void longRun_soak_twelveThousandParts_staysBounded() {
        int msgs = 4_000;
        double perMsgCost = 0.0025;
        for (int i = 0; i < msgs; i++) {
            String mid = "soak-" + i;
            RunHub.applyMessageInfo(RunHub.tx(), obj(
                    "id", mid, "role", "assistant",
                    "tokens", obj("input", 300, "output", 40),
                    "cost", perMsgCost));
            for (int p = 0; p < 3; p++) {
                RunHub.applyPart(obj(
                        "type", p == 2 ? "reasoning" : "text",
                        "sessionID", (Object) null,
                        "messageID", mid, "id", mid + "-p" + p,
                        "text", "soak chunk " + i + "/" + p), "assistant");
            }
        }
        RunHub.Tx t = RunHub.tx();
        synchronized (RunHub.lock()) {
            assertTrue("rows stay inside the trim wall ("
                            + t.rows.size() + ")",
                    t.rows.size() <= RunHub.TRIM_OVER + 1);
            assertTrue("message bookkeeping capped",
                    t.msgs.size() <= RunHub.MSG_CAP);
            assertTrue("trimmed-key memory capped",
                    t.trimmedKeys.size() <= 4096);
            assertTrue("counter map empty on stable-key traffic",
                    t.typeCount.isEmpty());
        }
        assertEquals("sums survive the whole soak",
                msgs * perMsgCost, RunHub.sessionCost(), 1e-9);
        assertEquals(msgs * 340L, RunHub.sessionTok());
        assertEquals("depth meter reads the LAST message", 340L,
                t.lastAssistantTok);
    }
}
