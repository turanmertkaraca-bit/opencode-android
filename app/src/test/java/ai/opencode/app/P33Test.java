package ai.opencode.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P33 — the final-version pins. The field reports this cycle answers:
 *
 *   • "p32 made everything low contrast white" — the model picker's
 *     live flags collapsed whenever the server didn't answer (boot,
 *     restart, hibernate wake): every row dimmed to catalog ink. The
 *     pure rule carryLive keeps last-known live truth through a blip.
 *   • "the app thinks i have no api key even tho it says i have it in
 *     api settings" — three layers: the sheet re-reads auth at open
 *     (UI, pinned in P33UiTest), the sandbox reloads a CHANGED key by
 *     itself (wiring), and hasAnyKey counts custom providers whose key
 *     lives inline in opencode.json (configHasEmbeddedKey here).
 *   • "make it default" (Graphite) — Theme.DEFAULT_PALETTE + defaultId,
 *     pinned here too so no later cycle quietly reverts the face.
 */
public class P33Test {

    // ---------------------------------------------------------- helpers

    private static Models.Mdl mdl(String id, boolean live) {
        Models.Mdl m = new Models.Mdl();
        m.id = id;
        m.name = id;
        m.live = live;
        return m;
    }

    private static Models.Prov prov(String id, Models.Mdl... models) {
        Models.Prov p = new Models.Prov();
        p.id = id;
        p.name = id;
        p.models.addAll(Arrays.asList(models));
        return p;
    }

    private static Map<String, Models.Prov> byId(Models.Prov... provs) {
        Map<String, Models.Prov> m = new LinkedHashMap<>();
        for (Models.Prov p : provs) m.put(p.id, p);
        return m;
    }

    // ------------------------------------------------- carryLive rules

    @Test
    public void carryLive_serverBlip_keepsLastKnownLiveFlags() {
        // last fetch: the server served both models of this provider
        Models.Prov prevP = prov("openai", mdl("gpt-live", true), mdl("gpt-2", true));
        // this fetch: the server was down — everything arrived catalog-only
        Models.Prov nextP = prov("openai", mdl("gpt-live", false), mdl("gpt-2", false));

        Models.carryLive(new ArrayList<>(Arrays.asList(prevP)), byId(nextP));

        assertTrue("a model the server served last time stays live through a blip",
                nextP.models.get(0).live);
        assertTrue(nextP.models.get(1).live);
        assertTrue("the provider reads usable again — the header can't say "
                + "'needs its own key' over served models", nextP.usable);
    }

    @Test
    public void carryLive_neverInventsFlags_forUnservedModels() {
        Models.Prov prevP = prov("openai", mdl("served", true));
        Models.Prov nextP = prov("openai", mdl("served", false), mdl("never-served", false));

        Models.carryLive(new ArrayList<>(Arrays.asList(prevP)), byId(nextP));

        assertTrue(nextP.models.get(0).live);
        assertFalse("a model the server never listed stays catalog-only",
                nextP.models.get(1).live);
    }

    @Test
    public void carryLive_neverClears_existingTruth() {
        Models.Prov prevP = prov("a", mdl("x", true));
        Models.Prov nextP = prov("a", mdl("x", true), mdl("y", false));

        Models.carryLive(new ArrayList<>(Arrays.asList(prevP)), byId(nextP));

        assertTrue("live stays live — the rule only sets, never clears",
                nextP.models.get(0).live);
    }

    @Test
    public void carryLive_otherProvidersAreUntouched() {
        Models.Prov prevP = prov("openai", mdl("gpt", true));
        Models.Prov nextA = prov("openai", mdl("gpt", false));
        Models.Prov nextB = prov("mistral", mdl("mixtral", false));
        Map<String, Models.Prov> next = byId(nextA, nextB);

        Models.carryLive(new ArrayList<>(Arrays.asList(prevP)), next);

        assertTrue(nextA.models.get(0).live);
        assertFalse("another provider's catalog rows must not light up",
                nextB.models.get(0).live);
        assertFalse(nextB.usable);
    }

