package ai.opencode.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P9 model catalog: EVERY provider the server knows + the FULL models.dev
 * catalog for discovery.
 *
 * Problem this fixes (user): "the model select screen only shows opencode
 * zen free models like 7 of em and nothing else". /config/providers only
 * lists providers that are usable with current auth — so with no keys it
 * degrades to the bundled free tier. The app now ALSO fetches
 * https://models.dev/api.json (the exact catalog opencode syncs from) and
 * merges it in: configured providers first, everything else searchable
 * below, with a clear "(no key)" marker. Catalog is cached on disk so the
 * sheet opens instantly and works offline.
 *
 * Parsing stays defensive: whatever parses, parses; nothing throws.
 */
public final class Models {

    /** One selectable (providerID, modelID) pair (flat form, kept for compat). */
    public static final class Item {
        public String provider, id, label;
    }

    /** Grouped provider, P6/P9. */
    public static final class Prov {
        public String id, name;
        public boolean configured;          // has a key in auth.json
        public boolean usable;              // listed by /config/providers
        public final List<Mdl> models = new ArrayList<>();
    }

    public static final class Mdl {
        public String id, name, desc;
        public boolean free;                // P14: zero input+output cost
        public double costIn, costOut;      // P14: $/Mtok, shown in the sheet
        /** P25: context window (tokens) from the models.dev `limit.context`
         *  shape — drives the chat's context-depth meter. 0 = unknown. */
        public long ctx;
        /** P15 RESTORES the P12 flag the P14 rework dropped — the exact
         *  regression the user kept reporting ("the working model picker is
         *  in the first p12"). true when the RUNNING SERVER listed this
         *  model in /config/providers — picking it actually works.
         *  models.dev/bundled entries are discovery-only: selectable-looking
         *  but the server answers "Model not found", which is what made the
         *  picker "broken" again after P14 let every catalog row save. */
        public boolean live;
    }

    /** Last successful fetch — the picker reuses it for instant re-opens. */
    private static volatile List<Prov> lastFetch = new ArrayList<>();

    /** P13: where the last fetch's data came from — shown IN the picker so
     *  "only 7 zen models" failures are visible on-screen, not a mystery. */
    public static volatile String lastSource = "no fetch yet";

    private Models() {}

    public static List<Prov> lastFetch() {
        return lastFetch;
    }

    /** Fetch ALL providers + models (server list ⊕ models.dev catalog ⊕
     *  bundled snapshot floor — P13: the picker now shows the full catalog
     *  even with zero network, so “only 7 zen free models” can't return). */
    public static List<Prov> fetch(Context c) {
        Map<String, Prov> byId = new LinkedHashMap<>();
        boolean serverOk = false;
        String catSource = null;

        // ---- 1. the running server (authoritative for what is usable) ----
        try {
            Api.Resp r = Api.get("/config/providers");
            if (r.ok()) {
                Object root = Json.parse(r.body);
                Map<String, Object> m = Json.obj(root);
                List<Object> pl = null;
                if (m != null) {
                    pl = Json.arr(m.get("providers"));
                    if (pl == null) {
                        Map<String, Object> pm = Json.map(m, "providers");
                        if (pm != null) {
                            for (Map.Entry<String, Object> e : pm.entrySet()) {
                                Map<String, Object> p = Json.obj(e.getValue());
                                if (p == null) continue;
                                if (Json.str(p, "id") == null) p.put("id", e.getKey());
                                collectProvider(byId, p, c, true);
                            }
                        }
                    }
                } else {
                    pl = Json.arr(root);
                }
                if (pl != null) {
                    for (Object po : pl) {
                        Map<String, Object> p = Json.obj(po);
                        if (p != null) collectProvider(byId, p, c, true);
                    }
                }
                serverOk = !byId.isEmpty();
            }
        } catch (Exception ignored) {}

        // ---- 2. models.dev catalog (full discovery, disk-cached) ----
        // P15: NO early return here — the P12a bug (map-shaped provider
        // responses skipped the catalog merge) stays fixed; every source
        // always merges. The fix for "picker looks broken" is the live
        // flag above + the sheet refusing dead picks, NOT a smaller list.
        String cat = httpGet("https://models.dev/api.json", 8000);
        if (cat != null && cat.length() > 1000) {
            writeCache(c, cat);
            catSource = "models.dev live";
        } else {
            cat = readCache(c);
            if (cat != null && cat.length() > 1000) catSource = "cached catalog";
        }
        if (catSource == null) {
            // P13: bundled snapshot ships in the APK — the catalog floor.
            cat = readAsset(c, "models-dev.json");
            if (cat != null && cat.length() > 1000) catSource = "bundled catalog";
        }
        if (cat != null) {
            try {
                Map<String, Object> root = Json.obj(Json.parse(cat));
                if (root != null) {
                    for (Map.Entry<String, Object> e : root.entrySet()) {
                        Map<String, Object> p = Json.obj(e.getValue());
                        if (p == null) continue;
                        if (Json.str(p, "id") == null) p.put("id", e.getKey());
                        collectProvider(byId, p, c, false);
                    }
                }
            } catch (Exception ignored) {}
        }

        lastFetch = order(byId);
        lastSource = (serverOk ? "server" : "server offline")
                + " · " + (catSource != null ? catSource : "no catalog");
        return lastFetch;
    }

