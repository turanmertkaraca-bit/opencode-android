package ai.opencode.app;

import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P38 — the second field report, pinned at the layer that owns each bug:
 *
 * 1. THE NOTES ARE FOR THE MODEL, NOT FOR THE HUMAN. P37's environment
 *    map rendered inside the user's own bubble (a giant system-reminder
 *    block burying the message — the screenshots), and the same was true
 *    of the render-check and terse notes on their ride turns. The strip
 *    is display-only: wire text keeps the notes, the bubble shows the
 *    words. Old sessions repaint through the same upsert, so their
 *    stored blocks clean up too.
 * 2. ONCE MEANS ONCE. The told-maps were memory-only — a process death
     * (overnight, a kill, an update) forgot them and the next send rode
 *    the whole note again. They persist write-through now; the restore
 *    path refills the maps the send path reads.
 * 3. THE RESIDUAL GAP. A body ending in blank lines paints them — the
 *    token footer floated below a visible void (the one message the user
 *    still saw after P37). Display + copy trim the tail; stored bytes
 *    stay untouched.
 */
@RunWith(RobolectricTestRunner.class)
public class P38Test {

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // ------------------------------------------------- the display strip

    @Test
    public void strip_plainText_passesThrough() {
        assertEquals("hello", NoteStrip.display("hello"));
        assertEquals("two words\n\nsecond line",
                NoteStrip.display("two words\n\nsecond line"));
    }

    @Test
    public void strip_nullAndEmpty_areSafe() {
        assertNull(NoteStrip.display(null));
        assertEquals("", NoteStrip.display(""));
    }

    @Test
    public void strip_envNoteOnly_messageSurvives() {
        String wire = EnvNote.note(true) + "\n\nhello";
        assertEquals("hello", NoteStrip.display(wire));
    }

    @Test
    public void strip_envNoteAbsentVariant_stillStripped() {
        String wire = EnvNote.note(false) + "\n\nwhat about push?";
        assertEquals("what about push?", NoteStrip.display(wire));
    }

    @Test
    public void strip_allThreeNotes_realMessageIsAllThatRemains() {
        String wire = RenderCheck.note(43117, "tok")
                + "\n\n" + EnvNote.note(true)
                + "\n\n" + TerseMode.note(true)
                + "\n\nrun the tests please";
        assertEquals("run the tests please", NoteStrip.display(wire));
    }

    @Test
    public void strip_noteOnlyMessage_emptyResult() {
        assertEquals("", NoteStrip.display(EnvNote.note(true)));
        assertEquals("", NoteStrip.display(
                EnvNote.note(false) + "\n\n" + TerseMode.note(false)));
    }

    @Test
    public void strip_unknownReminderBlock_isNeverStripped() {
        String userOwn = "<system-reminder>something the user typed"
                + "</system-reminder>\n\nhello";
        assertEquals(userOwn, NoteStrip.display(userOwn));
    }

    @Test
    public void strip_appNoteMidText_isKept() {
        // the app only ever PREPENDS; a note-shaped block after the
        // message began is content as far as the strip cares
        String s = "look at this\n\n" + EnvNote.note(true) + "\n\nok?";
        assertEquals(s, NoteStrip.display(s));
    }

    @Test
    public void strip_leadingWhitespaceBeforeNotes_seamGoesToo() {
        String wire = "\n\n  " + EnvNote.note(true) + "\n\n  hi";
        assertEquals("hi", NoteStrip.display(wire));
    }

    @Test
    public void strip_unterminatedNote_isKept() {
        String s = "<system-reminder>Environment: never closed";
        assertEquals(s, NoteStrip.display(s));
    }

    // ------------------------------------------- signature cross-pinning

    @Test
    public void signatures_theStripKnowsEveryNoteTheAppWrites() {
        // drift in any note builder must fail HERE, not in the field
        for (boolean gh : new boolean[]{true, false})
            assertTrue(NoteStrip.appNote(EnvNote.note(gh), 0));
        assertTrue(NoteStrip.appNote(TerseMode.note(true), 0));
        assertTrue(NoteStrip.appNote(TerseMode.note(false), 0));
        String rc = RenderCheck.note(43117, "tok");
        assertTrue(rc != null && NoteStrip.appNote(rc, 0));
    }

    @Test
    public void signatures_envNoteCarriesNoTokenShape() {
        // the strip sees the full note on every ride — re-pin the secret
        // rule one layer deeper while we are here
        for (boolean gh : new boolean[]{true, false}) {
            String n = EnvNote.note(gh);
            assertFalse(n.matches(".*ghp_[A-Za-z0-9]{20,}.*"));
            assertFalse(n.matches(".*github_pat_.*"));
        }
    }

