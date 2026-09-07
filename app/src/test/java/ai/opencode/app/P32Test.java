package ai.opencode.app;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * P32 — the final version's pins. The field report: "some parts of the
 * ui is inconsistent with the whole theme" and "pressing the theme
 * change button causes a crash".
 *
 * Root causes found:
 *  • CRASH: pickTheme() dead-cast android.R.layout.simple_list_item_1
 *    (a TextView!) to LinearLayout — ClassCastException on every tap.
 *    Pinned at the UI layer in P32UiTest.
 *  • INCONSISTENCY: card-surface tints hardcoded white (0xB3FFFFFF …)
 *    — invisible ink on the Paper palette; the sandbox veil hardcoded a
 *    dark midnight wash; and a theme switch only re-skinned Settings,
 *    every other open screen kept the old palette.
 *
 * These are the pure pins for the new palette-owned helpers. The legacy
 * reproduction asserts are the important ones: on the DEFAULT palette
 * the new helpers produce BYTE-IDENTICAL colors to the old hexes, so
 * the fix cannot visually change the theme the user actually uses.
 */
public class P32Test {

    // ---- helpers to point the palette statics at a table row ------------

    private static void usePalette(int idx) {
        int[] p = Theme.PALETTE_DATA[idx];
        Theme.ON_CARD = p[21];
        Theme.SURFACE = p[1];
        Theme.SURFACE2 = p[2];
        Theme.TINT_DANGER = p[16];
    }

    private static int alphaOf(int c) { return (c >>> 24) & 0xFF; }
    private static int rgbOf(int c)   { return c & 0x00FFFFFF; }

    /** never leak a mutated palette static into a sibling test */
    @After
    public void restoreDefaults() {
        Theme.ON_CARD = 0xCCFFFFFF;
        Theme.SURFACE = 0xFF0B0E16;
        Theme.SURFACE2 = 0xFF121724;
        Theme.TINT_DANGER = 0xFF4A2126;
    }

    // ---- onCard: the on-gradient ink ------------------------------------

    @Test
    public void onCard_reproducesTheLegacyHexes_onTheDefaultOledPalette() {
        usePalette(0); // oled — ON_CARD 0xCCFFFFFF
        // every hardcoded tint P8..P31 shipped, reproduced byte-exact
        assertEquals(0xB3FFFFFF, Theme.onCard(0xB3)); // PROJECT tag
        assertEquals(0xB8FFFFFF, Theme.onCard(0xB8)); // card path
        assertEquals(0x30FFFFFF, Theme.onCard(0x30)); // card divider
        assertEquals(0x33FFFFFF, Theme.onCard(0x33)); // card rim / restart fill
        assertEquals(0x55FFFFFF, Theme.onCard(0x55)); // restart stroke
        assertEquals(0x10FFFFFF, Theme.onCard(0x10)); // ghost fill
        assertEquals(0x50FFFFFF, Theme.onCard(0x50)); // ghost stroke
    }

    @Test
    public void onCard_alwaysCarriesTheOnCardRgb_atTheRequestedAlpha() {
        for (int i = 0; i < Theme.PALETTE_DATA.length; i++) {
            usePalette(i);
            int[] alphas = {0x00, 0x10, 0x30, 0x55, 0xB3, 0xB8, 0xFF};
            for (int a : alphas) {
                int c = Theme.onCard(a);
                assertEquals("palette " + i + " alpha " + a,
                        alphaOf(Theme.ON_CARD) == 0 && a == 0 ? 0 : a, alphaOf(c));
                assertEquals("palette " + i + " alpha " + a,
                        rgbOf(Theme.ON_CARD), rgbOf(c));
            }
        }
    }

    @Test
    public void onCard_paperGetsInk_notInvisibleWhite() {
        usePalette(5); // paper — ON_CARD 0xE6222630 (dark ink)
        int tag = Theme.onCard(0xB3);
        assertEquals(0xB3222630, tag);
        assertNotEquals("the old white tag on a paper card = invisible ink",
                0xB3FFFFFF, tag);
        // and the ink is genuinely DARK (readable on a light card)
        assertTrue("paper card ink must be dark", (tag & 0xFF) < 0x60);
    }

    @Test
    public void onCard_whiteFamilyKept_onEveryDarkPalette() {
        // midnight/graphite keep pure-white-family ink; ember/forest use
        // their own warm/green ON_CARD whites — all stay light
        for (int i : new int[]{1, 2}) {
            usePalette(i);
            assertTrue("palette " + i, Theme.onCard(0xB3) == (0xB3FFFFFF));
        }
        for (int i : new int[]{0, 3, 4}) {
            usePalette(i);
            int c = Theme.onCard(0xB3);
            assertTrue("palette " + i + " on-card ink stays light",
                    (c & 0xFF) > 0xC0 && ((c >> 8) & 0xFF) > 0xC0);
        }
    }

    // ---- surfaceScrim: the "starting sandbox" veil -----------------------

    @Test
    public void surfaceScrim_reproducesTheLegacyVeilOnOled() {
        usePalette(0);
        assertEquals(0xE60B0E16, Theme.surfaceScrim(0xE6));
    }

