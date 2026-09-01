package ai.opencode.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P5: model catalog + selection.
 *
 * Endpoint verified against the shipped v1.18.25 binary:
 *   GET /config/providers
 * and the message body accepts
 *   {"parts":[...], "model":{"providerID":..,"modelID":..}}
 * (scan evidence: "variant:Ze,model:{providerID:k,modelID:be},parts:[...").
 *
 * Parsing is defensive: the response shape may be {"providers":[...]} or a
 * bare array; each provider carries models as EITHER a map (id -> info) or
 * an array of info objects. Whatever parses, parses; nothing throws.
 */
public final class Models {

    /** One selectable (providerID, modelID) pair. */
    public static final class Item {
        public String provider, id, label;
    }

    private Models() {}

    /** Fetch the provider/model list from the running server. Never null. */
    @SuppressWarnings("unchecked")
    public static List<Item> fetch() {
        List<Item> out = new ArrayList<>();
        try {
            Api.Resp r = Api.get("/config/providers");
            if (!r.ok()) return out;
            Object root = Json.parse(r.body);
            Map<String, Object> m = Json.obj(root);
            Object provs = (m != null) ? m.get("providers") : root;
            List<Object> pl = Json.arr(provs);
            if (pl == null && m != null) pl = Json.list(m, "providers");
            if (pl == null) return out;
            for (Object po : pl) {
                Map<String, Object> p = Json.obj(po);
                if (p == null) continue;
                String pid = Json.str(p, "id");
                if (pid == null || pid.isEmpty()) continue;
                String pname = Json.str(p, "name");
                Object models = p.get("models");
                if (models instanceof Map) {
                    for (Object mo : ((Map<String, Object>) models).values()) {
                        addModel(out, pid, pname, Json.obj(mo));
                    }
                } else {
                    List<Object> ml = Json.arr(models);
                    if (ml != null) for (Object mo : ml) addModel(out, pid, pname, Json.obj(mo));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void addModel(List<Item> out, String pid, String pname,
                                 Map<String, Object> mm) {
        if (mm == null) return;
        String mid = Json.str(mm, "id");
        if (mid == null || mid.isEmpty()) return;
        Item it = new Item();
        it.provider = pid;
        it.id = mid;
        String mn = Json.str(mm, "name");
        String display = (mn == null || mn.isEmpty()) ? mid : mn;
        it.label = display + "   ·   " + pid;
        out.add(it);
    }

    // ------------------------------------------------- selection (prefs)

    private static final String PREFS = "oc";
    private static final String KEY = "model";

    /** Persist the picked model as "providerID/modelID". */
    public static void save(Context c, String provider, String id) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, provider + "/" + id).apply();
    }

    /** Clear the override (server default model is used again). */
    public static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    /** The picked model as {providerID, modelID}, or null for server default. */
    public static String[] selected(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        if (s == null) return null;
        int i = s.indexOf('/');
        if (i <= 0 || i == s.length() - 1) return null;
        return new String[]{s.substring(0, i), s.substring(i + 1)};
    }
}
