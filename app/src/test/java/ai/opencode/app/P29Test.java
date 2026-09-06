package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P29 — pure pins for the field's four asks:
 *
 *   • CostMath — the next-send price line: text/image token estimates,
 *     worst-case next-input cost, the window percent, and the exact hint
 *     line (hidden when nothing to send, "free model" when the pick costs
 *     0, the compact nudge at ≥50% window). The Σ pill format is NOT
 *     touched by any of this — asserted by keeping these functions fully
 *     separate from Resilience.contextMeter.
 *
 *   • TerseMode — the AGENTS.md managed block: merge/strip idempotence,
 *     user-content preservation, marker stability, the OFF transition.
 *
 *   • RunHub.buildMultiImageBodies — one message carrying N image parts
 *     (and the 1-image/0-image fallbacks to the existing builders).
 *
 *   • Models.find — the pricing lookup the hint depends on.
 *
 * Runs under Robolectric (like P25HubTest): the RunHub body-builders are
 * pure, but the class's static shell is Android-shaped, and Robolectric
 * is already on the test classpath — no reason to fight it.
 */
@RunWith(RobolectricTestRunner.class)
public class P29Test {

    // ------------------------------------------------------------ CostMath

    @Test
    public void textTokens_emptyIsZero_asciiIsFourPerChar() {
        assertEquals(0, CostMath.estimateTextTokens(null));
        assertEquals(0, CostMath.estimateTextTokens(""));
        assertEquals(0, CostMath.estimateTextTokens("   \n  "));
        // 40 ASCII chars ≈ 10 tokens
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append('a');
        assertEquals(10, CostMath.estimateTextTokens(sb.toString()));
        // short non-empty never collapses to 0
        assertEquals(1, CostMath.estimateTextTokens("hi"));
    }

    @Test
    public void textTokens_nonAsciiCountsPerChar() {
        // 6 CJK chars ≈ 6 tokens (the ~4-chars/token rule is ASCII-only)
        assertEquals(6, CostMath.estimateTextTokens("你好世界晚安"));
        // mixed: 8 ASCII (2 tok) + 3 CJK (3 tok)
        assertEquals(5, CostMath.estimateTextTokens("abcdefg1你好世"));
    }

    @Test
    public void imageTokens_pixelsOver750_clamped() {
        // the app's vision path downscales to ≤1024 long edge
        long sq = CostMath.estimateImageTokens(1024, 1024);
        assertEquals((1024L * 1024L + 749) / 750, sq);
        assertTrue("square 1024 lands in the honest band",
                sq >= 1000 && sq <= 2000);
        // clamp floor / ceiling
        assertEquals(450, CostMath.estimateImageTokens(100, 100));
        assertEquals(4000, CostMath.estimateImageTokens(4000, 4000));
        // undecodable dims price nothing
        assertEquals(0, CostMath.estimateImageTokens(0, 0));
        assertEquals(0, CostMath.estimateImageTokens(-5, 100));
    }

    @Test
    public void nextInputCost_worstCaseReReadsContext() {
        // 48k ctx + 2k new at $1/Mtok → $0.05 (the whole window, cached
        // discount can only make the REAL bill smaller)
        assertEquals(0.05, CostMath.nextInputCost(48_000, 2_000, 1.0), 1e-9);
        assertEquals(0, CostMath.nextInputCost(48_000, 2_000, 0), 1e-12);
        // negative ctx clamps to 0 but the NEW content still prices
        assertEquals(0.0001, CostMath.nextInputCost(-5, 100, 1.0), 1e-12);
    }

    @Test
    public void windowPct_and_hints() {
        assertEquals(24, CostMath.windowPct(48_000, 200_000));
        assertEquals(0, CostMath.windowPct(0, 200_000));
        assertEquals(0, CostMath.windowPct(48_000, 0));
    }

    @Test
    public void hintLine_contract() {
        // nothing to send → hidden
        assertEquals("", CostMath.hintLine(0, 48_000, 0.05, true, 200_000));
        // priced send
        assertEquals("≈ 2k new · next ≈ $0.0500 · ctx 48k",
                CostMath.hintLine(2_000, 48_000, 0.05, true, 200_000));
        // known-free model says so instead of $0.0000
        assertEquals("≈ 2k new · free model · ctx 48k",
                CostMath.hintLine(2_000, 48_000, 0, true, 200_000));
        // unknown price → tokens only
        assertEquals("≈ 2k new · ctx 48k",
                CostMath.hintLine(2_000, 48_000, 0, false, 200_000));
        // heavy window → the compact nudge (the user's "clear the context
        // to make it cheaper")
        String heavy = CostMath.hintLine(2_000, 120_000, 0.2, true, 200_000);
        assertTrue(heavy.endsWith(" · compact to pay less"));
    }

    @Test
    public void maxAttachments_six() {
        assertEquals(6, CostMath.MAX_ATTACHMENTS);
    }

    // ------------------------------------------------------------ TerseMode

    private static final String USER_MD =
            "# My project\n\nUse pnpm. Never touch /vendor.\n";