    /** P15: usable (server-live) providers first — the P12a order that made
     *  the picker feel right — then configured, then alphabetical. */
    private static List<Prov> order(Map<String, Prov> byId) {
        List<Prov> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> {
            if (a.usable != b.usable) return a.usable ? -1 : 1;   // live first
            if (a.configured != b.configured) return a.configured ? -1 : 1;
            return a.name.toLowerCase(Locale.US)
                    .compareTo(b.name.toLowerCase(Locale.US));
        });
        return out;
    }

    /** Package-private hook so the P15 regression tests pin the sort. */
    static List<Prov> orderForTest(Map<String, Prov> byId) {
        return order(byId);
    }

    private static void collectProvider(Map<String, Prov> byId, Map<String, Object> p,
                                        Context c, boolean fromServer) {
        String pid = Json.str(p, "id");
        if (pid == null || pid.isEmpty()) return;
        Prov prov = byId.get(pid);
        if (prov == null) {
            prov = new Prov();
            prov.id = pid;
            String pname = Json.str(p, "name");
            prov.name = (pname == null || pname.isEmpty()) ? pid : pname;
            prov.configured = AuthStore.hasKey(c, pid);
            byId.put(pid, prov);
        }
        if (fromServer) {
            prov.usable = true;
            if (AuthStore.hasKey(c, pid)) prov.configured = true;
        }
        Object models = p.get("models");
        if (models instanceof Map) {
            Set<String> have = new HashSet<>();
            for (Mdl m : prov.models) have.add(m.id);
            for (Map.Entry<String, Object> e
                    : ((Map<String, Object>) models).entrySet()) {
                Map<String, Object> mm = Json.obj(e.getValue());
                if (mm == null) continue;
                if (Json.str(mm, "id") == null) mm.put("id", e.getKey());
                String mid = Json.str(mm, "id");
                if (mid == null) continue;
                if (have.contains(mid)) {
                    // P27 flicker fix: the SAME model arrives from two
                    // sources with DIFFERENT context windows (the server's
                    // provider entry vs the models.dev catalog — field saw
                    // the Σ pill say 47% (200k window) then drop to 8% (1M)
                    // as fetches disagreed). The merge is now DETERMINISTIC:
                    // the curated catalog's limit wins whenever it has one.
                    if (!fromServer) upgradeCtx(prov, mid, mm);
                    continue;
                }
                addModel(prov, mm, fromServer);
                have.add(mid);
            }
        } else {
            List<Object> ml = Json.arr(models);
            if (ml != null) for (Object mo : ml) addModel(prov, Json.obj(mo), fromServer);
        }
    }

    /** P27: let the models.dev catalog correct an already-merged model's
     *  context window. Pure rule, JVM-pinned: catalog limit (>0) always
     *  wins; otherwise the first-seen value stands. */
    static void upgradeCtx(Prov prov, String mid, Map<String, Object> mm) {
        if (prov == null || mid == null || mm == null) return;
        long cat = parseCtx(mm);
        if (cat <= 0) return;
        for (Mdl m : prov.models) {
            if (mid.equals(m.id)) { m.ctx = cat; return; }
        }
    }

    private static void addModel(Prov prov, Map<String, Object> mm, boolean live) {
        if (mm == null) return;
        String mid = Json.str(mm, "id");
        if (mid == null || mid.isEmpty()) return;
        Mdl mdl = new Mdl();
        mdl.id = mid;
        String mn = Json.str(mm, "name");
        mdl.name = (mn == null || mn.isEmpty()) ? mid : mn;
        String d = Json.str(mm, "description");
        mdl.desc = (d == null || d.isEmpty()) ? null : d;
        mdl.live = live;                    // P15: P12 semantics restored
        // P14: cost block (models.dev schema) — $ per Mtok. Zero input AND
        // output cost = the "free" badge in the picker (31 zen models today).
        try {
            Map<String, Object> cost = Json.obj(mm.get("cost"));
            if (cost != null) {
                mdl.costIn = Json.num(cost, "input");
                mdl.costOut = Json.num(cost, "output");
                mdl.free = mdl.costIn == 0 && mdl.costOut == 0;
            }
        } catch (Exception ignored) {}
        mdl.ctx = parseCtx(mm);             // P25: context window
        prov.models.add(mdl);
    }

    /** P25: context window out of a model object. models.dev schema:
     *  "limit":{"context":200000,"output":8192}; some server shapes
     *  put context_window / context at top level. Defensive: whatever
     *  parses, parses; 0 when nothing sane is there. Package-private
     *  static so the JVM suite pins it. */
    static long parseCtx(Map<String, Object> mm) {
        if (mm == null) return 0;
        long v = 0;
        try {
            Map<String, Object> limit = Json.obj(mm.get("limit"));
            if (limit != null) v = (long) Json.num(limit, "context");
            if (v <= 0) v = (long) Json.num(mm, "context_window");
            if (v <= 0) v = (long) Json.num(mm, "context");
        } catch (Exception ignored) {}
        // sane bounds: a "context window" under 1k or over 100M is junk
        return (v >= 1024 && v <= 100_000_000L) ? v : 0;
    }

    /** P25: the context window (tokens) of the picked model, or 0 when
     *  unknown (no pick, not in the last fetch, or the catalog had no
     *  limit for it). Reads the in-memory fetch only — never network. */
    public static long contextLimitFor(List<Prov> provs, String provider, String id) {
        if (provs == null || provider == null || id == null) return 0;
        for (Prov p : provs) {
            if (!provider.equals(p.id)) continue;
            for (Mdl m : p.models) {
                if (id.equals(m.id)) return m.ctx;
            }
        }
        return 0;
    }

    // ------------------------------------------------- P27: sticky window

    private static final String KEY_CTXWIN = "ctxwin";
    /** At most this many (provider/model → window) entries live in prefs. */
    static final int CTXWIN_CAP = 64;

    /** The context window for the Σ pill, DETERMINISTIC for a session:
     *  the live fetch decides; when this fetch knows nothing (server blip,
     *  catalog offline) the last KNOWN window for this exact model stands
     *  in — the denominator can flicker between sources, never within a
     *  session once a value is on record. Known values are stashed for the
     *  next cold start. Never network. */
    public static long resolveLimit(Context c, List<Prov> provs,
                                    String provider, String id) {
        long v = contextLimitFor(provs, provider, id);
        if (v > 0) {
            stashLimit(c, provider, id, v);
            return v;
        }
        return stickyLimit(c, provider, id);
    }

    private static java.util.Map<String, String> ctxWinMap(Context c) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            java.util.Map<String, ?> all = c.getSharedPreferences(PREFS,
                    Context.MODE_PRIVATE).getAll();
            for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(KEY_CTXWIN + ":")
                        && e.getValue() instanceof String) {
                    out.put(e.getKey(), (String) e.getValue());
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void stashLimit(Context c, String provider, String id, long ctx) {
        if (c == null || provider == null || id == null || ctx <= 0) return;
        try {
            android.content.SharedPreferences ed0 = c.getSharedPreferences(PREFS,
                    Context.MODE_PRIVATE);
            String key = KEY_CTXWIN + ":" + provider + "/" + id;
            String cur = ed0.getString(key, null);
            if (cur != null && cur.equals(String.valueOf(ctx))) return;
            android.content.SharedPreferences.Editor ed = ed0.edit().putString(
                    key, String.valueOf(ctx));
            // bounded: eldest entries (insertion order of the map) evicted
            java.util.Map<String, String> all = ctxWinMap(c);
            all.remove(key);
            while (all.size() >= CTXWIN_CAP) {
                String eldest = all.keySet().iterator().next();
                ed.remove(eldest);
                all.remove(eldest);
            }
            ed.apply();
        } catch (Exception ignored) {}
    }

    static long stickyLimit(Context c, String provider, String id) {
        if (c == null || provider == null || id == null) return 0;
        try {
            String v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CTXWIN + ":" + provider + "/" + id, null);
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------- catalog net

    /** Plain-HTTPS GET to models.dev (bionic/Java resolver — no sandbox). */
    private static String httpGet(String url, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) { c.disconnect(); return null; }
            String body = Api.readAll(c.getInputStream());
            c.disconnect();
            return body;
        } catch (Exception e) {
            return null;
        }
    }

    private static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "models-cache.json");
    }

    private static void writeCache(Context c, String body) {
        try {
            File f = cacheFile(c);
            File tmp = new File(f.getParentFile(), f.getName() + ".part");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(body.getBytes("UTF-8"));
            }
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Exception ignored) {}
    }

    private static String readCache(Context c) {
        try {
            return Api.readAll(new FileInputStream(cacheFile(c)));
        } catch (Exception e) {
            return null;
        }
    }

    /** P13: APK-bundled models.dev snapshot — the offline catalog floor. */
    private static String readAsset(Context c, String name) {
        try {
            return Api.readAll(c.getAssets().open(name));
        } catch (Exception e) {
            return null;
        }
    }

    /** True when (provider, id) exists AND the running server serves it.
     *  P15: back to the P12 rule — discovery (models.dev / bundled) entries
     *  do NOT pass, so validateSelectedModel() self-heals dead picks BEFORE
     *  the request instead of the server answering "Model not found". This
     *  exact gate is why the first P12 picker felt like it worked. */
    public static boolean available(List<Prov> provs, String provider, String id) {
        if (provs == null || provider == null || id == null) return false;
        for (Prov p : provs) {
            if (!provider.equals(p.id)) continue;
            for (Mdl m : p.models)
                if (id.equals(m.id)) return m.live;
        }
        return false;
    }

    /** Flatten to a plain item list (kept for send-path compatibility). */
    public static List<Item> flatten(List<Prov> provs) {
        List<Item> out = new ArrayList<>();
        for (Prov p : provs) {
            for (Mdl m : p.models) {
                Item it = new Item();
                it.provider = p.id;
                it.id = m.id;
                it.label = m.name + "   ·   " + p.id;
                out.add(it);
            }
        }
        return out;
    }

    // ------------------------------------------------- selection (prefs)

    private static final String PREFS = "oc";
    private static final String KEY = "model";
    /** P26: set when the user deliberately picks a discovery-catalog model
     *  ("· catalog", dim). The free list rotates, so the pick may work —
     *  and if the server refuses, the run-time model-not-found self-heal
     *  clears it and falls back to the server default. */
    private static final String KEY_FORCED = "model_forced";

    /** Persist the picked model as "providerID/modelID" (server-listed pick). */
    public static void save(Context c, String provider, String id) {
        save(c, provider, id, false);
    }

    /** Persist the pick + whether it was forced out of the discovery
     *  catalog (try-anyway semantics). */
    public static void save(Context c, String provider, String id, boolean forced) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, provider + "/" + id)
                .putBoolean(KEY_FORCED, forced)
                .apply();
    }

    /** Clear the per-session override. */
    public static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY).remove(KEY_FORCED).apply();
    }

    /** True when the current pick was a deliberate catalog choice (P26). */
    public static boolean forced(Context c) {
        if (c == null) return false;
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_FORCED, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** The picked model as {providerID, modelID}, or null for server default. */
    public static String[] selected(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null);
        if (s == null) return null;
        int i = s.indexOf('/');
        if (i <= 0 || i == s.length() - 1) return null;
        return new String[]{s.substring(0, i), s.substring(i + 1)};
    }
}
