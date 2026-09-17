package ai.opencode.app;

import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P47 — the "the live token was on the wire all along" release, pinned
 * where each piece lives.
 *
 * GROUND TRUTH DRIVING THIS RELEASE (mock streaming provider against a
 * real opencode v1.18.25 server, timestamped /event probe): the feed
 * publishes message.part.updated snapshots only at part BOUNDARIES
 * (created-empty → final), while every provider delta rides a separate
 * message.part.delta frame {sessionID, messageID, partID, field:"text",
 * delta} in real time. The app consumed only the snapshots — the screen
 * showed nothing while the model spoke and the whole reply materialized
 * at once ("appears instantly" — the field report on v0.46.0). P47
 * consumes the deltas: RunHub.applyDelta appends them to the row keyed
 * exactly like applyPart keys parts, the governor keeps throttling only
 * the boundary snapshots (deltas pass through untouched), and the chat
 * paints the growth in place instead of rebuilding the row per token.
 * The git shim now also tunes fresh FUSE-worktree repos (filemode /
 * trustctime / checkstat / untrackedcache / fsmonitor / gc.auto) so the
 * agent's follow-up git work stops fighting stat jitter.
 */
@RunWith(RobolectricTestRunner.class)
public class P47Test {

    // ============================== 1. The delta wire frame

    /** Exactly what opencode v1.18.25 put on the wire (captured raw by
     *  the probe): flat properties, partID — not id — plus field/delta. */
    private static Map<String, Object> deltaEvent(String sid, String mid,
                                                  String pid, String delta) {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("sessionID", sid);
        props.put("messageID", mid);
        props.put("partID", pid);
        props.put("field", "text");
        props.put("delta", delta);
        Map<String, Object> ev = new java.util.LinkedHashMap<>();
        ev.put("id", "evt_test");
        ev.put("type", "message.part.delta");
        ev.put("properties", props);
        return ev;
    }

    // ============================== 2. Governor: deltas pass through

    @Test public void governor_deltaFramesPassThroughInstantly() {
        PartGovernor g = new PartGovernor();
        String raw = "{\"type\":\"message.part.delta\",\"properties\":{"
                + "\"sessionID\":\"s1\",\"messageID\":\"m1\","
                + "\"partID\":\"prt_1\",\"field\":\"text\",\"delta\":\"tok \"}}";
        List<String> out = g.offer(raw, 1000);
        assertEquals("delta must be released immediately", 1, out.size());
        assertEquals(raw, out.get(0));
        assertEquals("delta must never be held", 0, g.pendingCount());
        assertEquals("delta must not consume a snapshot apply slot",
                0, g.appliedCount());
    }

    @Test public void governor_deltaNeverMatchesTheSnapshotScan() {
        // the REAL delta shape (captured raw by the probe): field/delta,
        // no nested "type":"text" — the snapshot scan finds no part type
        // and bails to null even if it were pointed at a delta frame;
        // the production path never does (offer gates partKey by the
        // message.part.updated type — pinned by the passthrough test)
        String raw = "{\"type\":\"message.part.delta\",\"properties\":{"
                + "\"sessionID\":\"s1\",\"messageID\":\"m1\","
                + "\"partID\":\"prt_1\",\"field\":\"text\",\"delta\":\"x\"}}";
        assertNull(PartGovernor.partKey(raw, "message.part.delta".length()));
        assertEquals("message.part.delta", PartGovernor.envelopeType(raw));
    }

    @Test public void governor_snapshotsStillThrottleAroundDeltaStream() {
        // deltas in, snapshots interleaved: snapshots hold/throttle,
        // deltas stream, the final snapshot force-lands on idle
        PartGovernor g = new PartGovernor();
        String snap = "{\"type\":\"message.part.updated\",\"properties\":{\"part\":{"
                + "\"id\":\"prt_1\",\"sessionID\":\"s1\",\"messageID\":\"m1\","
                + "\"type\":\"text\",\"text\":\"body\"}}}";
        String delta = "{\"type\":\"message.part.delta\",\"properties\":{"
                + "\"sessionID\":\"s1\",\"messageID\":\"m1\","
                + "\"partID\":\"prt_1\",\"field\":\"text\",\"delta\":\"d\"}}";
        assertNotNull(g.offer(snap, 0).size());                 // first applies
        g.offer(delta, 10);                                     // passes
        assertTrue(g.offer(snap, 100).isEmpty());               // held
        assertEquals(1, g.offer(delta, 110).size());            // still passes
        g.noteType("session.idle");                             // the run ended
        List<String> flushed = g.drain(120, false);
        assertEquals("held snapshot must land at the boundary",
                1, flushed.size());
    }

    // ============================== 3. RunHub.applyDelta

    /** Fresh transcript per test — RunHub state is static by design. */
    private static RunHub.Tx freshCur() throws Exception {
        RunHub.Tx t = RunHub.tx();
        t.rows.clear();
        t.idxByKey.clear();
        t.msgs.clear();
        t.typeCount.clear();
        t.trimmedKeys.clear();
        Field f = RunHub.class.getDeclaredField("sessionId");
        f.setAccessible(true);
        f.set(null, null);
        return t;
    }

    @Test public void applyDelta_appendsToTheExistingRow() throws Exception {
        RunHub.Tx t = freshCur();
        // a reasoning row (or an earlier snapshot) made the row first;
        // deltas grow it in place, never duplicate it
        RunHub.upsertReason(t, "m1|prt_1", "m1", "Hel");
        assertTrue(RunHub.rowIn(t, "m1|prt_1") != null);
        RunHub.applyDelta(null, "m1", "prt_1", "lo ");
        RunHub.applyDelta(null, "m1", "prt_1", "wor");
        RunHub.applyDelta(null, "m1", "prt_1", "ld");
        assertEquals("one row, grown in place", 1, t.rows.size());
        assertEquals("Hello world", t.rows.get(0).text.toString());
        assertEquals("kind is preserved — the delta only grows text",
                RunHub.K_REASON, t.rows.get(0).kind);
    }

