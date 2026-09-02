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
        /** P12: true when the RUNNING SERVER listed this model in
         *  /config/providers — i.e. picking it actually works. models.dev
         *  entries are discovery-only (the old picker let you select them,
         *  the server answered "Model not found", and the picker looked
         *  broken — the exact recurring complaint). */
        public boolean live;
    }

    /** Last successful fetch — the picker reuses it for instant re-opens. */
    private static volatile List<Prov> lastFetch = new ArrayList<>();

    private Models() {}

    public static List<Prov> lastFetch() {
        return lastFetch;
    }

    /** Fetch ALL providers + models (server list ⊕ models.dev catalog). */
    public static List<Prov> fetch(Context c) {
        Map<String, Prov> byId = new LinkedHashMap<>();

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
                            lastFetch = order(byId);
                            return lastFetch;
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
            }
        } catch (Exception ignored) {}

        // ---- 2. models.dev catalog (full discovery, disk-cached) ----
        String cat = httpGet("https://models.dev/api.json", 8000);
        if (cat != null && cat.length() > 1000) {
            writeCache(c, cat);
        } else {
            cat = readCache(c);
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
        return lastFetch;
    }

    /** Configured/usable providers first, then alphabetical by name. */
    private static List<Prov> order(Map<String, Prov> byId) {
        List<Prov> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> {
            if (a.usable != b.usable) return a.usable ? -1 : 1;   // P12: live first
            if (a.configured != b.configured) return a.configured ? -1 : 1;
            return a.name.toLowerCase(Locale.US)
                    .compareTo(b.name.toLowerCase(Locale.US));
        });
        return out;
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
                if (mid == null || have.contains(mid)) continue;
                addModel(prov, mm, fromServer);
                have.add(mid);
            }
        } else {
            List<Object> ml = Json.arr(models);
            if (ml != null) for (Object mo : ml) addModel(prov, Json.obj(mo), fromServer);
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
        mdl.live = live;
        prov.models.add(mdl);
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

    /** True when (provider, id) exists AND was listed by the running
     *  server (live). P12: models.dev discovery entries no longer pass —
     *  picking one used to guarantee "Model not found" at run time. */
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

    /** Persist the picked model as "providerID/modelID". */
    public static void save(Context c, String provider, String id) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, provider + "/" + id).apply();
    }

    /** Clear the per-session override. */
    public static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
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
