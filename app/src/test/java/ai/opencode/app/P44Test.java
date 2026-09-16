package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P44 — the quiet-collapse release, pinned where each piece lives.
 *
 * FIELD REPORT DRIVING THIS RELEASE (v0.43.0): mid-task the agent's
 * context "randomly collapsed for no reason", it forgot its own
 * greeting, answered a phantom hello-world opener, and burned real
 * money re-probing the sandbox it had already mapped. The source chain:
 * the server's compaction summary thins the OLDEST turns (the original
 * task) and can summarize away the P42 sandbox ground rules that ride
 * the session-start note — the model then re-probes, refills, and the
 * cycle repeats. The cure is the post-compaction anchor: the session's
 * first real send after a summary lands re-rides the thread recap, the
 * ground rules, and the name of any active window cap.
 *
 * Pure logic here; the RunHub wiring is pinned by the Tx-level tests
 * below plus the worklog file:line map. Robolectric runner: RunHub
 * static init needs the shadow Looper (the P25HubTest pattern).
 */
@RunWith(RobolectricTestRunner.class)
public class P44Test {

    // ======================================= 1. CompactionPolicy.anchorBlock

    @Test public void anchor_namesTheThreadRulesAndCap() {
        String b = CompactionPolicy.anchorBlock(212,
                "clone fly-brain into the playground",
                "is it done yet?",
                "Still fetching — the pack is large.",
                128_000);
        assertTrue("system-reminder shape",
                b.startsWith("<system-reminder>") && b.endsWith("</system-reminder>"));
        assertTrue("must carry the greeting-recap signature so NoteStrip strips it",
                b.contains(GreetGuard.SIG));
        assertTrue(b.contains("212 messages deep"));
        assertTrue(b.contains("clone fly-brain into the playground"));
        assertTrue(b.contains("is it done yet?"));
        assertTrue(b.contains("Still fetching — the pack is large."));
        assertTrue("active cap must be named in tokens the pill shows",
                b.contains("128k"));
        assertTrue(b.contains("Σ popover"));
        assertTrue("the ground rules must ride again",
                b.contains("TLS is provisioned and working"));
        assertTrue(b.contains("fails twice"));
        assertTrue("continuation, never restart",
                b.contains("Continue this exact task"));
    }

    @Test public void anchor_withoutCap_staysQuietAboutIt() {
        String b = CompactionPolicy.anchorBlock(9, "fix the build", null,
                null, 0);
        assertFalse("no cap set → no cap clause",
                b.contains("window cap"));
        assertTrue(b.contains("fix the build"));
        assertFalse("null lastUser must not add an empty quote",
                b.contains("latest real request"));
        assertFalse("null lastAssistant must not add an empty quote",
                b.contains("you last said"));
    }

    @Test public void anchor_negativeTurnsClampsAndGreetingClausePresent() {
        String b = CompactionPolicy.anchorBlock(-3, "task", "u", "a", 0);
        assertTrue(b.contains("0 messages deep"));
        assertTrue("a greeting right after a summary must still stay in thread",
                b.contains("only a greeting"));
    }

    // ===================================== 2. CompactionPolicy.summaryNote(cap)

    @Test public void summaryNote_noCap_isExactlyTheP41Line() {
        assertEquals(CompactionPolicy.summaryNote(),
                CompactionPolicy.summaryNote(0));
        assertEquals(CompactionPolicy.summaryNote(),
                CompactionPolicy.summaryNote(-5));
    }

    @Test public void summaryNote_withCapNamesTheTrigger() {
        String base = CompactionPolicy.summaryNote();
        String with = CompactionPolicy.summaryNote(64_000);
        assertTrue(with.startsWith(base));
        assertTrue(with.contains("64.0k"));
        assertTrue(with.contains("Σ popover"));
        assertTrue(with.contains("raised or cleared"));
    }

    // ============================== 3. RunHub.compactionAnchorBlockTx

    @Test public void anchorTx_nullOrEmptyThreadNeverRides() {
        assertNull(RunHub.compactionAnchorBlockTx(null, "hi"));
        RunHub.Tx t = new RunHub.Tx();
        assertNull("no opening request → no anchor",
                RunHub.compactionAnchorBlockTx(t, "hi"));
    }

    @Test public void anchorTx_noMinimumDepth_summaryIsTheProof() {
        // Unlike GreetGuard's 6-turn gate: a compaction landing IS the
        // depth proof, so a short-but-compacted thread must anchor.
        RunHub.Tx t = new RunHub.Tx();
        t.rows.add(userRow("clone fly-brain into the playground"));
        t.rows.add(asstRow("On it — cloning now."));
        String block = RunHub.compactionAnchorBlockTx(t, null);
        assertNotNull(block);
        assertTrue(block.contains("clone fly-brain into the playground"));
        assertTrue(block.contains("TLS is provisioned and working"));
    }

    @Test public void anchorTx_skipsTheLiveSend_notQuotedAsHistory() {
        RunHub.Tx t = new RunHub.Tx();
        t.rows.add(userRow("clone fly-brain into the playground"));
        t.rows.add(asstRow("Working."));
        t.rows.add(userRow("is it done yet?"));
        String block = RunHub.compactionAnchorBlockTx(t, "continue");
        assertNotNull(block);
        assertFalse("the current send must not be quoted as the previous request",
                block.contains("\"continue\""));
        assertTrue(block.contains("is it done yet?"));
    }

    @Test public void anchorTx_displayStripKeepsTheBubbleClean() {
        RunHub.Tx t = new RunHub.Tx();
        t.rows.add(userRow("clone fly-brain into the playground"));
        t.rows.add(asstRow("On it."));
        String wire = RunHub.compactionAnchorBlockTx(t, null)
                + "\n\ncontinue the clone";
        assertEquals("the anchor must strip silently — only the user's words show",
                "continue the clone", NoteStrip.display(wire));
    }

    // ==================================== 4. RunHub anchor pending-set

    @Test public void anchorPending_defaultsAbsentAndConsumeIsSafe() {
        assertFalse(RunHub.anchorPending(null));
        assertFalse(RunHub.anchorPending("no-such-session"));
        RunHub.anchorConsume(null);            // must not throw
        RunHub.anchorConsume("no-such-session");
        assertFalse(RunHub.anchorPending("no-such-session"));
    }

    // ------------------------------------------------------- helpers

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
}
