package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.opencode.app.CompactionPoison.Msg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P40 — the source-cure release, pinned where each piece lives:
 *
 * 1. THE ROOT CAUSE, now proven on the rig (real opencode 1.18.25 under
 *    emulation): a compaction whose summarizer returns an EMPTY reply
 *    still writes its root row (assistant, agent "compaction") — and the
 *    model's payload assembles FROM that root. Every later send is
 *    system + dangling prompt + the current turn: flat tokens, and the
 *    model answers as if the chat were fresh, while GET still paints the
 *    whole history. Kills, restarts, and re-summarizing were proven NOT
 *    to cause or cure it — re-summarize inherits the poisoned root.
 * 2. THE DETECTOR (pure, this suite): parses the raw message array the
 *    hub already pulls, finds the last compaction root, and calls poison
 *    only when that root's text never landed while real turns exist
 *    before it. The trap is pinned: USER messages carry their own
 *    `summary` metadata (git diffs) at the API level too — the root
 *    marker is agent "compaction" on an assistant row, nothing else.
 * 3. THE CURE PLAN (pure): victim ids = every EMPTY root + its trigger
 *    prompt; healthy roots and real turns are never touched; the SQL is
 *    bound-parameter DELETEs, parts before messages, batched under the
 *    999-parameter wall. The device executor stops the server, runs the
 *    plan, restarts, and verifies through the same API the model reads.
 * 4. THE BRIDGE: while a session is diagnosed-and-not-yet-cured, the
 *    P39 Context repair note rides its sends — one bridge, not a tax.
 */
@RunWith(RobolectricTestRunner.class)
public class P40Test {

    // ------------------------------------------------ fixtures (real shapes)

