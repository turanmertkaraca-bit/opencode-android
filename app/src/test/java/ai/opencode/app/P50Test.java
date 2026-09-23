package ai.opencode.app;

import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P50 — the BACKGROUND release, pinned where each piece lives.
 *
 * FIELD REPORT DRIVING THIS RELEASE: "the app cannot work in background,
 * it keeps closing after a little while even tho i closed cool idle" and
 * "these tool call bubble works for tool calls and nothing else". Two
 * diseases, both cured at the root:
 *
 *   1. THE BACKGROUND KILLER — auto-hibernate (P31) defaulted ON: after
 *      10 quiet background minutes the sandbox STOPPED ITSELF, and the
 *      "Cool idle" switch the user flipped is a different feature (the
 *      wake lock). On top of the default flip, a persisted WANT flag +
 *      an allow-while-idle watchdog chain now resurrect the service
 *      after an OS/OEM process kill, swipe-away re-arms, and the
 *      battery-exemption one-tap rides the notification.
 *
 *   2. THE QUESTION TOOL — question.asked (and the plan approval) left
 *      the agent blocked forever: the card was display-only. The
 *      shipped v1.18.25 binary exposes
 *      POST /api/session/{sid}/question/{rid}/reply {"answers":[[...]]}
 *      — the hub now stores asks, the chat renders tappable options,
 *      and the reply ladder answers/skips in place.
 *
 * All pinned here:
 *   - the hibernate default (OFF) and the watchdog decision (pure),
 *   - Questions.parse against the EXACT input the field screenshot
 *     showed (header/question/options/multiple), including garbage
 *     tolerance — a malformed frame must never take the hub down,
 *   - the reply body shape: one label array per question, in order,
 *   - the server's own echo rule (", "-joined, empty = "Unanswered"),
 *   - the event aliases (question.v2.*) and the store round-trip
 *     through the REAL RunHub listener (asked → stored; replied and
 *     rejected → removed; same-id re-ask → replaced, never doubled),
 *   - the version moved to 0.50.0-p50.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class P50Test {

    // ============ 1. the background killer: hibernate default + watchdog

    @Test public void hibernate_factoryDefaultIsOff() {
        assertFalse("auto-hibernate must be opt-in — it WAS the background"
                        + " killer",
                ServerService.HIBERNATE_DEFAULT);
    }

    @Test public void hibernate_dueContractUntouched() {
        // the pure rule still refuses: foreground, work in flight, off (0)
        long now = 1_000_000L;
        assertFalse(Hibernate.due(0, now, 1, false, false));
        assertTrue(Hibernate.due(now - 11 * 60_000L, now, 10 * 60_000L,
                false, false));
        assertFalse("a run in flight is never hibernated",
                Hibernate.due(now - 11 * 60_000L, now, 10 * 60_000L,
                        true, false));
        assertFalse("a waiting permission/question is never hibernated",
                Hibernate.due(now - 11 * 60_000L, now, 10 * 60_000L,
                        false, true));
        assertFalse("0 ms = off",
                Hibernate.due(now - 11 * 60_000L, now, 0, false, false));
    }

    @Test public void watchdog_bootDecision() {
        assertTrue(WatchdogReceiver.shouldBoot(true, true));
        assertFalse("keep-alive off → no resurrection",
                WatchdogReceiver.shouldBoot(false, true));
        assertFalse("a deliberate stop is never undone",
                WatchdogReceiver.shouldBoot(true, false));
        assertEquals(4 * 60_000L, WatchdogReceiver.INTERVAL_MS);
    }

    @Test public void hibernate_aPendingQuestionBlocksHibernation_semantics() {
        // hibernateDueAt() folds pending questions into the permsPending
        // input; the store side of that contract is pinned by the round
        // trip below (ask → count>0, replied → count==0). The pure rule
        // (permsPending → never) is pinned right above.
        RunHub.clearQuestionsForTests();
        assertEquals(0, RunHub.pendingQuestionCount());
        RunHub.HUB.onEvent(askEvent("s1", "r1", oneQuestion()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a blocked-on-user run is NOT idle",
                1, RunHub.pendingQuestionCount());
        RunHub.HUB.onEvent(goneEvent("question.replied", "s1", "r1"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, RunHub.pendingQuestionCount());
    }

    // ============ 2. the question tool: parse the shipped schema

    @Test public void questions_parse_fieldShape() {
        // the EXACT shape the field hit: question/header/options with
        // label+description (the "Hosting" ask from the screenshots)
        Questions.Req r = Questions.parse(askProps("s1", "r1",
                question("Where does the bot process actually run in the"
                                + " final product?", "Hosting",
                        false,
                        option("Phone hosts everything (Recommended)",
                                "Android app runs the bot engine; fully"
                                        + " standalone on-device."),
                        option("PC/server runs bot, phone is just a viewer",
                                "Android app connects elsewhere; simpler"
                                        + " but not fully on-phone."))));
        assertNotNull(r);
        assertEquals("r1", r.id);
        assertEquals("s1", r.sessionID);
        assertEquals(1, r.prompts.size());
        assertEquals("Hosting", r.prompts.get(0).header);
        assertEquals(2, r.prompts.get(0).options.size());
        assertEquals("Phone hosts everything (Recommended)",
                r.prompts.get(0).options.get(0).label);
        assertFalse(r.prompts.get(0).multiple);
    }

    @Test public void questions_parse_multiChoice() {
        Questions.Req r = Questions.parse(askProps("s1", "r1",
                question("Pick formatting previews", "Rows", true,
                        option("Diff", "Emit edit diff"),
                        option("Todo", "Emit todo card"))));
        assertNotNull(r);
        assertTrue(r.prompts.get(0).multiple);
    }

    @Test public void questions_parse_toleratesGarbage() {
        assertNull("null payload", Questions.parse(null));
        assertNull("no id", Questions.parse(askProps(null, "r1", oneQuestion())));
        assertNull("no sid", Questions.parse(askProps("s1", null, oneQuestion())));
        assertNull("empty questions",
                Questions.parse(askProps("s1", "r1")));
        Map<String, Object> junk = new LinkedHashMap<>();
        junk.put("id", "r1");
        junk.put("sessionID", "s1");
        junk.put("questions", "not-a-list");
        assertNull("questions must be a list", Questions.parse(junk));
        Map<String, Object> half = askProps("s1", "r1", oneQuestion());
        half.put("questions", Arrays.asList("bare-string"));
        assertNull("non-map question entries rejected", Questions.parse(half));
    }

    @Test public void questions_replyBody_oneLabelArrayPerQuestion() {
        List<List<String>> picked = new ArrayList<>();
        picked.add(Arrays.asList("Phone hosts everything (Recommended)"));
        picked.add(new ArrayList<String>());          // unanswered ships []
        picked.add(Arrays.asList("Diff", "Todo"));
        assertEquals("{\"answers\":["
                + "[\"Phone hosts everything (Recommended)\"],"
                + "[],"
                + "[\"Diff\",\"Todo\"]]}",
                Questions.answersJson(picked));
        assertEquals("null → empty answers array", "{\"answers\":[]}",
                Questions.answersJson(null));
    }

    @Test public void questions_replyBody_escapesQuotes() {
        List<List<String>> picked = new ArrayList<>();
        picked.add(Arrays.asList("say \"hi\""));
        assertEquals("{\"answers\":[[\"say \\\"hi\\\"\"]]}",
                Questions.answersJson(picked));
    }

    @Test public void questions_echoRule_matchesTheServer() {
        assertEquals("Phone hosts everything (Recommended)",
                Questions.echo(Arrays.asList(
                        "Phone hosts everything (Recommended)")));
        assertEquals("Diff, Todo",
                Questions.echo(Arrays.asList("Diff", "Todo")));
        assertEquals("Unanswered", Questions.echo(null));
        assertEquals("Unanswered", Questions.echo(new ArrayList<String>()));
    }

    @Test public void questions_eventTypeAliases() {
        assertTrue(Questions.isAskedType("question.asked"));
        assertTrue(Questions.isAskedType("question.v2.asked"));
        assertFalse(Questions.isAskedType("permission.asked"));
        assertTrue(Questions.isGoneType("question.replied"));
        assertTrue(Questions.isGoneType("question.rejected"));
        assertTrue(Questions.isGoneType("question.v2.replied"));
        assertTrue(Questions.isGoneType("question.v2.rejected"));
        assertFalse(Questions.isGoneType("question.asked"));
    }

    // ============ 3. the store round-trip through the REAL hub listener

    @Test public void onEvent_ask_storesAndReplaces() {
        RunHub.clearQuestionsForTests();
        RunHub.HUB.onEvent(askEvent("s1", "r1", oneQuestion()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(RunHub.pendingQuestion("s1"));
        // same id re-asked (the server refreshes an ask) → replace, not dupe
        RunHub.HUB.onEvent(askEvent("s1", "r1", oneQuestion()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, RunHub.pendingQuestionCount());
        assertNull("other sessions untouched", RunHub.pendingQuestion("s2"));
    }

    @Test public void onEvent_replyAndReject_remove() {
        RunHub.clearQuestionsForTests();
        RunHub.HUB.onEvent(askEvent("s1", "r1", oneQuestion()));
        RunHub.HUB.onEvent(askEvent("s1", "r2", oneQuestion()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, RunHub.pendingQuestionCount());
        RunHub.HUB.onEvent(goneEvent("question.replied", "s1", "r1"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, RunHub.pendingQuestionCount());
        RunHub.HUB.onEvent(goneEvent("question.v2.rejected", "s1", "r2"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, RunHub.pendingQuestionCount());
        assertNull(RunHub.pendingQuestion("s1"));
    }

    @Test public void onEvent_malformedAsk_isStoredButInert() {
        // a broken payload must never take the event loop down: the ask
        // stores defensively (raw map), parse() degrades to null, and the
        // NEXT event still processes.
        RunHub.clearQuestionsForTests();
        Map<String, Object> ev = askEvent("s1", "r1", oneQuestion());
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) ev.get("properties");
        props.put("questions", 42);
        RunHub.HUB.onEvent(ev);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("store keeps the raw ask", 1, RunHub.pendingQuestionCount());
        assertNull("parse refuses the broken shape",
                Questions.parse(RunHub.pendingQuestion("s1")));
        RunHub.HUB.onEvent(goneEvent("question.replied", "s1", "r1"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, RunHub.pendingQuestionCount());
    }

    // ============ 4. the version

    @Test public void version_isP50() {
        // P52 moved the tag forward; the pin rides the current release
        assertEquals("0.52.0-p52", SettingsActivity.VERSION_TAG);
    }

    // ------------------------------------------------------------ helpers

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> option(String label, String description) {
        return obj("label", label, "description", description);
    }

    private static Map<String, Object> question(String q, String header,
            boolean multiple, Map<String, Object>... options) {
        return obj("question", q, "header", header,
                "multiple", multiple, "options", Arrays.asList(options));
    }

    private static Map<String, Object> oneQuestion() {
        return question("Which Java version should we target first?",
                "MC version", false,
                option("1.21.x", "Modern vanilla/Paper."),
                option("1.20.1", "Very common."));
    }

    private static Map<String, Object> askProps(String sid, String rid,
            Map<String, Object>... questions) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rid != null) m.put("id", rid);
        if (sid != null) m.put("sessionID", sid);
        m.put("questions", Arrays.asList(questions));
        m.put("tool", obj("messageID", "msg_1", "callID", "call_1"));
        return m;
    }

    private static Map<String, Object> askEvent(String sid, String rid,
            Map<String, Object>... questions) {
        return obj("type", "question.asked",
                "properties", askProps(sid, rid, questions));
    }

    private static Map<String, Object> goneEvent(String type, String sid,
            String rid) {
        return obj("type", type,
                "properties", obj("sessionID", sid, "requestID", rid));
    }
}
