package ai.opencode.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P36 — the launcher icon follows the theme. Pins live on the PURE
 * parts: the theme→alias mapping and the exactly-one-enabled plan.
 * The Android shell around them (two contained methods calling
 * PackageManager) is unreachable from the JVM suite — the house rule
 * since P24 — so it stays as thin as possible: read state, call the
 * pinned plan, fire the ops, log on any failure.
 */
public class P36Test {

    // ------------------------------------------------------------ helpers

    private static Map<String, Boolean> allAliases() {
        LinkedHashMap<String, Boolean> m = new LinkedHashMap<>();
        m.put(LauncherIcon.CLASSIC, false);
        for (String a : LauncherIcon.THEME_ALIASES) m.put(a, false);
        return m;
    }

    /** Full state map with exactly the given aliases enabled. */
    private static Map<String, Boolean> state(String... enabled) {
        Map<String, Boolean> m = allAliases();
        for (String e : enabled) {
            if (!m.containsKey(e)) throw new IllegalArgumentException(e);
            m.put(e, true);
        }
        return m;
    }

    private static String[] enable(String alias) {
        return new String[]{alias, "enable"};
    }

    private static String[] disable(String alias) {
        return new String[]{alias, "disable"};
    }

    private static String assertOpsContain(String[][] ops, String alias, String kind) {
        for (String[] op : ops)
            if (op[0].equals(alias) && op[1].equals(kind)) return alias;
        throw new AssertionError("missing op " + kind + " " + alias
                + " in " + Arrays.deepToString(ops));
    }

    // ------------------------------------------------------------- table

    @Test
    public void aliasTable_matchesPaletteTable() {
        // a future palette added without an alias must fail here, not
        // silently fall back to the classic icon in production
        assertEquals(Theme.PALETTES.length, LauncherIcon.THEME_ALIASES.length);
        for (String a : LauncherIcon.THEME_ALIASES)
            assertTrue("alias name must be non-empty", a != null && !a.isEmpty());
    }

    // ------------------------------------------------------------ mapping

    @Test
    public void aliasMapping_allSixPalettes() {
        assertEquals("LauncherOled", LauncherIcon.aliasFor("oled"));
        assertEquals("LauncherMidnight", LauncherIcon.aliasFor("midnight"));
        assertEquals("LauncherGraphite", LauncherIcon.aliasFor("graphite"));
        assertEquals("LauncherEmber", LauncherIcon.aliasFor("ember"));
        assertEquals("LauncherForest", LauncherIcon.aliasFor("forest"));
        assertEquals("LauncherPaper", LauncherIcon.aliasFor("paper"));
    }

    @Test
    public void aliasMapping_nullAndGarbageFallToClassic() {
        assertEquals(LauncherIcon.CLASSIC, LauncherIcon.aliasFor(null));
        assertEquals(LauncherIcon.CLASSIC, LauncherIcon.aliasFor(""));
        assertEquals(LauncherIcon.CLASSIC, LauncherIcon.aliasFor("bogus"));
        // paletteIndex clamps garbage to 0 — aliasFor must NOT (that
        // would hand every typo the OLED icon)
        assertEquals(LauncherIcon.CLASSIC, LauncherIcon.aliasFor("OLED"));
    }

    // -------------------------------------------------------------- plan

    @Test
    public void plan_freshInstall_explicitPick_enablesFirstThenDisables() {
        // manifest state on a brand-new install: classic on, all else off
        String[][] ops = LauncherIcon.planOps(
                state("LauncherClassic"), "LauncherForest");
        assertEquals(2, ops.length);
        assertArrayEquals(enable("LauncherForest"), ops[0]);   // enable FIRST
        assertArrayEquals(disable("LauncherClassic"), ops[1]);
    }

    @Test
    public void plan_alreadyCorrect_isANoOp() {
        // a no-op must fire nothing — zero launcher churn on every boot
        assertEquals(0, LauncherIcon.planOps(state("LauncherPaper"),
                "LauncherPaper").length);
        // classic is the manifest default-enabled alias: fresh reality
        assertEquals(0, LauncherIcon.planOps(state(LauncherIcon.CLASSIC),
                LauncherIcon.CLASSIC).length);
    }

    @Test
    public void plan_interruptedSwitch_healsWithDisableOnly() {
        // the enable landed, the process died before the disable:
        // both on → just the disable, never a redundant re-enable
        String[][] ops = LauncherIcon.planOps(
                state("LauncherForest", "LauncherClassic"), "LauncherForest");
        assertEquals(1, ops.length);
        assertArrayEquals(disable("LauncherClassic"), ops[0]);
    }

