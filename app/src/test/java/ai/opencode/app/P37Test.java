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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P37 — the field-report pins. Three device-reported chat defects and the
 * agent-environment confusion, each pinned at the layer that owns it:
 *
 * 1. empty chat items — a text part holding only whitespace (tool-only
 *    assistant rounds, the shell before an early error) used to become an
 *    invisible padded box plus a floating token footer: the weird gaps.
 *    Now the hub never materializes the row and the view paints zero
 *    footprint even if one slips through.
 * 2. the double cross — the error card view already draws its own ✕; the
 *    model side no longer prepends a second one.
 * 3. the environment map — the note that rides a session's first message
 *    answers the GitHub-token question honestly in BOTH states and never
 *    contains the token itself; the guest gitconfig turns GH_TOKEN into a
 *    working plain git push without holding any secret.
 * 4. the leaky-model note — raw DSML tool-call markup printed as chat
 *    text gets a once-per-session explanation that the marked-up actions
 *    did not run.
 */
@RunWith(RobolectricTestRunner.class)
public class P37Test {

    @Before
    public void resetHub() throws Exception {
        RunHub.Tx t = RunHub.tx();
        t.rows.clear();
        t.idxByKey.clear();
        t.msgs.clear();
        t.typeCount.clear();
        t.trimmedKeys.clear();
        t.lastAssistantTok = 0;
        t.dsmlNoted = false;
        set("sessionId", null);
        set("sessionTitle", null);
        set("busy", false);
        RunHub.clearRunsForTest();
        set("interruptedNotePending", false);
        set("lastUserText", null);
    }