    /** A user turn, exactly as GET /session/{id}/message returns it —
     *  including the `summary` diffs metadata that must never read as a
     *  compaction root. */
    private static String userTurn(String id, String text) {
        return "{\"info\":{\"id\":\"" + id + "\",\"sessionID\":\"ses_x\","
                + "\"role\":\"user\",\"time\":{\"created\":1789139275988},"
                + "\"summary\":{\"diffs\":[]}},"
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    /** An assistant turn (agent build) with a text part. */
    private static String asstTurn(String id, String parent, String text) {
        return "{\"info\":{\"id\":\"" + id + "\",\"sessionID\":\"ses_x\","
                + "\"role\":\"assistant\",\"parentID\":\"" + parent + "\","
                + "\"mode\":\"build\",\"agent\":\"build\","
                + "\"tokens\":{\"total\":112,\"input\":107,\"output\":5,"
                + "\"reasoning\":0,\"cache\":{\"read\":0,\"write\":0}},"
                + "\"finish\":\"stop\"},"
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    /** The compaction root an empty summarizer leaves behind: agent
     *  "compaction", text part present but EMPTY. */
    private static String emptyRoot(String id, String parent) {
        return "{\"info\":{\"id\":\"" + id + "\",\"sessionID\":\"ses_x\","
                + "\"role\":\"assistant\",\"parentID\":\"" + parent + "\","
                + "\"mode\":\"compaction\",\"agent\":\"compaction\","
                + "\"finish\":\"stop\"},"
                + "\"parts\":[{\"type\":\"text\",\"text\":\"\"}]}";
    }

    /** A healthy compaction root: the summary actually carries text. */
    private static String healthyRoot(String id, String parent, String text) {
        return "{\"info\":{\"id\":\"" + id + "\",\"sessionID\":\"ses_x\","
                + "\"role\":\"assistant\",\"parentID\":\"" + parent + "\","
                + "\"mode\":\"compaction\",\"agent\":\"compaction\","
                + "\"finish\":\"stop\"},"
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    /** The trigger prompt the summarize flow writes before its root —
     *  its text is the summarizer's question, its agent is "build". */
    private static String triggerPrompt(String id, String text) {
        return "{\"info\":{\"id\":\"" + id + "\",\"sessionID\":\"ses_x\","
                + "\"role\":\"user\",\"time\":{\"created\":1789139354800},"
                + "\"agent\":\"build\"},"
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    private static List<Msg> parse(String... items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) b.append(',');
            b.append(items[i]);
        }
        return CompactionPoison.parse(Json.arr(Json.parse(b.append("]").toString())));
    }

    private static List<String> ids(List<Msg> msgs) {
        List<String> out = new ArrayList<>();
        for (Msg m : msgs) out.add(m.id);
        return out;
    }

    // --------------------------------------------- 1. parse + root marker

    @Test public void parse_readsInfoWrapperAndParts() {
        List<Msg> msgs = parse(
                userTurn("u1", "turn one: remember 42"),
                asstTurn("a1", "u1", "MOCK-REPLY"));
        assertEquals(2, msgs.size());
        assertEquals("u1", msgs.get(0).id);
        assertEquals("user", msgs.get(0).role);
        assertEquals("turn one: remember 42", msgs.get(0).text);
        assertEquals("a1", msgs.get(1).id);
        assertEquals("u1", msgs.get(1).parentID);
        assertEquals("build", msgs.get(1).agent);
        assertTrue(msgs.get(1).text.contains("MOCK-REPLY"));
    }

    @Test public void parse_concatenatesMultipleTextPartsAndSkipsOthers() {
        List<Msg> msgs = parse("{\"info\":{\"id\":\"m1\",\"role\":\"assistant\","
                + "\"agent\":\"build\"},\"parts\":["
                + "{\"type\":\"text\",\"text\":\"first\"},"
                + "{\"type\":\"reasoning\",\"text\":\"hidden\"},"
                + "{\"type\":\"text\",\"text\":\"second\"}]}");
        assertEquals("first\nsecond", msgs.get(0).text);
    }

    @Test public void rootMarker_isOnlyTheCompactionAgentRow() {
        List<Msg> msgs = parse(
                userTurn("u1", "real turn"),
                asstTurn("a1", "u1", "reply"),
                emptyRoot("r1", "t1"));
        assertNull("a build-assistant is not a root", msgs.get(1).isRoot() ? "x" : null);
        assertTrue(msgs.get(2).isRoot());
        // THE TRAP: the user row carries `summary` diffs metadata — it is
        // metadata, never a root. isRoot() needs agent compaction AND
        // role != user; both a build user turn and the summarize trigger
        // prompt must fail the check.
        assertFalse(msgs.get(0).isRoot());
        assertFalse(parse(triggerPrompt("t1", "What did we do so far?"))
                .get(0).isRoot());
    }

    @Test public void lastRoot_picksTheNewestCompactionRow() {
        List<Msg> msgs = parse(
                userTurn("u1", "t1"),
                emptyRoot("r1", "t0"),
                userTurn("u2", "t2"),
                healthyRoot("r2", "t9", "a real summary"),
                userTurn("u3", "t3"));
        assertEquals("r2", CompactionPoison.lastRoot(msgs).id);
    }

    // ---------------------------------------------------- 2. the poison

    @Test public void poisoned_emptyRootWithRealTurnsBefore_it() {
        List<Msg> msgs = parse(
                userTurn("u1", "turn one"),
                asstTurn("a1", "u1", "reply one"),
                triggerPrompt("t1", "What did we do so far?"),
                emptyRoot("r1", "t1"),
                userTurn("u2", "turn two"));
        assertTrue(CompactionPoison.poisoned(msgs));
    }

    @Test public void poisoned_emptyRootWithNothingBefore_losesNothing() {
        // a compact on a brand-new session: the trigger prompt is the
        // summarizer's own apparatus, NOT history — an empty root here
        // loses nothing and must never read as poison
        List<Msg> msgs = parse(
                triggerPrompt("t1", "What did we do so far?"),
                emptyRoot("r1", "t1"));
        assertFalse(CompactionPoison.poisoned(msgs));
    }

    @Test public void poisoned_whitespaceOnlyRootAlsoPoisons() {
        // force whitespace through the same shape
        List<Msg> ws = parse(
                userTurn("u1", "turn one"),
                triggerPrompt("t1", "q"),
                healthyRoot("r1", "t1", "   \n\t "),
                userTurn("u2", "turn two"));
        assertTrue(CompactionPoison.poisoned(ws));
    }

    @Test public void poisoned_healthySummaryNeverPoisons() {
        List<Msg> msgs = parse(
                userTurn("u1", "turn one"),
                asstTurn("a1", "u1", "reply one"),
                triggerPrompt("t1", "What did we do so far?"),
                healthyRoot("r1", "t1", "You discussed the number 42 and the rig."),
                userTurn("u2", "turn two"));
        assertFalse(CompactionPoison.poisoned(msgs));
    }

    @Test public void poisoned_noRootOrNothingToLose_never() {
        // no root at all — the ordinary session
        assertFalse(CompactionPoison.poisoned(parse(
                userTurn("u1", "t1"), asstTurn("a1", "u1", "r1"))));
        // an empty root but NOTHING before it (not even the trigger)
        assertFalse(CompactionPoison.poisoned(parse(emptyRoot("r1", "t0"))));
        // null / empty input guards
        assertFalse(CompactionPoison.poisoned(null));
        assertFalse(CompactionPoison.poisoned(new ArrayList<Msg>()));
    }

    @Test public void poisoned_onlyTheLastRootDecides() {
        // an old EMPTY root superseded by a HEALTHY one: the model starts
        // at the healthy summary — by design, not poison.
        List<Msg> msgs = parse(
                userTurn("u1", "t1"),
                triggerPrompt("t1", "q1"), emptyRoot("r1", "t1"),
                userTurn("u2", "t2"),
                triggerPrompt("t2", "q2"),
                healthyRoot("r2", "t2", "a real summary"),
                userTurn("u3", "t3"));
        assertFalse(CompactionPoison.poisoned(msgs));
    }

    // -------------------------------------------------- 3. the cure plan

    @Test public void victims_emptyRootsAndTheirTriggers_only() {
        List<Msg> msgs = parse(
                userTurn("u1", "turn one"),
                asstTurn("a1", "u1", "reply one"),
                triggerPrompt("t1", "What did we do so far?"),
                emptyRoot("r1", "t1"),
                userTurn("u2", "turn two"),
                asstTurn("a2", "u2", "reply two"),
                triggerPrompt("t2", "q"),
                healthyRoot("r2", "t2", "a real summary"),
                userTurn("u3", "turn three"));
        List<String> v = CompactionPoison.victims(msgs);
        assertEquals(Arrays.asList("r1", "t1"), v);
        assertFalse("healthy root untouched", v.contains("r2"));
        assertFalse("real turns untouched", v.contains("u1") || v.contains("a1"));
    }

    @Test public void victims_collectEveryEmptyRootWhenSeveral() {
        List<Msg> msgs = parse(
                userTurn("u1", "t1"),
                triggerPrompt("t1", "q1"), emptyRoot("r1", "t1"),
                userTurn("u2", "t2"),
                triggerPrompt("t2", "q2"), emptyRoot("r2", "t2"),
                userTurn("u3", "t3"));
        List<String> v = CompactionPoison.victims(msgs);
        assertEquals(4, v.size());
        assertTrue(v.containsAll(Arrays.asList("r1", "t1", "r2", "t2")));
    }

    @Test public void victims_emptyWhenHealthyOrAbsent() {
        assertTrue(CompactionPoison.victims(parse(
                userTurn("u1", "t1"), asstTurn("a1", "u1", "r1"))).isEmpty());
        assertTrue(CompactionPoison.victims(parse(
                userTurn("u1", "t1"),
                triggerPrompt("t1", "q"),
                healthyRoot("r1", "t1", "real"),
                userTurn("u2", "t2"))).isEmpty());
        assertTrue(CompactionPoison.victims(null).isEmpty());
    }

    @Test public void deleteSql_partsBeforeMessages_boundAndBatched() {
        List<String> ids = Arrays.asList("m1", "t1", "r1");
        List<String> sql = CompactionPoison.deleteSql(ids);
        assertEquals(2, sql.size());
        assertTrue(sql.get(0).startsWith("DELETE FROM `part` WHERE `message_id` IN ("));
        assertTrue(sql.get(1).startsWith("DELETE FROM `message` WHERE `id` IN ("));
        assertEquals(3, countPlaceholders(sql.get(0)));
        assertEquals(3, countPlaceholders(sql.get(1)));
        // no id is ever spliced into the SQL — placeholders only
        for (String s : sql) {
            assertFalse(s.contains("m1"));
            assertFalse(s.contains("r1"));
        }
    }

    @Test public void deleteSql_batchesUnderTheParameterWall() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < CompactionPoison.SQL_BATCH + 5; i++) ids.add("x");
        // force the batch wall by shrinking the batch through a second list
        List<String> small = new ArrayList<>();
        for (int i = 0; i < CompactionPoison.SQL_BATCH; i++) small.add("x");
        List<String> two = CompactionPoison.deleteSql(small);
        assertEquals(2, two.size());
        assertEquals(CompactionPoison.SQL_BATCH,
                countPlaceholders(two.get(0)));
        // SQL_BATCH+5 items → 2 part batches + 2 message batches
        List<String> big = CompactionPoison.deleteSql(ids);
        assertEquals(4, big.size());
        assertEquals(5, countPlaceholders(big.get(1)));
    }

