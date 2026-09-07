package ai.opencode.app;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P32 — the theme crash, pinned at the UI layer.
 *
 * The field report: "pressing the theme change button causes a crash".
 * Root cause: pickTheme() built its rows through a dead
 * (LinearLayout) cast of android.R.layout.simple_list_item_1 — which
 * inflates to a TextView. ClassCastException on EVERY tap of the Theme
 * row, before the sheet ever opened; the picker was unreachable in the
 * field for the whole P31 cycle.
 *
 * These pins make sure: tapping Theme opens the sheet; tapping a
 * palette applies it (prefs + statics); and a screen that was open
 * during a theme switch re-skins itself on resume (syncIfNeeded).
 */
@RunWith(RobolectricTestRunner.class)
public class P32UiTest {

    // ---- view-tree helpers ------------------------------------------------

    private static TextView findText(View root, String contains) {
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            if (tv.getText() != null
                    && tv.getText().toString().contains(contains)) return tv;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findText(g.getChildAt(i), contains);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static View decorOf(android.app.Activity a) {
        return a.getWindow().getDecorView();
    }

    /** Climb from a TextView to its clickable ancestor — rowLink puts
     *  the click listener on the ROW, one level above the title column. */
    private static View clickableOf(View v) {
        View cur = v;
        while (cur != null && !cur.isClickable()) {
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return cur;
    }

    // ---- THE crash pin ----------------------------------------------------

    @Test
    public void themeRow_opensThePicker_insteadOfCrashing() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity a = ctl.setup().get();

            TextView row = findText(decorOf(a), "Theme");
            assertNotNull("the Theme row must exist in Settings", row);

            // THE PIN: this exact interaction threw ClassCastException in
            // P31 (the dead simple_list_item_1 cast). It must open the
            // sheet — and it must not throw.
            View clickable = clickableOf(row);
            assertNotNull("the row must be clickable", clickable);
            clickable.performClick();

            AlertDialog dlg = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull("tapping Theme must open the picker dialog", dlg);
            assertTrue(dlg.isShowing());

            // the six palettes are all present and named
            assertNotNull("Paper row present", findText(dlg.getWindow().getDecorView(), "Paper"));
            assertNotNull("OLED row present", findText(dlg.getWindow().getDecorView(), "OLED black"));
        }
    }

    @Test
    public void pickingAPalette_persistsThePref_andAppliesTheStatics() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity a = ctl.setup().get();

            TextView themeRow = findText(decorOf(a), "Theme");
            View open = clickableOf(themeRow);
            assertNotNull(open);
            open.performClick();
            AlertDialog dlg = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(dlg);

            TextView paper = findText(dlg.getWindow().getDecorView(), "Paper");
            assertNotNull(paper);
            View pick = clickableOf(paper);
            assertNotNull(pick);
            pick.performClick();

            // the pref is the source of truth — and the statics follow it
            assertEquals("paper", Theme.currentId(a));
            assertEquals(0xFFF3F1EA, Theme.BG);
            assertTrue(Theme.isLight());
            // persisted for the next process, too
            assertEquals("paper", a.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getString("theme", null));
        }
    }

    // ---- the whole-app-follows-the-theme guard ----------------------------

    @Test
    public void syncIfNeeded_reskinsAStaleScreen_andNeverLoops() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity a = ctl.setup().get();

            // the screen is coherent with the pref — no recreate
            assertFalse(Theme.syncIfNeeded(a));
            // P33: the pref-less default is Graphite now (was oled)
            assertEquals(0xFF0A0A0B, Theme.BG);

            // a theme switch happened elsewhere while this screen was open
            a.getSharedPreferences("oc", Context.MODE_PRIVATE).edit()
                    .putString("theme", "paper").apply();

            assertTrue("stale screen must reskin on resume",
                    Theme.syncIfNeeded(a));
            assertEquals("statics must already be the NEW palette",
                    0xFFF3F1EA, Theme.BG);

            // idempotent: the second resume must NOT recreate again
            assertFalse(Theme.syncIfNeeded(a));
        }
    }

    @Test
    public void unknownThemePref_fallsBackSanely_withoutCrash() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity a = ctl.setup().get();
            a.getSharedPreferences("oc", Context.MODE_PRIVATE).edit()
                    .putString("theme", "garbage").apply();
            // garbage resolves to oled (the sanity floor) — a screen built
            // on the graphite DEFAULT is legitimately stale now, so the
            // reskin fires once; the pin is that the SECOND call is a
            // no-op (no loop, no crash) and the statics are coherent.
            Theme.syncIfNeeded(a);
            assertEquals(0xFF000000, Theme.BG);
            assertFalse(Theme.syncIfNeeded(a));
        }
    }
}