    @Test
    public void surfaceScrim_followsSurfaceHue_withExactAlpha() {
        for (int i = 0; i < Theme.PALETTE_DATA.length; i++) {
            usePalette(i);
            int scrim = Theme.surfaceScrim(0xE6);
            assertEquals("palette " + i, 0xE6, alphaOf(scrim));
            assertEquals("palette " + i, rgbOf(Theme.SURFACE), rgbOf(scrim));
        }
    }

    @Test
    public void surfaceScrim_isLightOnPaper_darkOnDarkPalettes() {
        usePalette(5);
        assertTrue("paper veil must be light",
                ((Theme.surfaceScrim(0xE6) >> 16) & 0xFF) > 0xC0);
        for (int i : new int[]{0, 1, 2, 3, 4}) {
            usePalette(i);
            assertTrue("dark palette " + i + " veil must stay dark",
                    ((Theme.surfaceScrim(0xE6) >> 16) & 0xFF) < 0x30);
        }
    }

    // ---- isStale: the whole-app-follows-the-theme rule -------------------

    @Test
    public void isStale_firesOnlyWhenThePaletteActuallyChanged() {
        assertFalse(Theme.isStale("oled", "oled"));
        assertFalse(Theme.isStale("paper", "paper"));
        assertTrue(Theme.isStale("paper", "oled"));
        assertTrue(Theme.isStale("midnight", "oled"));
        assertTrue(Theme.isStale("graphite", "ember"));
    }

    @Test
    public void isStale_nullAppliedNeverFires_andUnknownIdsAreSafe() {
        // null applied = "not applied yet" → no-op beats a recreate storm
        assertFalse(Theme.isStale("paper", null));
        assertFalse(Theme.isStale(null, "oled"));
        assertFalse(Theme.isStale(null, null));
        // an unknown id resolves to index 0 (oled) on BOTH sides — an
        // unparsable pref can never trigger a recreate loop
        assertFalse(Theme.isStale("nope", "oled"));
        assertTrue(Theme.isStale("nope", "paper"));
    }

    // ---- the palette-owned card/pill builders -----------------------------

    @Test
    public void codeWell_errCard_sysPill_followTheLiveTokens() {
        usePalette(0);
        // oled parity: fills equal the oled table values the P10 XML froze
        // (the helpers read the tokens directly; dp() strokes are trivial)
        assertEquals(0xFF0B0E16, Theme.SURFACE);
        assertEquals(0xFF4A2126, Theme.TINT_DANGER);
        assertEquals(0xFF121724, Theme.SURFACE2);
        // and the stroke derivations the helpers bake in
        assertEquals(0x66E07A7A, (Theme.ERR & 0x00FFFFFF) | 0x66000000);
        assertEquals(0x332A3A66, (Theme.TINT_ACCENT & 0x00FFFFFF) | 0x33000000);
        assertEquals(0x4D7C9CFF, (Theme.ACCENT & 0x00FFFFFF) | 0x4D000000);
    }

    @Test
    public void pillBuilders_usePaletteValues_notFrozenHexes_onPaper() {
        usePalette(5); // paper
        // the fills the builders use must DIFFER from the old frozen hexes
        // bg_code #0D1017 → paper SURFACE FBFAF6 (a LIGHT well on light)
        assertNotEquals(0xFF0D1017, Theme.SURFACE);
        assertTrue("paper code well must be light",
                (Theme.SURFACE & 0xFF) > 0xC0);
        // bg_err_card #2A151C → paper TINT_DANGER F6DBDD (a light red wash)
        assertNotEquals(0xFF2A151C, Theme.TINT_DANGER);
        assertTrue("paper danger wash must be light",
                ((Theme.TINT_DANGER >> 16) & 0xFF) > 0xC0);
        // bg_sys_pill #141824 → paper SURFACE2 EDEBE2
        assertNotEquals(0xFF141824, Theme.SURFACE2);
        assertTrue("paper sys pill must be light",
                (Theme.SURFACE2 & 0xFF) > 0xC0);
    }

    // ---- the picker's swatch rows ----------------------------------------

    @Test
    public void pickerSwatches_areThreeDistinguishableValues_forEveryPalette() {
        for (int i = 0; i < Theme.PALETTE_DATA.length; i++) {
            int[] p = Theme.PALETTE_DATA[i];
            int bg = p[0], s2 = p[2], ac = p[4];
            assertNotEquals("palette " + i + " bg==accent", bg, ac);
            assertNotEquals("palette " + i + " s2==accent", s2, ac);
            assertNotEquals("palette " + i + " bg==s2", bg, s2);
        }
    }

    @Test
    public void sixPalettes_stillShip_andOledIsStillTheDefault() {
        assertEquals(6, Theme.PALETTES.length);
        assertEquals("oled", Theme.PALETTES[0]);
        assertEquals(0, Theme.paletteIndex(null));
        assertEquals(0, Theme.paletteIndex("unheard-of"));
    }
}
