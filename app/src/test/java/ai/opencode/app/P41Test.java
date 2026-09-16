package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.opencode.app.Models.Mdl;
import ai.opencode.app.Models.Prov;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P41 — the compaction governance release, pinned where each piece
 * lives.
 *
 * THE FIELD BUG (September screenshots): a long agentic session on a
 * fast model re-explored the same empty project folder over and over
 * and burned real money, while the Σ pill reassured "16% — light".
 * The bundled server's own source explains it: when a turn reaches
 * the window THE SERVER believes, it silently summarizes the thread
 * and keeps only preserve_recent_tokens of recent turns verbatim —
 * a default of 2k–15k tokens — so the model is left with a paragraph
 * while the screen shows everything. The app's meter divided by the
 * CATALOG's advertised window (a different source that can disagree
 * several-fold), never named the disagreement, never announced the
 * compaction. These tests pin the three-part source fix.
 *
 * 1. CompactionPolicy.preserveRecentTokensFor — the floor the app
 *    pins into opencode.json: scaled to the window, clamped, never
 *    written for unknown or small windows, and ALWAYS below the
 *    usable space so a compaction still frees something.
 * 2. CompactionPolicy.mergePreserve — write-if-absent: a user's own
 *    compaction block (any shape, even malformed) is never touched.
 * 3. Models.overflowLimitFor / resolveLimit source order — the
 *    server's own claim first (the number compaction actually fires
 *    on), catalog only as fallback.
 * 4. The popover/chat notes: risk threshold at 70%, mismatch beyond
 *    ~20%, the summary line, and no note ever carrying a
 *    token-shaped string.
 */
@RunWith(RobolectricTestRunner.class)
public class P41Test {

    // --------------------------------------------- 1. the floor table

    @Test public void preserveTable_scaledClampedGated() {
        // unknown / tiny windows: the server default stands
        assertEquals(0L, CompactionPolicy.preserveRecentTokensFor(0));
        assertEquals(0L, CompactionPolicy.preserveRecentTokensFor(-5));
        assertEquals(0L, CompactionPolicy.preserveRecentTokensFor(63_999));
        // the gate opens at 64k: 30% of the usable (64k − 20k)
        assertEquals(13_200L, CompactionPolicy.preserveRecentTokensFor(64_000));
        // a 128k-class window: ~32k verbatim tail vs the 15k default
        assertEquals(32_400L, CompactionPolicy.preserveRecentTokensFor(128_000));
        // a 200k window (the P27 field's server-side number)
        assertEquals(54_000L, CompactionPolicy.preserveRecentTokensFor(200_000));
        // big windows all cap at 60k
        assertEquals(60_000L, CompactionPolicy.preserveRecentTokensFor(1_048_576));
        assertEquals(60_000L, CompactionPolicy.preserveRecentTokensFor(1_310_720));
    }

    @Test public void preserveFloor_staysBelowUsable_forEveryWindow() {
        // the safety property the whole fix rests on: for ANY window the
        // catalog could claim, the written floor must leave usable space
        // (limit − 20k reserve) — otherwise a compaction frees nothing
        // and the model's memory is wiped whole instead of tailed.
        for (long limit = 64_000; limit <= 2_000_000; limit += 1_000) {
            long v = CompactionPolicy.preserveRecentTokensFor(limit);
            assertTrue("floor below usable at " + limit, v < limit - 20_000);
        }
    }

    // --------------------------------------------- 2. the merge rule