    // ------------------------------------------------------- the tail trim

    @Test
    public void trimTail_trailingNewlines_goAway() {
        assertEquals("text", NoteStrip.trimTail("text\n\n\n"));
        assertEquals("text", NoteStrip.trimTail("text \n\t "));
        assertEquals("text", NoteStrip.trimTail("text"));
    }

    @Test
    public void trimTail_leadingBlankLines_goAway_spacesStay() {
        assertEquals("text", NoteStrip.trimTail("\n\r\ntext"));
        assertEquals("  indented", NoteStrip.trimTail("  indented"));
    }

    @Test
    public void trimTail_interiorWhitespace_untouched() {
        String s = "para one\n\npara two\n\ncode:\n\n  x = 1";
        assertEquals(s, NoteStrip.trimTail(s));
    }

    @Test
    public void trimTail_blankAndEmpty_andNull() {
        assertEquals("", NoteStrip.trimTail("  \n\n "));
        assertEquals("", NoteStrip.trimTail(""));
        assertNull(NoteStrip.trimTail(null));
    }

    @Test
    public void trimTail_untouchedInput_returnsSameReference() {
        String s = "clean";
        assertTrue(s == NoteStrip.trimTail(s));
    }

    // -------------------------------------- user bubble paints the words

    @Test
    public void upsertUser_wireText_rowShowsTheMessageNotTheNote()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ctl.setup().get();
            String wire = EnvNote.note(true) + "\n\n"
                    + TerseMode.note(true) + "\n\nhello there";
            RunHub.upsertUser(RunHub.tx(), "m1|p1", wire);
            idle();
            List<RunHub.Row> rows = RunHub.rows();
            assertEquals(1, rows.size());
            assertEquals("hello there", rows.get(0).text.toString());
            // and a mid-session re-upsert (the server echoing the same
            // part with the full wire text again) stays clean
            RunHub.upsertUser(RunHub.tx(), "m1|p1", wire);
            idle();
            assertEquals("hello there", rows.get(0).text.toString());
        }
    }

    @Test
    public void upsertUser_userTypingTheirOwnReminderBlock_keepsIt()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ctl.setup().get();
            String own = "<system-reminder>my own words</system-reminder>\n\nhi";
            RunHub.upsertUser(RunHub.tx(), "m1|p1", own);
            idle();
            assertEquals(own, RunHub.rows().get(0).text.toString());
        }
    }

    // ------------------------------------ told-state survives the process

    @Test
    public void toldMap_roundTrip_throughTheDiskShape() throws Exception {
        Map<String, Boolean> src = new java.util.LinkedHashMap<>();
        src.put("ses-1", Boolean.TRUE);
        src.put("ses-2", Boolean.FALSE);
        Method json = RunHub.class.getDeclaredMethod("toldJson", Map.class);
        json.setAccessible(true);
        String j = (String) json.invoke(null, src);
        assertEquals("{\"ses-1\":true,\"ses-2\":false}", j);

        Method parse = RunHub.class.getDeclaredMethod("restoreToldMap",
                Map.class, String.class, Map.class);
        parse.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) Json.obj(
                Json.parse("{\"v\":1,\"env\":{\"ses-9\":true},\"style\":{\"ses-1\":false}}"));
        Map<String, Boolean> into = new java.util.LinkedHashMap<>();
        into.put("stale", Boolean.TRUE);
        parse.invoke(null, root, "style", into);
        // merge semantics: at boot the map is empty, so merge == replace;
        // pre-existing keys are never disturbed by a section refill
        assertEquals(2, into.size());
        assertEquals(Boolean.FALSE, into.get("ses-1"));
        assertEquals(Boolean.TRUE, into.get("stale"));
        parse.invoke(null, root, "env", into);
        assertEquals(Boolean.TRUE, into.get("ses-9"));
        assertEquals(Boolean.FALSE, into.get("ses-1"));   // untouched
        // a missing/unknown section clears nothing
        parse.invoke(null, root, "render", into);
        assertEquals(Boolean.TRUE, into.get("ses-9"));
    }

    @Test
    public void toldMaps_restoreFeedsTheGateTheSendReads() throws Exception {
        // envTold filled from a snapshot means EnvNote.needsNote sees TRUE
        // — the morning-after continue sends no second map
        Field f = RunHub.class.getDeclaredField("envTold");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> envTold = (Map<String, Boolean>) f.get(null);
        envTold.put("ses-morning", Boolean.TRUE);
        assertFalse(EnvNote.needsNote(envTold.get("ses-morning")));
        assertTrue(EnvNote.needsNote(envTold.get("ses-other")));
        envTold.remove("ses-morning");
    }
}
