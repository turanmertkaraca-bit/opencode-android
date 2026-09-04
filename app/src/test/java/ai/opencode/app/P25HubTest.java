package ai.opencode.app;

import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P25 — RunHub pipeline pins, on the JVM via Robolectric: the transcript
 * the chat renders must be the SAME object the hub mutates, upserts must
 * keep the exact merge/changed semantics the field iterated on, the send
 * ladder must keep its variant order, and a process kill mid-run must
 * leave an honest interrupted note (and never a wedged busy flag).
 */
@RunWith(RobolectricTestRunner.class)
public class P25HubTest {

    /** RunHub state is static by design (ServerService style) — every
     *  test starts from a clean transcript so pins never lean on order. */
    @Before
    public void resetHub() throws Exception {
        RunHub.Tx t = RunHub.tx();
        t.rows.clear();
        t.idxByKey.clear();
        t.msgs.clear();
        t.typeCount.clear();
        t.trimmedKeys.clear();
        t.lastAssistantTok = 0;
        set("sessionId", null);
        set("sessionTitle", null);
        set("busy", false);
        set("runSessionId", null);
        set("interruptedNotePending", false);
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

    // ------------------------------------------------------- view = hub

    @Test
    public void activityRows_areTheHubRows_sameObject() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            Field f = ChatActivity.class.getDeclaredField("rows");
            f.setAccessible(true);
            assertSameInstance(f.get(a), RunHub.rows());
            Field lf = ChatActivity.class.getDeclaredField("lock");
            lf.setAccessible(true);
            assertSameInstance(lf.get(a), RunHub.lock());
        }
    }

    private static void assertSameInstance(Object expected, Object actual) {
        assertTrue("expected the SAME instance", expected == actual);
    }

    // ------------------------------------------------------- sys notes

    @Test
    public void hubSys_landsInTheTranscriptAndRenders() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.sys("hello from the hub");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            List<RunHub.Row> rows = RunHub.rows();
            assertEquals(1, rows.size());
            assertEquals(RunHub.K_SYS, rows.get(0).kind);
            assertTrue(rows.get(0).text.toString().contains("hello from the hub"));
        }
    }

    // ---------------------------------------------------- upsert pipeline

    @Test
    public void upsertText_growsWithoutDuplication() {
        RunHub.Tx t = new RunHub.Tx();
        RunHub.upsertText(t, "m1|p1", "m1", "Hel");
        RunHub.upsertText(t, "m1|p1", "m1", "Hello world");
        assertEquals(1, t.rows.size());
        assertEquals("Hello world", t.rows.get(0).text.toString());
        assertEquals(RunHub.K_ASSISTANT, t.rows.get(0).kind);
    }

    @Test
    public void upsertText_truncatedEchoIsIgnored() {
        RunHub.Tx t = new RunHub.Tx();
        RunHub.upsertText(t, "m1|p1", "m1", "Hello world");
        RunHub.upsertText(t, "m1|p1", "m1", "Hello");   // stale echo
        assertEquals("Hello world", t.rows.get(0).text.toString());
    }

    @Test
    public void upsertTool_nullStatusChangeStillCounts() {
        // P24's crash class: a null status arriving over a non-null one
        // must count as CHANGED (Objects.equals), never NPE the add path.
        // The incoming null is adopted as "" (toolRow normalizes); the pin
        // is that the upsert DETECTS the change and never throws.
        RunHub.Tx t = new RunHub.Tx();
        RunHub.Row first = RunHub.toolRow("m1|p1",
                obj("tool", "bash", "state", obj("status", "running", "title", "ls")));
        RunHub.upsertTool(t, first);
        RunHub.Row second = RunHub.toolRow("m1|p1",
                obj("tool", "bash", "state", obj("status", null, "title", "ls")));
        RunHub.upsertTool(t, second);
        assertEquals("", t.rows.get(0).status);   // null adopted, no NPE
    }

    @Test
    public void upsertTool_errorStatusOpensTheCard() {
        RunHub.Tx t = new RunHub.Tx();
        RunHub.upsertTool(t, RunHub.toolRow("m1|p1",
                obj("tool", "bash", "state",
                        obj("status", "error", "title", "boom",
                            "error", obj("message", "exit 1")))));
        assertTrue("failures are never hidden collapsed", t.rows.get(0).open);
        assertTrue(t.rows.get(0).output.toString().contains("✕ exit 1"));
    }

    @Test
    public void applyPart_keysByMessageAndPartId() {
        RunHub.Tx t = new RunHub.Tx();
        RunHub.applyPart(obj("type", "text", "messageID", "m9", "id", "p3",
                "text", "streamed"), "assistant");
        // goes to the hub's cur (no session) — find the row by key shape
        RunHub.Row r = RunHub.rowIn(RunHub.tx(), "m9|p3");
        assertTrue(r != null && r.text.toString().contains("streamed"));
        // and the passed Tx stays untouched (routing sanity)
        assertEquals(0, t.rows.size());
    }

    // ------------------------------------------------------- message info

    @Test
    public void applyMessageInfo_tracksDepthInputAndSessionSums() {
        Map<String, Object> info = obj(
                "id", "mA", "role", "assistant", "sessionID", (Object) null,
                "tokens", obj("input", 40_000, "output", 2_000, "reasoning", 1_000,
                        "cache", obj("read", 3_000, "write", 1_000)),
                "cost", 0.0123);
        info.remove("sessionID");          // live replay shape: no session on info
        // into the CURRENT transcript — the same one the pill sums
        RunHub.applyMessageInfo(RunHub.tx(), info);
        assertEquals("input+output+reasoning+cache read+write",
                47_000L, RunHub.tx().lastAssistantTok);
        assertEquals(0.0123, RunHub.sessionCost() + 0.0, 1e-9);
        assertEquals(47_000L, RunHub.sessionTok());
    }

    // ------------------------------------------------------- send ladder

    @Test
    public void buildBodies_keepsTheVariantOrder() {
        List<String> b = RunHub.buildBodies("hi",
                new String[]{"zen", "free-a"}, "build");
        assertEquals(4, b.size());
        assertTrue(b.get(0).startsWith("{\"model\":"));
        assertTrue(b.get(0).contains("\"agent\":\"build\""));   // ma: model+agent
        assertTrue(b.get(1).startsWith("{\"model\":"));
        assertFalse("m variant drops the agent", b.get(1).contains("\"agent\""));
        assertTrue(b.get(2).startsWith("{\"agent\":"));          // a: agent only
        assertTrue(b.get(3).startsWith("{\"parts\":"));          // bare last
        assertTrue(b.get(0).contains("\"text\":\"hi\""));
    }

    @Test
    public void buildBodies_withoutSelection_hasNoModelVariants() {
        List<String> b = RunHub.buildBodies("hi", null, "plan");
        assertEquals(2, b.size());
        assertTrue(b.get(0).startsWith("{\"agent\":"));
        assertTrue(b.get(0).contains("\"agent\":\"plan\""));
        assertTrue(b.get(1).startsWith("{\"parts\":"));
    }

    @Test
    public void buildImageBodies_carriesTextAndFileParts() {
        List<String> b = RunHub.buildImageBodies("look",
                "data:image/jpeg;base64,AAA", null, "build");
        assertEquals(2, b.size());
        assertTrue(b.get(0).contains("\"type\":\"file\""));
        assertTrue(b.get(0).contains("\"mime\":\"image/jpeg\""));
        assertTrue(b.get(0).contains("\"text\":\"look\""));
    }

    // ------------------------------------------------- failure phrasing

    @Test
    public void classificationHelpers_keepTheirFieldPhrasings() {
        assertTrue(RunHub.isModelNotFound(
                "Model not found: zen/x. Did you mean: …"));
        assertTrue(RunHub.isModelNotFound("ProviderModelNotFoundError"));
        assertFalse(RunHub.isModelNotFound("rate limited"));
        assertTrue(RunHub.isStreamFlake("upstream idle timeout"));
        assertTrue(RunHub.isKeyError("401 Unauthorized"));
        assertTrue(RunHub.isKeyError("credit balance too low"));
        assertFalse(RunHub.isKeyError("all good"));
    }

    // ---------------------------------------------- run-state persistence

    @Test
    public void runState_jsonRoundTrip() {
        RunHub.RunState s = new RunHub.RunState();
        s.sid = "ses_123";
        s.title = "Fix the bug";
        s.user = "please fix it";
        s.busy = true;
        s.ts = 1234L;
        RunHub.RunState back = RunHub.rsFromJson(RunHub.rsToJson(s));
        assertEquals("ses_123", back.sid);
        assertEquals("Fix the bug", back.title);
        assertEquals("please fix it", back.user);
        assertTrue(back.busy);
        assertEquals(1234L, back.ts);
    }

    @Test
    public void runState_nullsAndGarbageSurvive() {
        // rsToJson(null) is the empty object; parsing it yields an empty
        // (usable) state — never a crash, never a half-initialized id.
        RunHub.RunState empty = RunHub.rsFromJson(RunHub.rsToJson(null));
        assertTrue(empty != null && empty.sid == null && !empty.busy);
        assertNull(RunHub.rsFromJson("not json at all"));
        // a wrong-typed sid parses but yields no usable id (Json.str = null)
        RunHub.RunState back = RunHub.rsFromJson("{\"sid\": 12}");
        assertTrue(back != null && back.sid == null);
    }

    @Test
    public void interruptedNote_firesOnceThenGoesQuiet() throws Exception {
        Field f = RunHub.class.getDeclaredField("interruptedNotePending");
        f.setAccessible(true);
        f.setBoolean(null, true);
        String note = RunHub.consumeInterruptedNote();
        assertTrue(note, note.contains("interrupted"));
        assertTrue(note, note.contains("history is intact"));
        assertNull("one-shot: never twice", RunHub.consumeInterruptedNote());
    }

    // ---------------------------------------------------- merge semantics

    @Test
    public void mergeText_adoptGrowthIgnoreEcho() {
        assertEquals("abcdef", RunHub.mergeText("", "abcdef"));
        assertEquals("abcdef", RunHub.mergeText("abc", "abcdef"));   // grew
        assertEquals("abcdef", RunHub.mergeText("abcdef", "abc"));   // echo
        assertEquals("xyz", RunHub.mergeText("abc", "xyz"));         // replace
        assertEquals("same", RunHub.mergeText("same", "same"));
    }
}
