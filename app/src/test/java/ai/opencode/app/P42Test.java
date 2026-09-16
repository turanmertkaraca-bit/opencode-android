package ai.opencode.app;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P42 — the honesty release, pinned where each piece lives.
 *
 * FIELD REPORT DRIVING THIS RELEASE (v0.41.0, a 3-hour session): the
 * agent spent real money in loops trying to "fix its environment" —
 * curl TLS was dead in every shell mode (no CA bundle was ever exported),
 * the env note the agent reads actively told it to run
 * "apt-get install ca-certificates" (the exact loop), deleted watcher
 * rows painted "20090 d ago" (a unit-inversion between age-seconds and
 * epoch-seconds), the sessions sheet said "just now" forever, and both
 * the context meter and the money figures could silently under-count
 * after a restart or a background session.
 *
 * These tests pin the fixes at the pure-logic layer; see the worklog
 * for the full file:line map.
 *
 * 1. Resilience.ago — the unit-explicit relative-time renderer.
 */
public class P42Test {

    private static final long NOW = 1_700_000_040_000L; // fixed "now"

    // ---------------------------------------------- 1. Resilience.ago

    @Test public void ago_unitsAreExplicit_epochMsIn_notAge() {
        // the field bug: a caller passed (now - ts)/1000 (an AGE in
        // seconds) into a helper that multiplied by 1000 again and
        // diffed against the epoch → "20090 d ago". ago() takes the
        // epoch directly, so an event 30 seconds old reads "just now".
        assertEquals("just now", Resilience.ago(NOW - 30_000L, NOW));
        // and a 20-year-old timestamp reads in days, not "20000 days"
        assertEquals("7305 d ago", Resilience.ago(NOW - 7305L * 86_400_000L, NOW));
    }

    @Test public void ago_scale() {
        assertEquals("just now", Resilience.ago(NOW, NOW));
        assertEquals("just now", Resilience.ago(NOW - 59_999L, NOW));
        assertEquals("1 min ago", Resilience.ago(NOW - 60_000L, NOW));
        assertEquals("59 min ago", Resilience.ago(NOW - 59L * 60_000L, NOW));
        assertEquals("1 h ago", Resilience.ago(NOW - 3_600_000L, NOW));
        assertEquals("23 h ago", Resilience.ago(NOW - 23L * 3_600_000L, NOW));
        assertEquals("1 d ago", Resilience.ago(NOW - 86_400_000L, NOW));
        assertEquals("2 d ago", Resilience.ago(NOW - 2L * 86_400_000L, NOW));
    }

    @Test public void ago_unknownTime_rendersDash_notTwentyThousandDays() {
        // deleted file row / session with no time map: lastModified()==0
        // used to paint ~20090 d ago; now it renders an honest "—"
        assertEquals("—", Resilience.ago(0, NOW));
        assertEquals("—", Resilience.ago(-1, NOW));
    }

    @Test public void ago_futureTimestamp_neverNegative() {
        // clock skew: a timestamp slightly ahead of "now" is "just now",
        // never a negative burst
        assertEquals("just now", Resilience.ago(NOW + 5_000L, NOW));
    }

    // ---------------------------------------- 2. Resilience.pctFloor

    @Test public void pctFloor_oneRuleForEverySurface() {
        // the field inconsistency: the pill ROUNDED (69.5% → "70%")
        // while the compaction warning FLOORED (69 < 70, silent) — the
        // warning lagged the number the user could see. One rule now.
        assertEquals(69, Resilience.pctFloor(695, 1000));
        assertEquals(70, Resilience.pctFloor(700, 1000));
        assertEquals(0, Resilience.pctFloor(0, 1000));
        assertEquals(0, Resilience.pctFloor(48, 0));      // unknown window
        assertEquals(100, Resilience.pctFloor(2000, 1000)); // over → clamp
    }

    @Test public void contextMeter_usesTheSharedFloor() {
        // 69.5% of 200k = 139k: used to round up to "70%"; now floors
        assertEquals("139k / 200k · 69%",
                Resilience.contextMeter(139_000, 200_000));
    }

    // --------------------------------- 3. ContextPolicy (the slider)