    @Test public void merge_writesWhenAbsent_andKeepsRestOfConfig() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", "openrouter/~deepseek/deepseek-v4-flash-latest");
        assertTrue(CompactionPolicy.mergePreserve(root, 1_310_720));
        assertEquals("model key untouched",
                "openrouter/~deepseek/deepseek-v4-flash-latest", root.get("model"));
        @SuppressWarnings("unchecked")
        Map<String, Object> comp = (Map<String, Object>) root.get("compaction");
        assertEquals(60_000L, comp.get("preserve_recent_tokens"));
    }

    @Test public void merge_neverTouchesAnExistingValue() {
        // the user's own number — any number — always wins
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("preserve_recent_tokens", 5_000);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compaction", comp);
        assertFalse(CompactionPolicy.mergePreserve(root, 1_310_720));
        assertEquals(5_000, comp.get("preserve_recent_tokens"));

        // even a malformed value counts as user-owned: hands off
        Map<String, Object> junk = new LinkedHashMap<>();
        junk.put("preserve_recent_tokens", "later");
        Map<String, Object> root2 = new LinkedHashMap<>();
        root2.put("compaction", junk);
        assertFalse(CompactionPolicy.mergePreserve(root2, 1_310_720));

        // an existing compaction block WITHOUT the key gains only the key
        Map<String, Object> auto = new LinkedHashMap<>();
        auto.put("auto", false);
        Map<String, Object> root3 = new LinkedHashMap<>();
        root3.put("compaction", auto);
        assertTrue(CompactionPolicy.mergePreserve(root3, 128_000));
        assertEquals(false, auto.get("auto"));
        assertEquals(32_400L, auto.get("preserve_recent_tokens"));
    }

    @Test public void merge_smallOrUnknownWindow_writesNothing() {
        Map<String, Object> root = new LinkedHashMap<>();
        assertFalse(CompactionPolicy.mergePreserve(root, 0));
        assertFalse(CompactionPolicy.mergePreserve(root, 32_000));
        assertTrue(root.isEmpty());
        assertFalse(CompactionPolicy.mergePreserve(null, 1_310_720));
    }

    @Test public void merge_nonMapCompactionBlock_isUserJunk_handsOff() {
        // "compaction": true — malformed, the server ignores it, we
        // never overwrite it either
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compaction", true);
        assertFalse(CompactionPolicy.mergePreserve(root, 1_310_720));
        assertEquals(true, root.get("compaction"));
    }

    // ---------------------------------------- 3. the window precedence

    private static Models.Prov prov(String id, Mdl... ms) {
        Models.Prov p = new Models.Prov();
        p.id = id;
        p.name = id;
        p.models.addAll(Arrays.asList(ms));
        return p;
    }

    private static Mdl mdl(String id, long catalog, long server) {
        Mdl m = new Mdl();
        m.id = id;
        m.ctx = catalog;
        m.ctxServer = server;
        return m;
    }

    @Test public void overflowLimit_serverClaimWinsOverCatalog() {
        List<Prov> provs = new ArrayList<>();
        provs.add(prov("openrouter",
                mdl("~deepseek/deepseek-v4-flash-latest", 1_310_720L, 131_072L)));
        // the field case in miniature: catalog 1.31M, server 128k —
        // compaction fires on the server's number, so must the meter
        assertEquals(131_072L, Models.overflowLimitFor(
                provs, "openrouter", "~deepseek/deepseek-v4-flash-latest"));
    }

    @Test public void overflowLimit_catalogStandsInWhenServerSilent() {
        List<Prov> provs = new ArrayList<>();
        provs.add(prov("some-catalog-only", mdl("m", 200_000L, 0)));
        assertEquals(200_000L,
                Models.overflowLimitFor(provs, "some-catalog-only", "m"));
        assertEquals(0L, Models.overflowLimitFor(provs, "nope", "m"));
        assertEquals(0L, Models.overflowLimitFor(null, "openrouter", "m"));
        assertEquals(0L, Models.overflowLimitFor(provs, null, "m"));
        assertEquals(0L, Models.overflowLimitFor(provs, "openrouter", null));
    }

    @Test public void lookupHelpers_splitTheSources() {
        List<Prov> provs = new ArrayList<>();
        provs.add(prov("p", mdl("m", 1_310_720L, 131_072L)));
        assertEquals(131_072L, Models.serverLimitFor(provs, "p", "m"));
        assertEquals(1_310_720L, Models.catalogLimitFor(provs, "p", "m"));
        // a model the server never listed: server lookup 0, catalog intact
        List<Prov> provs2 = new ArrayList<>();
        provs2.add(prov("p", mdl("m2", 200_000L, 0)));
        assertEquals(0L, Models.serverLimitFor(provs2, "p", "m2"));
        assertEquals(200_000L, Models.catalogLimitFor(provs2, "p", "m2"));
    }

    // ---------------------------------------------------- 4. the notes

    @Test public void riskNote_silentBelow70_warnsAtOrAbove() {
        assertNull(CompactionPolicy.riskNote(0, 131_072));
        assertNull(CompactionPolicy.riskNote(50_000, 0));
        assertNull(CompactionPolicy.riskNote(-1, 131_072));
        assertNull(CompactionPolicy.riskNote(69_000, 131_072));   // 52%
        assertNull(CompactionPolicy.riskNote(91_000, 131_072));   // 69.4%
        String note = CompactionPolicy.riskNote(92_000, 131_072); // 70.2%
        assertTrue(note != null && note.contains("131k"));
        assertTrue(note.contains("summarizes"));
        // at 100%+ the meter already clamps — the note still speaks
        assertTrue(CompactionPolicy.riskNote(200_000, 131_072) != null);
    }

    @Test public void mismatchNote_onlyWhenCatalogOverstatesBy20Percent() {
        // the field shape: catalog 1.31M vs server 128k
        String note = CompactionPolicy.mismatchNote(131_072L, 1_310_720L);
        assertTrue(note != null && note.contains("131k") && note.contains("1.3M"));
        // near-agreement stays silent (within 20% either way)
        assertNull(CompactionPolicy.mismatchNote(200_000L, 220_000L));
        // catalog SMALLER than server: the meter already errs safe
        assertNull(CompactionPolicy.mismatchNote(1_310_720L, 200_000L));
        // missing sources never speak
        assertNull(CompactionPolicy.mismatchNote(0, 1_310_720L));
        assertNull(CompactionPolicy.mismatchNote(131_072L, 0));
        assertNull(CompactionPolicy.mismatchNote(0, 0));
    }

    @Test public void summaryNote_saysWhatHappened_andCarriesNoSecret() {
        String s = CompactionPolicy.summaryNote();
        assertTrue(s.contains("summarized"));
        assertTrue(s.contains("model"));
        // the notes are prose for a person — never a token/credential shape
        assertFalse(s.matches(".*(ghp|sk)-[A-Za-z0-9]{8,}.*"));
    }

    @Test public void noteTexts_neverCarryATokenShape() {
        String[] notes = {
                CompactionPolicy.summaryNote(),
                CompactionPolicy.riskNote(100_000, 131_072),
                CompactionPolicy.mismatchNote(131_072L, 1_310_720L),
        };
        for (String n : notes) {
            if (n == null) continue;
            assertFalse(n.matches(".*(ghp|github_pat|sk)-[A-Za-z0-9_-]{8,}.*"));
        }
    }
}