    @Test
    public void merge_on_insertsBlock_userContentKept() {
        String out = TerseMode.merge(USER_MD, true);
        assertTrue(TerseMode.isOn(out));
        assertTrue(out.startsWith(USER_MD));
        assertTrue(out.contains(TerseMode.START));
        assertTrue(out.contains(TerseMode.END));
        assertTrue(out.contains(TerseMode.TEXT));
        int start = out.indexOf(TerseMode.START);
        assertTrue("markers always paired",
                out.indexOf(TerseMode.START, start + 1) < 0);
    }

    @Test
    public void merge_on_isIdempotent() {
        String once = TerseMode.merge(USER_MD, true);
        String twice = TerseMode.merge(once, true);
        assertEquals(once, twice);
    }

    @Test
    public void merge_off_removesOnlyTheBlock() {
        String on = TerseMode.merge(USER_MD, true);
        String off = TerseMode.merge(on, false);
        assertFalse(TerseMode.isOn(off));
        assertTrue("user content survives the OFF toggle",
                off.contains("Use pnpm. Never touch /vendor."));
        assertFalse(off.contains(TerseMode.TEXT));
        // OFF is idempotent too
        assertEquals(off, TerseMode.merge(off, false));
    }

    @Test
    public void merge_off_onEmptyOrNull_isEmpty() {
        assertEquals("", TerseMode.merge(null, false));
        assertEquals("", TerseMode.merge("", false));
        assertTrue(TerseMode.merge("", true).contains(TerseMode.START));
    }

    @Test
    public void strip_removesBlockAnywhere_cleanSeams() {
        String mid = "a\n\n" + TerseMode.START + "\nx\n" + TerseMode.END
                + "\n\nb\n";
        String out = TerseMode.strip(mid);
        assertFalse(out.contains("x"));
        assertTrue(out.contains("a"));
        assertTrue(out.contains("b"));
        // unterminated block (crash-truncated file) still stripped
        String broken = "keep\n" + TerseMode.START + "\npartial";
        assertFalse(TerseMode.strip(broken).contains("partial"));
        assertTrue(TerseMode.strip(broken).contains("keep"));
    }

    // ------------------------------------ RunHub.buildMultiImageBodies

    @Test
    public void multiImage_oneMessageCarriesAllParts() {
        List<String> urls = Arrays.asList("data:image/jpeg;base64,AAA",
                "data:image/jpeg;base64,BBB", "data:image/jpeg;base64,CCC");
        List<String> bodies = RunHub.buildMultiImageBodies("look", urls,
                new String[]{"opencode", "m1"}, "build");
        assertFalse(bodies.isEmpty());
        String first = bodies.get(0);            // model+agent variant first
        assertTrue(first.contains("\"providerID\":\"opencode\""));
        assertTrue(first.contains("\"modelID\":\"m1\""));
        assertEquals(3, countParts(first, "\"type\":\"file\""));
        assertEquals(1, countParts(first, "\"type\":\"text\""));
        assertTrue(first.contains("data:image/jpeg;base64,AAA"));
        assertTrue(first.contains("data:image/jpeg;base64,CCC"));
        assertTrue(first.contains("\"agent\":\"build\""));
        // every variant carries all three
        for (String b : bodies) assertEquals(3, countParts(b, "\"type\":\"file\""));
    }

    @Test
    public void multiImage_fallsBackToExistingBuilders() {
        String url = "data:image/jpeg;base64,AAA";
        // 1 image → exactly the P17 single-image shape
        List<String> one = RunHub.buildMultiImageBodies("look",
                java.util.Collections.singletonList(url), null, "plan");
        List<String> legacy = RunHub.buildImageBodies("look", url, null, "plan");
        assertEquals(legacy, one);
        // 0 images → plain text bodies
        List<String> none = RunHub.buildMultiImageBodies("hi",
                new ArrayList<>(), null, "build");
        assertEquals(RunHub.buildBodies("hi", null, "build"), none);
        // null list → plain text bodies (never NPE)
        assertEquals(RunHub.buildBodies("hi", null, "build"),
                RunHub.buildMultiImageBodies("hi", null, null, "build"));
    }

    private static int countParts(String body, String marker) {
        int n = 0, i = 0;
        while ((i = body.indexOf(marker, i)) >= 0) { n++; i += marker.length(); }
        return n;
    }

    // ------------------------------------------------------------ Models

    @Test
    public void find_returnsRow_nullSafe() {
        Models.Prov p = new Models.Prov();
        p.id = "opencode";
        p.name = "OpenCode";
        Models.Mdl m = new Models.Mdl();
        m.id = "kimi";
        m.costIn = 0.2;
        m.ctx = 200_000;
        p.models.add(m);
        List<Models.Prov> provs = new ArrayList<>();
        provs.add(p);

        assertSame(m, Models.find(provs, "opencode", "kimi"));
        assertNull(Models.find(provs, "opencode", "nope"));
        assertNull(Models.find(provs, "other", "kimi"));
        assertNull(Models.find(null, "opencode", "kimi"));
        assertNull(Models.find(provs, null, "kimi"));
    }

    private static void assertSame(Object expected, Object actual) {
        assertTrue("expected same instance", expected == actual);
    }
}