    @Test public void deleteSql_emptyPlanIsEmpty() {
        assertTrue(CompactionPoison.deleteSql(null).isEmpty());
        assertTrue(CompactionPoison.deleteSql(new ArrayList<String>()).isEmpty());
    }

    private static int countPlaceholders(String sql) {
        int n = 0;
        for (int i = 0; i < sql.length(); i++) if (sql.charAt(i) == '?') n++;
        return n;
    }

    // ---------------------------------------------- 4. the honest lines

    @Test public void notes_areHonestSingleLinesWithoutSecrets() {
        String note = CompactionPoison.note();
        assertTrue(note.contains("empty summary"));
        assertTrue(note.contains("Repairing"));
        String cured = CompactionPoison.curedNote();
        assertTrue(cured.contains("full conversation reaches the model"));
        String failed = CompactionPoison.failedNote("the server did not stop");
        assertTrue(failed.contains("the server did not stop"));
        assertTrue(failed.contains("keeps riding"));
        for (String s : Arrays.asList(note, cured, failed)) {
            assertFalse(s.contains("\""));
            assertFalse(s.toLowerCase().contains("ghp_"));
        }
    }

    // ----------------------------- 5. the hub bridge (flag + agent parse)

    private static void setCur(RunHub.Tx t) throws Exception {
        Field f = RunHub.class.getDeclaredField("cur");
        f.setAccessible(true);
        f.set(null, t);
    }