    @Test
    public void plan_zeroEnabled_stillEnables() {
        // paranoid state (nothing enabled): the enable is the whole plan
        String[][] ops = LauncherIcon.planOps(allAliases(), "LauncherEmber");
        assertEquals(1, ops.length);
        assertArrayEquals(enable("LauncherEmber"), ops[0]);
    }

    @Test
    public void plan_themeToTheme_enablesNewThenDropsOld() {
        String[][] ops = LauncherIcon.planOps(
                state("LauncherGraphite"), "LauncherEmber");
        assertEquals(2, ops.length);
        assertArrayEquals(enable("LauncherEmber"), ops[0]);
        assertArrayEquals(disable("LauncherGraphite"), ops[1]);
        // classic was already off — untouched
    }

    @Test
    public void plan_backToClassic_disablesOnlyTheTheme() {
        // real device state: classic is manifest-default ON (the theme
        // alias was enabled on top of it) — back to classic = just the
        // disable, classic never needs a re-enable
        String[][] ops = LauncherIcon.planOps(
                state("LauncherMidnight", LauncherIcon.CLASSIC),
                LauncherIcon.CLASSIC);
        assertEquals(1, ops.length);
        assertArrayEquals(disable("LauncherMidnight"), ops[0]);
    }

    @Test
    public void plan_multiStale_cleanupDisablesEveryStaleAlias() {
        // a broken history left three enabled: desired stays, rest go
        String[][] ops = LauncherIcon.planOps(
                state("LauncherPaper", "LauncherClassic", "LauncherOled"),
                "LauncherPaper");
        assertEquals(2, ops.length);
        for (String[] op : ops) assertEquals("disable", op[1]);
        assertOpsContain(ops, LauncherIcon.CLASSIC, "disable");
        assertOpsContain(ops, "LauncherOled", "disable");
    }

    @Test
    public void plan_desiredIsNeverDisabled_byAnyState() {
        // property sweep: every desired alias × every stale combo must
        // never produce a disable of the desired alias, and the enable
        // (when present) must always be op[0]
        String[] all = new String[LauncherIcon.THEME_ALIASES.length + 1];
        all[0] = LauncherIcon.CLASSIC;
        System.arraycopy(LauncherIcon.THEME_ALIASES, 0, all, 1,
                LauncherIcon.THEME_ALIASES.length);
        for (String desired : all) {
            for (String stale : all) {
                String[][] ops = LauncherIcon.planOps(state(stale), desired);
                for (String[] op : ops) {
                    if (op[0].equals(desired)) assertEquals("enable", op[1]);
                }
                if (ops.length > 0 && ops[0][1].equals("enable"))
                    assertEquals(desired, ops[0][0]);
            }
        }
    }

    @Test
    public void plan_unknownDesired_treatedLikeClassicMode() {
        // defensive: an alias outside the table is a request to enable
        // it anyway — and exactly-one-enabled still holds, so the
        // currently-enabled classic goes in the same plan (enable first)
        String[][] ops = LauncherIcon.planOps(state("LauncherClassic"),
                "LauncherGhost");
        assertEquals(2, ops.length);
        assertArrayEquals(enable("LauncherGhost"), ops[0]);
        assertArrayEquals(disable("LauncherClassic"), ops[1]);
    }

    // ------------------------------------------------- bundled asset name

    @Test
    public void pickAsset_prefersTheCanonicalName() {
        java.util.HashSet<String> all = new java.util.HashSet<>(
                java.util.Arrays.asList("oc_pkg.bin", "n.bin", "busybox"));
        assertEquals("oc_pkg.bin", Binaries.pickAssetName(all));
    }

    @Test
    public void pickAsset_toleratesThePackagingSlipName() {
        // the v0.34/v0.35 accident: the tarball shipped as n.bin while
        // the code read oc_pkg.bin — fresh installs could not boot the
        // sandbox. The chain now tolerates the slip name too.
        java.util.HashSet<String> slip = new java.util.HashSet<>(
                java.util.Arrays.asList("n.bin", "busybox"));
        assertEquals("n.bin", Binaries.pickAssetName(slip));
    }

    @Test
    public void pickAsset_legacyUncompressedName() {
        java.util.HashSet<String> legacy = new java.util.HashSet<>(
                java.util.Arrays.asList("oc_pkg"));
        assertEquals("oc_pkg", Binaries.pickAssetName(legacy));
    }

    @Test
    public void pickAsset_nonePresent_isNull() {
        java.util.HashSet<String> none = new java.util.HashSet<>(
                java.util.Arrays.asList("busybox", "alpine.bin"));
        assertEquals(null, Binaries.pickAssetName(none));
    }
}