    private static Map<String, Object> cfgWithModel() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> provs = new LinkedHashMap<>();
        Map<String, Object> pdef = new LinkedHashMap<>();
        Map<String, Object> models = new LinkedHashMap<>();
        Map<String, Object> mdef = new LinkedHashMap<>();
        mdef.put("name", "test-model");
        models.put("test-model", mdef);
        pdef.put("models", models);
        provs.put("prov", pdef);
        root.put("provider", provs);
        return root;
    }

    @Test public void contextPolicy_normalizeBoundsAndQuantizes() {
        assertEquals(0, ContextPolicy.normalize(0));
        assertEquals(0, ContextPolicy.normalize(-5));
        assertEquals(ContextPolicy.MIN, ContextPolicy.normalize(1));
        assertEquals(ContextPolicy.MIN, ContextPolicy.normalize(63_999));
        assertEquals(64_000, ContextPolicy.normalize(64_900));   // 1k quantum, down
        assertEquals(ContextPolicy.MAX, ContextPolicy.normalize(9_999_999));
    }

    @Test public void contextPolicy_mergeWritesModelsDevShape() {
        Map<String, Object> root = cfgWithModel();
        assertTrue(ContextPolicy.mergeContextLimit(root, "prov", "test-model", 130_123));
        // the shape the bundled server parses natively ("limit.context",
        // 18 hits in the binary) — and Models.parseCtx reads back
        Map<String, Object> provs = (Map<String, Object>) root.get("provider");
        Map<String, Object> pdef = (Map<String, Object>) provs.get("prov");
        Map<String, Object> models = (Map<String, Object>) pdef.get("models");
        Map<String, Object> mdef = (Map<String, Object>) models.get("test-model");
        Map<String, Object> limit = (Map<String, Object>) mdef.get("limit");
        assertEquals(130_000L, limit.get("context"));   // quantized
        // idempotent: same value → no change
        assertFalse(ContextPolicy.mergeContextLimit(root, "prov", "test-model", 130_456));
        assertEquals(ContextPolicy.readContextLimit(root, "prov", "test-model"), 130_000L);
    }

    @Test public void contextPolicy_clearRemovesOverrideAndTidies() {
        Map<String, Object> root = cfgWithModel();
        assertTrue(ContextPolicy.mergeContextLimit(root, "prov", "test-model", 128_000));
        assertTrue(ContextPolicy.mergeContextLimit(root, "prov", "test-model", 0));
        assertEquals(0, ContextPolicy.readContextLimit(root, "prov", "test-model"));
        // the model's own "name" survives; the limit shell is gone
        Map<String, Object> provs = (Map<String, Object>) root.get("provider");
        Map<String, Object> pdef = (Map<String, Object>) provs.get("prov");
        Map<String, Object> models = (Map<String, Object>) pdef.get("models");
        Map<String, Object> mdef = (Map<String, Object>) models.get("test-model");
        assertFalse(mdef.containsKey("limit"));
        // clearing when nothing is set changes nothing
        assertFalse(ContextPolicy.mergeContextLimit(root, "prov", "test-model", 0));
    }

    @Test public void contextPolicy_userJunkNodesAreNeverTouched() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> provs = new LinkedHashMap<>();
        provs.put("prov", "not-a-map");                 // user junk
        root.put("provider", provs);
        assertFalse(ContextPolicy.mergeContextLimit(root, "prov", "m", 128_000));
        assertEquals("not-a-map", provs.get("prov"));   // untouched
    }

    // ----------------------- 4. CompactionPolicy.mergeOrMovePreserve

    @Test public void mergeOrMove_movesAnAppOwnedFloor_neverAUserValue() {
        // the field hazard: the floor was pinned once per boot for the
        // boot-time model; switching to a smaller-window model left a 60k
        // floor on a 64k window — every compaction a no-op.
        Map<String, Object> root = new LinkedHashMap<>();
        long v1 = CompactionPolicy.preserveRecentTokensFor(1_000_000);
        assertTrue(v1 > 0);
        root.put("compaction", new LinkedHashMap<String, Object>());
        ((Map<String, Object>) root.get("compaction")).put("preserve_recent_tokens", v1);
        // app-owned (equals what the app wrote) → moves to the new window
        long v2 = CompactionPolicy.preserveRecentTokensFor(128_000);
        assertTrue(v2 > 0 && v2 != v1);
        assertEquals(v2, CompactionPolicy.mergeOrMovePreserve(root, 128_000, v1));
        // user-edited since (file value ≠ what the app last wrote) → never
        // touched, even though the app once owned a value in there
        assertEquals(0, CompactionPolicy.mergeOrMovePreserve(root, 64_000, v1));
        Map<String, Object> comp = (Map<String, Object>) root.get("compaction");
        assertEquals(v2, ((Number) comp.get("preserve_recent_tokens")).longValue());
        // no ownership record → hands off even if it "looks" app-written
        Map<String, Object> root2 = new LinkedHashMap<>();
        Map<String, Object> comp2 = new LinkedHashMap<>();
        comp2.put("preserve_recent_tokens", v2);
        root2.put("compaction", comp2);
        assertEquals(0, CompactionPolicy.mergeOrMovePreserve(root2, 64_000, 0));
        assertEquals(v2, ((Number) comp2.get("preserve_recent_tokens")).longValue());
    }

    // --------------------------------- 12. P42-check final-pass pins

    @Test public void effectiveWindow_theCapShrinksWhatTheFloorAssumes() {
        // the check-pass hazard: the slider caps a 200k model at 64k but
        // the floor still pinned from the full window → preserve_recent
        // clamps to 60k on a 64k enforced window → every compaction a
        // no-op. The SMALLER of model window and user cap must win.
        assertEquals(64_000, CompactionPolicy.effectiveWindow(200_000, 64_000));
        assertEquals(128_000, CompactionPolicy.effectiveWindow(200_000, 128_000));
        assertEquals(200_000, CompactionPolicy.effectiveWindow(200_000, 0));
        assertEquals(64_000, CompactionPolicy.effectiveWindow(64_000, 200_000));
        // unknown model window with a cap → the cap alone is the truth
        assertEquals(128_000, CompactionPolicy.effectiveWindow(0, 128_000));
        // both unknown → 0 (do not write)
        assertEquals(0, CompactionPolicy.effectiveWindow(0, 0));
        // and the produced window always yields a live floor or none
        long win = CompactionPolicy.effectiveWindow(200_000, 64_000);
        long floor = CompactionPolicy.preserveRecentTokensFor(win);
        assertTrue(floor > 0 && floor < win);
    }

    @Test public void fmtTok_neverRoundsUpTo1000k() {
        // the check-pass find: %.0fk rounded 999.999k up to "1000k"
        assertEquals("1.00M", Resilience.fmtTok(999_999));
        assertEquals("1.00M", Resilience.fmtTok(996_000));
        // below the step the old shapes hold exactly
        assertEquals("994k", Resilience.fmtTok(994_000));
        assertEquals("990k", Resilience.fmtTok(990_000));
        assertEquals("48.0k", Resilience.fmtTok(48_000));
        assertEquals("48.7k", Resilience.fmtTok(48_700));
        assertEquals("999", Resilience.fmtTok(999));
        assertEquals("2.0M", Resilience.fmtTok(2_000_000));
    }
}