    private static void set(String field, Object v) throws Exception {
        Field f = RunHub.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // ------------------------------------------------- blank text parts

    @Test
    public void blankText_whitespaceOnly_isBlank() {
        assertTrue(RunHub.blankText(null));
        assertTrue(RunHub.blankText(""));
        assertTrue(RunHub.blankText(" "));
        assertTrue(RunHub.blankText(" \n\t \r\n  "));
        assertFalse(RunHub.blankText("a"));
        assertFalse(RunHub.blankText(" a"));
        assertFalse(RunHub.blankText("…"));
        assertFalse(RunHub.blankText(" . "));
    }

    @Test
    public void upsertText_blankPart_neverCreatesARow() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ctl.setup().get();
            RunHub.upsertText(RunHub.tx(), "m1|p1", "m1", "  \n\t ");
            RunHub.upsertText(RunHub.tx(), "m1|p2", "m1", "");
            RunHub.upsertText(RunHub.tx(), "m1|p3", "m1", "\r\n");
            idle();
            assertEquals(0, RunHub.rows().size());
        }
    }

    @Test
    public void upsertText_blankThenRealText_rowArrivesWithTheText() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ctl.setup().get();
            RunHub.upsertText(RunHub.tx(), "m1|p1", "m1", "   ");
            idle();
            assertEquals(0, RunHub.rows().size());
            RunHub.upsertText(RunHub.tx(), "m1|p1", "m1", "real words now");
            idle();
            List<RunHub.Row> rows = RunHub.rows();
            assertEquals(1, rows.size());
            assertEquals("real words now", rows.get(0).text.toString());
            // and it still grows like any other row
            RunHub.upsertText(RunHub.tx(), "m1|p1", "m1", "real words now + more");
            idle();
            assertEquals("real words now + more", rows.get(0).text.toString());
        }
    }

    @Test
    public void upsertText_blankPart_noRowEvenWhenMetaArrived()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ctl.setup().get();
            RunHub.applyMessageInfo(RunHub.tx(), obj(
                    "id", "m1", "role", "assistant",
                    "tokens", obj("input", 1200, "output", 30)), false);
            RunHub.upsertText(RunHub.tx(), "m1|p1", "m1", "  ");
            idle();
            // the floating token footer with NO message under it was the
            // weird-gap complaint — a blank part never paints one, meta
            // or not. Real-text messages keep their footers (pinned by
            // realRow_keepsItsBodyAndFooter + the normal attach path).
            assertEquals(0, RunHub.rows().size());
        }
    }

    private static java.util.Map<String, Object> obj(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // --------------------------------------------------- double-cross fix

    @Test
    public void messageError_titleCarriesNoSecondCross() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ctl.setup().get();
            RunHub.applyMessageInfo(RunHub.tx(), obj(
                    "id", "m9", "role", "assistant",
                    "error", obj("name", "APIError", "message", "boom")), false);
            idle();
            List<RunHub.Row> rows = RunHub.rows();
            assertEquals(1, rows.size());
            assertEquals(RunHub.K_ERR, rows.get(0).kind);
            // the card view paints the ✕ itself — the stored title must not
            // start with a second one
            assertFalse(rows.get(0).text.toString().startsWith("✕"));
            assertTrue(rows.get(0).text.toString().contains("APIError"));
        }
    }

    // ------------------------------------------------------- env note

    @Test
    public void envNote_absentMeansNeverTold_deliveredStaysQuiet() {
        assertTrue(EnvNote.needsNote(null));
        assertFalse(EnvNote.needsNote(Boolean.TRUE));
        assertFalse(EnvNote.needsNote(Boolean.FALSE));
    }

    @Test
    public void envNote_tokenSet_namesTheTokenAndItsScope_neverTheValue() {
        String n = EnvNote.note(true);
        assertTrue(n.startsWith("<system-reminder>"));
        assertTrue(n.endsWith("</system-reminder>"));
        assertTrue(n.contains("GH_TOKEN"));
        assertTrue(n.contains(EnvNote.REPO));
        assertTrue(n.contains("git push"));
        assertTrue(n.contains("Never print the token"));
        // host-vs-guest map present in both states
        assertTrue(n.contains("Debian guest"));
        assertTrue(n.contains("project folder"));
        // the note must never carry a token-shaped secret
        assertFalse(n.contains("ghp_"));
        assertFalse(n.contains("github_pat_"));
    }

    @Test
    public void envNote_tokenAbsent_saysSo_andPointsToTheKeysScreen() {
        String n = EnvNote.note(false);
        assertTrue(n.contains("no token is configured"));
        assertTrue(n.contains("git push will fail"));
        assertTrue(n.contains("Agent GitHub access"));
        assertFalse(n.contains("GH_TOKEN is exported"));
        assertFalse(n.contains("ghp_"));
        assertFalse(n.contains("github_pat_"));
    }

    // --------------------------------------------- guest gitconfig helper

    @Test
    public void gitConfigSnippet_scopesGitHubOnly_andHoldsNoSecret() {
        String g = Debian.gitConfigSnippet();
        assertTrue(g.startsWith("[credential \"https://github.com\"]"));
        assertTrue(g.contains("x-access-token"));
        assertTrue(g.contains("$GH_TOKEN"));     // read at push time
        assertFalse(g.contains("ghp_"));
        assertFalse(g.contains("github_pat_"));
        // printf receives literal backslash-n (the helper prints the real
        // newlines): the file must hold the two-char sequence
        assertTrue(g.contains("\\npassword=%s\\n"));
        // well-formed for the git config parser: helper value on one line
        assertEquals(2, g.trim().split("\n").length);
    }

    @Test
    public void gitConfigSnippet_writeIfDifferent_roundTrips() throws Exception {
        android.content.Context c =
                Robolectric.setupActivity(ChatActivity.class);
        Debian.writeLauncher(c);
        java.io.File rootfsRoot = new java.io.File(
                c.getFilesDir().getParentFile(), "files/debian/rootfs/root");
        // the rootfs may not exist in a bare test tree — the write is
        // silent then (pin THAT, not a file): no throw is the contract
        if (rootfsRoot.isDirectory()) {
            java.io.File gf = new java.io.File(rootfsRoot, ".gitconfig");
            byte[] want = Debian.gitConfigSnippet().getBytes("UTF-8");
            byte[] got = java.nio.file.Files.readAllBytes(gf.toPath());
            assertTrue(java.util.Arrays.equals(want, got));
        }
    }

    // ------------------------------------------------- leaky-model note

    @Test
    public void dsml_leaky_matchesTheFieldMarkup_only() {
        assertTrue(RunHub.Dsml.leaky("</|DSML|tool_calls>"));
        assertTrue(RunHub.Dsml.leaky("<|DSML|invoke>"));
        assertTrue(RunHub.Dsml.leaky("junk\n</|DSML|parameter> x"));
        assertFalse(RunHub.Dsml.leaky(null));
        assertFalse(RunHub.Dsml.leaky(""));
        assertFalse(RunHub.Dsml.leaky("we read the DSML docs today"));
        assertFalse(RunHub.Dsml.leaky("|DSML| without the bracket"));
    }

    @Test
    public void dsml_note_saysTheActionsDidNotRun() {
        String n = RunHub.Dsml.note();
        assertTrue(n.contains("did NOT run"));
        assertTrue(n.contains("tool-call markup"));
    }

    @Test
    public void upsertText_dsmlMarkup_firesOneNotePerSession_notPerPart()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ctl.setup().get();
            RunHub.upsertText(RunHub.tx(), "m1|p1", "m1",
                    "step 1 ok\n</|DSML|invoke>");
            idle();
            List<RunHub.Row> rows = RunHub.rows();
            assertEquals(2, rows.size());            // text + one note
            assertEquals(RunHub.K_SYS, rows.get(1).kind);
            assertTrue(rows.get(1).text.toString().contains("DSML"));
            // more leaking parts: no more notes
            RunHub.upsertText(RunHub.tx(), "m1|p2", "m1",
                    "</|DSML|tool_calls> again");
            idle();
            assertEquals(3, RunHub.rows().size());   // text only, no note
        }
    }

    // ------------------------------------------- blank rows paint nothing

    @Test
    public void blankRowWithoutMeta_isZeroFootprintInView() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.Row r = new RunHub.Row();
            r.kind = RunHub.K_ASSISTANT;
            r.key = "m1|p1";
            r.text.append("  \n ");
            java.lang.reflect.Method m = ChatActivity.class
                    .getDeclaredMethod("buildRowView", RunHub.Row.class);
            m.setAccessible(true);
            android.view.View v = (android.view.View) m.invoke(a, r);
            assertEquals(android.view.View.GONE, v.getVisibility());
        }
    }

    @Test
    public void blankRowWithMeta_paintsTheFooterCompactly() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.Row r = new RunHub.Row();
            r.kind = RunHub.K_ASSISTANT;
            r.key = "m1|p1";
            r.text.append(" \t");
            r.meta = "⇅ 1.2k tok · $0.0005";
            java.lang.reflect.Method m = ChatActivity.class
                    .getDeclaredMethod("buildRowView", RunHub.Row.class);
            m.setAccessible(true);
            android.view.View v = (android.view.View) m.invoke(a, r);
            assertEquals(android.view.View.VISIBLE, v.getVisibility());
            assertTrue(v instanceof android.view.ViewGroup);
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            assertEquals(1, g.getChildCount());      // footer only, no body
            assertTrue(g.getChildAt(0) instanceof android.widget.TextView);
            String shown = ((android.widget.TextView) g.getChildAt(0))
                    .getText().toString();
            assertTrue(shown.contains("tok"));
        }
    }

    @Test
    public void realRow_keepsItsBodyAndFooter() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.Row r = new RunHub.Row();
            r.kind = RunHub.K_ASSISTANT;
            r.key = "m2|p1";
            r.text.append("hello world");
            r.meta = "⇅ 3.4k tok";
            java.lang.reflect.Method m = ChatActivity.class
                    .getDeclaredMethod("buildRowView", RunHub.Row.class);
            m.setAccessible(true);
            android.view.ViewGroup v = (android.view.ViewGroup)
                    m.invoke(a, r);
            assertEquals(2, v.getChildCount());      // body + footer
            assertEquals(android.view.View.VISIBLE, v.getVisibility());
        }
    }
}
