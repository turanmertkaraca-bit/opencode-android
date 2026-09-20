package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P45 — the "act normal" release, pinned where each piece lives.
 *
 * FIELD VERDICT DRIVING THIS RELEASE (v0.44.0): three demands, all
 * hard. ONE — the app must never silently summarize a chat's memory:
 * the kill switch is compaction.auto=false
 * in opencode.json (the bundled server's own escape hatch, found in
 * its embedded source: the overflow check short-circuits to "do not
 * compact" and an overflow turn errors instead of summarizing), pinned
 * by the app at every server boot, DEFAULT OFF. TWO — "the default
 * pallet is graphite change it back": the P44 claude face is removed
 * outright and the default face is Graphite again (the one-time p45
 * migration carries p44-migrated riders back). THREE — cold boot lands
 * on the project deck, never the last chat (MainActivity).
 *
 * Pure logic here; prefs/config paths run under the Robolectric shadow
 * (the P33UiTest pattern).
 */
@RunWith(RobolectricTestRunner.class)
public class P45Test {

    // ================================== 1. CompactionPolicy.mergeAuto

    @Test public void mergeAuto_nullRootNeverChanges() {
        assertFalse(CompactionPolicy.mergeAuto(null, false, -1, false));
    }

    @Test public void mergeAuto_junkCompactionBlockIsHandsOff() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compaction", "not-a-map");
        assertFalse(CompactionPolicy.mergeAuto(root, false, -1, false));
        assertEquals("not-a-map", root.get("compaction"));
    }

    @Test public void mergeAuto_absentBlock_writesTheSwitch() {
        Map<String, Object> root = new LinkedHashMap<>();
        assertTrue(CompactionPolicy.mergeAuto(root, false, -1, false));
        Map<?, ?> comp = (Map<?, ?>) root.get("compaction");
        assertEquals(Boolean.FALSE, comp.get("auto"));
    }

    @Test public void mergeAuto_matchingValue_isNoOp() {
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("auto", Boolean.FALSE);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compaction", comp);
        assertFalse("already off — nothing to write",
                CompactionPolicy.mergeAuto(root, false, -1, false));
        assertFalse("already on — nothing to write",
                CompactionPolicy.mergeAuto(root, true, 1, false));
    }

    @Test public void mergeAuto_userOwnedValue_survivesBoot() {
        // the user hand-set auto:true in opencode.json; the app wrote
        // false last time (appWritten=0). Boot must NEVER touch it.
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("auto", Boolean.TRUE);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compaction", comp);
        assertFalse(CompactionPolicy.mergeAuto(root, false, 0, false));
        assertEquals(Boolean.TRUE, comp.get("auto"));
    }

    @Test public void mergeAuto_userOwnedValue_forcedByToggle() {
        // the same state, but this time the USER FLIPPED THE SWITCH —
        // the click is the newest intent and the write goes through.
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("auto", Boolean.TRUE);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compaction", comp);
        assertTrue(CompactionPolicy.mergeAuto(root, false, 0, true));
        assertEquals(Boolean.FALSE, comp.get("auto"));
    }

    @Test public void mergeAuto_appWrittenTrue_canBeReOffedAtBoot() {
        // the app itself wrote true last time (a switch ON session) and
        // the switch now reads OFF — the boot bring-in-line applies.
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("auto", Boolean.TRUE);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compaction", comp);
        assertTrue(CompactionPolicy.mergeAuto(root, false, 1, false));
        assertEquals(Boolean.FALSE, comp.get("auto"));
    }

    @Test public void mergeAuto_nonBooleanAutoJunk_isHandsOffAtBoot() {
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("auto", "yes-please");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compaction", comp);
        assertFalse(CompactionPolicy.mergeAuto(root, false, -1, false));
        assertEquals("yes-please", comp.get("auto"));
        // but the toggle forces through junk too — the switch owns it
        assertTrue(CompactionPolicy.mergeAuto(root, false, -1, true));
        assertEquals(Boolean.FALSE, comp.get("auto"));
    }

    @Test public void mergeAuto_preserveKeyNeverTouched() {
        // the P41 floor rides the same block — the switch must not
        // disturb it
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("preserve_recent_tokens", 30_000L);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compaction", comp);
        assertTrue(CompactionPolicy.mergeAuto(root, false, -1, true));
        assertEquals(30_000L, comp.get("preserve_recent_tokens"));
    }

    // ================================== 2. riskNote — the two modes

    @Test public void riskNote_offMode_namesTheOverflowTruth() {
        String s = CompactionPolicy.riskNote(90_000, 128_000, false);
        assertNotNull(s);
        assertTrue(s.contains("auto-compact is off"));
        assertTrue(s.contains("overflow error"));
        assertTrue("never threatens a summarize in off mode",
                !s.contains("summarizes this chat's memory"));
    }

    @Test public void riskNote_onMode_keepsTheLegacyWarn() {
        String s = CompactionPolicy.riskNote(90_000, 128_000, true);
        assertNotNull(s);
        assertTrue(s.contains("compaction is close"));
        // the two-arg form is the ON-mode alias (pre-P45 pins keep passing)
        assertEquals(s, CompactionPolicy.riskNote(90_000, 128_000));
    }

    @Test public void riskNote_belowSeventyPercent_isNull_bothModes() {
        assertNull(CompactionPolicy.riskNote(89_000, 128_000, false));
        assertNull(CompactionPolicy.riskNote(89_000, 128_000, true));
        assertNull(CompactionPolicy.riskNote(0, 128_000, false));
        assertNull(CompactionPolicy.riskNote(90_000, 0, true));
    }

    // ================================== 3. the default face + migration

    @Test public void defaultFace_isGraphite_andClaudeIsGone() {
        assertEquals("graphite", Theme.DEFAULT_PALETTE);
        assertEquals(6, Theme.PALETTES.length);
        assertFalse(java.util.Arrays.asList(Theme.PALETTES).contains("claude"));
    }

    @Test public void p45Migration_carriesP44RidersBackToGraphite() {
        try (ActivityController<android.app.Activity> ctl =
                     Robolectric.buildActivity(android.app.Activity.class)) {
            android.app.Activity a = ctl.setup().get();
            android.content.SharedPreferences sp =
                    a.getSharedPreferences("oc", android.content.Context.MODE_PRIVATE);
            // a device the theme_migrated_p44 push dragged onto claude;
            // the flag is reset too — the app-start path may already have
            // consumed it in this shadow process
            sp.edit().putString("theme", "claude")
                    .putBoolean("theme_migrated_p45", false).commit();
            assertEquals("graphite", Theme.currentId(a));
            assertEquals("the migration REWRITES the pref (one time)",
                    "graphite", sp.getString("theme", null));
            // the migration is one-time: a later explicit pick stands
            sp.edit().putString("theme", "paper").apply();
            assertEquals("paper", Theme.currentId(a));
        }
    }

    @Test public void p45Migration_neverTouchesOtherExplicitPicks() {
        try (ActivityController<android.app.Activity> ctl =
                     Robolectric.buildActivity(android.app.Activity.class)) {
            android.app.Activity a = ctl.setup().get();
            android.content.SharedPreferences sp =
                    a.getSharedPreferences("oc", android.content.Context.MODE_PRIVATE);
            sp.edit().putString("theme", "ember")
                    .putBoolean("theme_migrated_p45", false).commit();
            assertEquals("ember", Theme.currentId(a));
            assertEquals("ember", sp.getString("theme", null));
        }
    }

    // ================================== 4. the AuthStore switch plumbing

    @Test public void compactionAuto_defaultsOff() {
        try (ActivityController<android.app.Activity> ctl =
                     Robolectric.buildActivity(android.app.Activity.class)) {
            android.app.Activity a = ctl.setup().get();
            assertFalse("OFF is the shipped default — the sandbox never "
                    + "summarizes memory behind the user's back",
                    AuthStore.compactionAuto(a));
        }
    }

    @Test public void setThenEnsure_roundTripsThroughOpencodeJson() throws Exception {
        try (ActivityController<android.app.Activity> ctl =
                     Robolectric.buildActivity(android.app.Activity.class)) {
            android.app.Activity a = ctl.setup().get();

            // toggle ON → the file carries auto:true, ownership recorded
            assertTrue(AuthStore.setCompactionAuto(a, true));
            Map<String, Object> cfg = AuthStore.readConfig(a);
            assertEquals(Boolean.TRUE,
                    ((Map<?, ?>) cfg.get("compaction")).get("auto"));
            assertEquals(1, a.getSharedPreferences("oc",
                    android.content.Context.MODE_PRIVATE)
                    .getInt("compaction_auto_app", -1));
            assertTrue(AuthStore.compactionAuto(a));

            // boot ensure agrees → no second write
            assertFalse(AuthStore.ensureCompactionAuto(a));

            // toggle OFF → the file flips, ownership recorded
            assertTrue(AuthStore.setCompactionAuto(a, false));
            cfg = AuthStore.readConfig(a);
            assertEquals(Boolean.FALSE,
                    ((Map<?, ?>) cfg.get("compaction")).get("auto"));
            assertEquals(0, a.getSharedPreferences("oc",
                    android.content.Context.MODE_PRIVATE)
                    .getInt("compaction_auto_app", -1));
            assertFalse(AuthStore.compactionAuto(a));

            // boot ensure agrees → no second write
            assertFalse(AuthStore.ensureCompactionAuto(a));
        }
    }

    @Test public void ensureWritesAbsentKey_toMatchTheOffSwitch() throws Exception {
        try (ActivityController<android.app.Activity> ctl =
                     Robolectric.buildActivity(android.app.Activity.class)) {
            android.app.Activity a = ctl.setup().get();
            // a config with a user compaction block but no auto key
            Map<String, Object> cfg = AuthStore.readConfig(a);
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("preserve_recent_tokens", 24_000L);
            cfg.put("compaction", comp);
            java.lang.reflect.Method w = AuthStore.class.getDeclaredMethod(
                    "writeConfig", android.content.Context.class, Map.class);
            w.setAccessible(true);
            w.invoke(null, a, cfg);

            assertTrue("the absent key is filled to match the switch",
                    AuthStore.ensureCompactionAuto(a));
            Map<String, Object> after = AuthStore.readConfig(a);
            assertEquals(Boolean.FALSE,
                    ((Map<?, ?>) after.get("compaction")).get("auto"));
            assertEquals("the P41 floor key survives the switch write",
                    24_000L,
                    ((Number) ((Map<?, ?>) after.get("compaction"))
                            .get("preserve_recent_tokens")).longValue());
        }
    }
}
