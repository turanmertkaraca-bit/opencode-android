package ai.opencode.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P6 model catalog: EVERY provider the server knows, grouped, searchable.
 *
 * Endpoint verified against the shipped v1.18.25 binary:
 *   GET /config/providers
 * and the message body accepts
 *   {"parts":[...], "model":{"providerID":..,"modelID":..}}
 * (scan evidence: "variant:Ze,model:{providerID:k,modelID:be},parts:[...").
 *
 * Parsing stays defensive: the response may be {"providers":[...]} / a bare
 * array / an object keyed by provider id; models may be a map or an array.
 * Whatever parses, parses; nothing throws. Model info fields seen in the
 * models.dev catalog embedded server-side: id, name, description, pricing.
 */
public final class Models {

    /** One selectable (providerID, modelID) pair (flat form, kept for compat). */
    public static final class Item {
        public String provider, id, label;
    }

    /** Grouped provider, P6. */
    public static final class Prov {
        public String id, name;
        public boolean configured;          // has a key in auth.json
        public final List<Mdl> models = new ArrayList<>();
    }

    public static final class Mdl {
        public String id, name, desc;
    }

    /** Last successful fetch — the picker reuses it for instant re-opens. */
    private static volatile List<Prov> lastFetch = new ArrayList<>();

    private Models() {}

    public static List<Prov> lastFetch() {
        return lastFetch;
    }

    /** Fetch ALL providers + models from the running server. Never null. */
    @SuppressWarnings("unchecked")
    public static List<Prov> fetch(Context c) {
        List<Prov> out = new ArrayList<>();
        try {
            Api.Resp r = Api.get("/config/providers");
            if (!r.ok()) { lastFetch = out; return out; }
            Object root = Json.parse(r.body);
            Map<String, Object> m = Json.obj(root);

            List<Object> pl = null;
            if (m != null) {
                pl = Json.arr(m.get("providers"));
                if (pl == null) {
                    Map<String, Object> pm = Json.map(m, "providers");
                    if (pm != null) {
                        // object keyed by provider id
                        for (Map.Entry<String, Object> e : pm.entrySet()) {
                            Map<String, Object> p = Json.obj(e.getValue());
                            if (p == null) continue;
                            if (Json.str(p, "id") == null) p.put("id", e.getKey());
                            collectProvider(out, p, c);
                        }
                        lastFetch = out;
                        return out;
                    }
                }
            } else {
                pl = Json.arr(root);
            }
            if (pl != null) {
                for (Object po : pl) {
                    Map<String, Object> p = Json.obj(po);
                    if (p != null) collectProvider(out, p, c);
                }
            }
        } catch (Exception ignored) {}
        lastFetch = out;
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectProvider(List<Prov> out, Map<String, Object> p,
                                        Context c) {
        String pid = Json.str(p, "id");
        if (pid == null || pid.isEmpty()) return;
        Prov prov = new Prov();
        prov.id = pid;
        String pname = Json.str(p, "name");
        prov.name = (pname == null || pname.isEmpty()) ? pid : pname;
        prov.configured = AuthStore.hasKey(c, pid);

        Object models = p.get("models");
        if (models instanceof Map) {
            for (Map.Entry<String, Object> e
                    : ((Map<String, Object>) models).entrySet()) {
                Map<String, Object> mm = Json.obj(e.getValue());
                if (mm == null) continue;
                if (Json.str(mm, "id") == null) mm.put("id", e.getKey());
                addModel(prov, mm);
            }
        } else {
            List<Object> ml = Json.arr(models);
            if (ml != null) for (Object mo : ml) addModel(prov, Json.obj(mo));
        }
        out.add(prov);
    }

    private static void addModel(Prov prov, Map<String, Object> mm) {
        if (mm == null) return;
        String mid = Json.str(mm, "id");
        if (mid == null || mid.isEmpty()) return;
        Mdl mdl = new Mdl();
        mdl.id = mid;
        String mn = Json.str(mm, "name");
        mdl.name = (mn == null || mn.isEmpty()) ? mid : mn;
        String d = Json.str(mm, "description");
        mdl.desc = (d == null || d.isEmpty()) ? null : d;
        prov.models.add(mdl);
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