    @Test
    public void carryLive_isSafeOnEmptyAndNullInputs() {
        Models.Prov nextP = prov("openai", mdl("gpt", false));
        Map<String, Models.Prov> next = byId(nextP);

        Models.carryLive(null, next);
        Models.carryLive(new ArrayList<>(), next);
        Models.carryLive(Arrays.asList(prov("x", mdl("y", true))),
                new LinkedHashMap<>());
        Models.carryLive(Arrays.asList(prov("x", mdl("y", true))), null);

        assertFalse("no-op on empty prev — first boot dims as always",
                nextP.models.get(0).live);
    }

    @Test
    public void carryLive_orderOfArgsMatters_prevThenNext() {
        // guards a future signature swap: (next, prev) would no-op silently
        Models.Prov prevP = prov("a", mdl("m", true));
        Map<String, Models.Prov> next = byId(prov("a", mdl("m", false)));
        Models.carryLive(new ArrayList<>(Arrays.asList(prevP)), next);
        assertTrue(((Models.Prov) next.get("a")).models.get(0).live);
    }

    // --------------------------------- configHasEmbeddedKey (AuthStore)

    /** provider("id", "key"|null) as the config fragment it lives in. */
    private static Map<String, Object> cfgWith(String pid, String apiKey) {
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("npm", "@ai-sdk/openai-compatible");
        prov.put("name", pid);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("baseURL", "https://x/v1");
        if (apiKey != null) options.put("apiKey", apiKey);
        prov.put("options", options);
        Map<String, Object> provs = new LinkedHashMap<>();
        provs.put(pid, prov);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("provider", provs);
        return cfg;
    }

    @Test
    public void configHasEmbeddedKey_readsACustomProviderKey() {
        assertTrue(AuthStore.configHasEmbeddedKey(cfgWith("myprov", "sk-test")));
    }

    @Test
    public void configHasEmbeddedKey_rejectsEmptyAndMissing() {
        assertFalse(AuthStore.configHasEmbeddedKey(null));
        assertFalse(AuthStore.configHasEmbeddedKey(new LinkedHashMap<>()));
        Map<String, Object> noProvider = new LinkedHashMap<>();
        noProvider.put("model", "a/b");
        assertFalse(AuthStore.configHasEmbeddedKey(noProvider));
        assertFalse("a provider without options carries no inline key",
                AuthStore.configHasEmbeddedKey(cfgWith("p", null)));
        assertFalse("an empty-string key is not a key",
                AuthStore.configHasEmbeddedKey(cfgWith("p", "")));
        assertFalse("a whitespace key is not a key",
                AuthStore.configHasEmbeddedKey(cfgWith("p", "   ")));
    }

    @Test
    public void configHasEmbeddedKey_anyProviderCounts() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        Map<String, Object> provs = new LinkedHashMap<>();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("options", new LinkedHashMap<>());          // no key here
        Map<String, Object> bOpts = new LinkedHashMap<>();
        bOpts.put("apiKey", "k");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("options", bOpts);                          // key here
        provs.put("a", a);
        provs.put("b", b);
        cfg.put("provider", provs);
        assertTrue("the second provider's key is enough",
                AuthStore.configHasEmbeddedKey(cfg));
    }

    // -------------------------------------------------- the default face

    @Test
    public void graphiteIsTheDefaultFace_ofTheFinalVersion() {
        assertEquals("graphite", Theme.DEFAULT_PALETTE);
        assertEquals("graphite lands where the table put it",
                2, Theme.paletteIndex(Theme.DEFAULT_PALETTE));
        assertFalse("the default face has a display name",
                Theme.paletteName("graphite").isEmpty());
        assertEquals("Graphite", Theme.paletteName("graphite"));
        // the sanity floor is unchanged: unknown ids still fall to oled
        assertEquals(0, Theme.paletteIndex("garbage"));
    }
}
