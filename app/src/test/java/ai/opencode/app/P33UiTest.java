package ai.opencode.app;

import android.app.Dialog;
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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * P33 — the theme system, pinned at the UI layer:
 *
 *   1. THE FIELD-SILENT SYNC, FIXED. P32's syncIfNeeded compared the pref
 *      against the PROCESS-GLOBAL static — which Settings' own apply()
 *      had already updated, so no other screen could ever be "stale" and
 *      the whole-app palette sync never fired in the field. The palette
 *      id now rides a per-screen decor stamp; these pins prove a screen
 *      built on one palette goes stale (and re-skins exactly once) when
 *      the pref moves on.
 *   2. THE INSTANT THEME CHANGE. Picking a palette in Settings rebuilds
 *      the screen IN PLACE — the window/decor identity survives (no
 *      recreate() teardown), the statics are the new palette, the sheet
 *      is gone, the content tree is fresh. The "takes a while, feels
 *      bad" wait is the recreate; this pin outlaws its return.
 *   3. THE MODEL SHEET NEVER LIES ABOUT KEYS. showModels re-reads
 *      auth.json at open; a saved key can't be called missing (UI-level
 *      cover for the "app thinks i have no api key" report).
 */
@RunWith(RobolectricTestRunner.class)
public class P33UiTest {

    // ---- helpers ----------------------------------------------------------

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

    private static View clickableOf(View v) {
        View cur = v;
        while (cur != null && !cur.isClickable()) {
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return cur;
    }

    // ---- 1. the per-screen stamp + one-shot reskin ------------------------

    @Test
    public void freshScreen_isStampedWithThePaletteItWasBuiltOn() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity a = ctl.setup().get();
            assertEquals("the stamp records what this screen was built with",
                    Theme.currentId(a), Theme.appliedIdOf(a));
            assertFalse("a fresh screen is never stale",
                    Theme.isStale(a));
        }
    }

    @Test
    public void prefMoveMakesTheScreenStale_andTheReskinRestampsExactlyOnce() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity a = ctl.setup().get();
            String builtWith = Theme.appliedIdOf(a);

            // the theme changed elsewhere (pref edited without this screen)
            a.getSharedPreferences("oc", Context.MODE_PRIVATE).edit()
                    .putString("theme", "ember").apply();

            assertTrue("the screen must be stale now", Theme.isStale(a));
            assertEquals("the stamp holds the OLD palette until the reskin",
                    builtWith, Theme.appliedIdOf(a));

            assertTrue("the resume reskin fires", Theme.syncIfNeeded(a));
            assertEquals("after the reskin the stamp is the NEW palette",
                    "ember", Theme.appliedIdOf(a));
            assertFalse("exactly once — the next resume is a no-op",
                    Theme.syncIfNeeded(a));
        }
    }

    // ---- 2. the instant in-place theme change ------------------------------

    @Test
    public void pickingATheme_reskinsInPlace_noRecreate_noDeadAir() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity a = ctl.setup().get();

            View decorBefore = decorOf(a);
            View contentBefore = findContentView(decorBefore);

            // open the picker
            TextView themeRow = findText(decorBefore, "Theme");
            assertNotNull(themeRow);
            View open = clickableOf(themeRow);
            assertNotNull(open);
            open.performClick();
            Dialog dlg = (Dialog) ShadowDialog.getLatestDialog();
            assertNotNull(dlg);
            assertTrue(dlg.isShowing());

            // pick Paper
            TextView paper = findText(dlg.getWindow().getDecorView(), "Paper");
            assertNotNull(paper);
            View pick = clickableOf(paper);
            assertNotNull(pick);
            pick.performClick();

            // THE PIN: same window, same decor — the activity was never
            // torn down. The old recreate() flow produced a NEW decor and
            // seconds of dead air; in-place means instant.
            assertSame("the decor must survive the theme change",
                    decorBefore, decorOf(a));
            assertFalse("the picker sheet is gone",
                    ShadowDialog.getLatestDialog().isShowing());

            // the statics ARE the new palette, and the content tree was
            // genuinely rebuilt from the new tokens (a fresh root view)
            assertEquals("paper", Theme.currentId(a));
            assertEquals(0xFFF3F1EA, Theme.BG);
            assertTrue(Theme.isLight());
            View contentAfter = findContentView(decorOf(a));
            assertNotNull(contentAfter);
            assertNotSame("the content tree was rebuilt from the new tokens",
                    contentBefore, contentAfter);
        }
    }

    @Test
    public void freshDefault_isGraphite_andThePickerSaysSo() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity a = ctl.setup().get();

            // the pref-less default face of the final version
            assertEquals("graphite", Theme.currentId(a));
            assertEquals(0xFF0A0A0B, Theme.BG);

            // open the picker — the Graphite row is checked AND labeled
            TextView themeRow = findText(decorOf(a), "Theme");
            clickableOf(themeRow).performClick();
            Dialog dlg = (Dialog) ShadowDialog.getLatestDialog();
            assertNotNull(dlg);
            TextView row = findText(dlg.getWindow().getDecorView(), "Graphite");
            assertNotNull(row);
            assertTrue("the default row carries the '· default' label",
                    row.getText().toString().contains("default"));
            assertTrue("the active row carries the check",
                    row.getText().toString().contains("✓"));
        }
    }

    // ---- 3. the model sheet reads auth at open ----------------------------

    @Test
    public void modelSheetFlags_comeFromAuthTruthAtOpenTime() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();

            // a provider with a STALE configured=false flag in the fetch
            Models.Prov p = new Models.Prov();
            p.id = "openai";
            p.name = "OpenAI";
            p.configured = false;                    // lastFetch's old truth
            Models.Mdl m = new Models.Mdl();
            m.id = "gpt-test";
            m.name = "gpt-test";
            p.models.add(m);
            java.util.List<Models.Prov> provs = new java.util.ArrayList<>();
            provs.add(p);

            // write a real key so auth.json disagrees with the stale flag
            AuthStore.setApiKey(a, "openai", "sk-test-1234567890abcdef");
            try {
                java.lang.reflect.Method show = ChatActivity.class
                        .getDeclaredMethod("showModels", java.util.List.class);
                show.setAccessible(true);
                show.invoke(a, provs);

                // THE PIN: the sheet re-read auth.json at open — the provider
                // that said "needs its own key" now reads configured=true.
                assertTrue("a saved key is never 'missing' in the sheet",
                        ((Models.Prov) provs.get(0)).configured);
            } finally {
                // keep the suite hermetic: drop the key this test wrote
                AuthStore.setApiKey(a, "openai", "");
            }
        }
    }

    // ---- view helper -------------------------------------------------------

    private static View findContentView(View root) {
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof android.widget.ScrollView
                        && c.getParent() == root) return c;
                View deep = findContentView(c);
                if (deep != null) return deep;
            }
        }
        return null;
    }
}