    @Test public void applyDelta_createsTheRowOnDemand() throws Exception {
        RunHub.Tx t = freshCur();
        // an SSE reconnect swallowed the created-empty frame: the first
        // delta must still materialize the run, never hide it
        RunHub.applyDelta(null, "m1", "prt_9", "tok");
        RunHub.Row r = RunHub.rowIn(t, "m1|prt_9");
        assertNotNull("missing row must be created", r);
        assertEquals("tok", r.text.toString());
        assertEquals(RunHub.K_ASSISTANT, r.kind);
    }

    @Test public void applyDelta_ignoresUserParts() throws Exception {
        RunHub.Tx t = freshCur();
        RunHub.MsgInfo mi = new RunHub.MsgInfo();
        mi.role = "user";
        t.msgs.put("m1", mi);
        RunHub.applyDelta(null, "m1", "prt_1", "nope");
        assertEquals("user parts never stream", 0, t.rows.size());
    }

    @Test public void applyDelta_ignoresSnapshotOwnedRows() throws Exception {
        RunHub.Tx t = freshCur();
        RunHub.upsertTool(t, RunHub.toolRow("m1|prt_2",
                obj("tool", "bash", "state", obj("status", "running", "title", "ls"))));
        RunHub.applyDelta(null, "m1", "prt_2", "junk");
        assertEquals(0, RunHub.rowIn(t, "m1|prt_2").text.length());
    }

    @Test public void applyDelta_blankDeltaIsANoOp() throws Exception {
        RunHub.Tx t = freshCur();
        RunHub.applyDelta(null, "m1", "prt_1", "");
        RunHub.applyDelta(null, "m1", null, "x");   // missing partID
        RunHub.applyDelta(null, null, "prt_1", "x"); // missing messageID
        assertEquals("nothing may materialize", 0, t.rows.size());
    }

    @Test public void onEvent_routesDeltaFramesToTheRow() throws Exception {
        RunHub.Tx t = freshCur();
        RunHub.upsertText(t, "m1|prt_1", "m1", "Hel");
        RunHub.HUB.onEvent(deltaEvent(null, "m1", "prt_1", "lo world"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Hello world", RunHub.rowIn(t, "m1|prt_1").text.toString());
    }

    @Test public void onEvent_ignoresNonTextDeltaFields() throws Exception {
        RunHub.Tx t = freshCur();
        RunHub.upsertText(t, "m1|prt_1", "m1", "keep");
        Map<String, Object> ev = deltaEvent(null, "m1", "prt_1", "nope");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) ev.get("properties");
        props.put("field", "metadata");
        RunHub.HUB.onEvent(ev);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("keep", RunHub.rowIn(t, "m1|prt_1").text.toString());
    }

    // ================== 4. The full turn: snapshot → deltas → snapshot

    @Test public void fullTurn_snapshotsReconcileAroundTheDeltaStream() {
        RunHub.Tx t = new RunHub.Tx();
        String key = "m1|prt_1";
        // the created-empty snapshot carries text:"" — the P37 blank rule
        // means no text row exists yet; the FIRST DELTA is what
        // materializes the run on screen
        RunHub.upsertText(t, key, "m1", "");
        assertNull(RunHub.rowIn(t, key));
        // the live delta stream
        String[] toks = {"Think", "ing", " about", " it", ".", " Then", " the",
                " answer", ":", " forty", "-", "two", ".", " Done", "."};
        for (String s : toks) assertTrue(RunHub.deltaAppendTx(t, key, "m1", s));
        RunHub.Row r = RunHub.rowIn(t, key);
        assertEquals("Thinking about it. Then the answer: forty-two. Done.",
                r.text.toString());
        // a stale boundary snapshot (prefix of what the deltas grew)
        RunHub.upsertText(t, key, "m1", "Thinking");
        assertEquals("stale snapshot must never roll the row back",
                "Thinking about it. Then the answer: forty-two. Done.",
                r.text.toString());
        // the final authoritative snapshot (superset → adopted)
        RunHub.upsertText(t, key, "m1",
                "Thinking about it. Then the answer: forty-two. Done. ✔");
        assertEquals("final snapshot lands exactly once",
                "Thinking about it. Then the answer: forty-two. Done. ✔",
                r.text.toString());
        assertEquals(1, t.rows.size());
    }

    // ============================== 5. Git shim: FUSE repo tuning

    @Test public void gitShim_tunesFreshFuseRepos() throws Exception {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        Shims.ensure(c);
        java.io.File f = new java.io.File(Shims.shimsDir(c), "git");
        String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        // the tuning block rides the same FUSE+separate-git-dir branch
        assertTrue(s.contains("core.filemode false"));
        assertTrue(s.contains("core.trustctime false"));
        assertTrue(s.contains("core.checkstat minimal"));
        assertTrue(s.contains("core.untrackedcache false"));
        assertTrue(s.contains("core.fsmonitor false"));
        assertTrue(s.contains("gc.auto 0"));
        // clone must finish before tuning (no exec before the stamps)
        assertTrue(s.contains("--separate-git-dir=\"$GD\" || exit $?"));
        // mksh safety: no line may continue an operator
        for (String line : s.split("\n")) {
            String t = line.trim();
            assertFalse("mksh-unsafe line: " + line,
                    t.startsWith("&&") || t.startsWith("||"));
        }
    }

    // ============================== 6. Version

    @Test public void version_isP47() {
        assertEquals("0.47.0-p47", SettingsActivity.VERSION_TAG);
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