    private static void setPoison(String sid, boolean on) throws Exception {
        Field f = RunHub.class.getDeclaredField("poisonKnown");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> s = (java.util.Set<String>) f.get(null);
        if (on) s.add(sid); else s.remove(sid);
    }

    @Test public void hub_agentParseMarksCompactionAsSummary() {
        RunHub.Tx t = new RunHub.Tx();
        t.sid = "ses_p";
        RunHub.applyMessageInfo(t, Json.obj(Json.parse(
                "{\"id\":\"r1\",\"role\":\"assistant\",\"agent\":\"compaction\"}")));
        RunHub.MsgInfo mi = t.msgs.get("r1");
        assertNotNull(mi);
        assertEquals("compaction", mi.agent);
        assertTrue("the detector must skip across a compaction row "
                + "even on replayed sessions", mi.summary);
        RunHub.applyMessageInfo(t, Json.obj(Json.parse(
                "{\"id\":\"a1\",\"role\":\"assistant\",\"agent\":\"build\"}")));
        assertEquals("build", t.msgs.get("a1").agent);
        assertFalse(t.msgs.get("a1").summary);
    }

    @Test public void hub_contextRepairRidesWhilePoisonedThenStops() throws Exception {
        String sid = "ses_poison";
        RunHub.Tx t = new RunHub.Tx();
        t.sid = sid;
        RunHub.applyMessageInfo(t, Json.obj(Json.parse(
                "{\"id\":\"u1\",\"role\":\"user\"}")));
        RunHub.Row r = new RunHub.Row();
        r.kind = RunHub.K_USER;
        r.key = "u1|p1";
        r.text.append("turn one: remember 42");
        t.rows.add(r);
        setCur(t);
        setPoison(sid, true);
        try {
            String wire = RunHub.contextRepair(sid);
            assertNotNull(wire);
            assertTrue(wire.startsWith("<system-reminder>"));
            assertTrue(wire.contains(AmnesiaGuard.SIGNATURE));
            assertTrue(wire.contains("turn one"));
        } finally {
            setPoison(sid, false);
        }
        assertNull("the bridge must lift once the repair verifies",
                RunHub.contextRepair(sid));
    }

    @Test public void hub_diagnoseGuardsShortInput() throws Exception {
        setPoison("ses_none", false);
        RunHub.diagnosePoison(null, null);
        RunHub.diagnosePoison("ses_none", null);
        RunHub.diagnosePoison("ses_none", new ArrayList<Object>());
        Field f = RunHub.class.getDeclaredField("poisonKnown");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> s = (java.util.Set<String>) f.get(null);
        assertFalse("a blank diagnose must never flag a session",
                s.contains("ses_none"));
    }
}
