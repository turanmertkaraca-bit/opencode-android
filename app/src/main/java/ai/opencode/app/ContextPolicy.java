package ai.opencode.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P42 — the user-facing context window cap, PURE so the JVM suite pins
 * every rule.
 *
 * THE FIELD ASK: "maybe something like a slider for context limit would
 * be cool". What the slider actually moves is the model's
 * {@code limit.context} — the number the bundled server believes and
 * enforces: it decides when the silent compaction fires (P41) and what
 * the Σ pill's denominator is. The override rides the SAME
 * models.dev shape the server already parses (the binary carries
 * "limit.context" natively; {@link Models#parseCtx} reads it back from
 * /config/providers), written into opencode.json under
 * {@code provider.<pid>.models.<mid>.limit.context}.
 *
 * Rules:
 *   - bounds [MIN, MAX], quantized to STEP — a cap below the compaction
 *     floor's own minimum would make every compaction a no-op, and a
 *     cap above 2M is fantasy for every model this app can name;
 *   - 0 = "clear my override" — the removal also tidies the empty
 *     shells it leaves behind (limit → models → provider), so a user
 *     reading opencode.json never findsour scaffolding;
 *   - the write is USER-owned by definition (it IS the user's edit),
 *     so no app-ownership marker applies here — unlike the P41
 *     compaction floor, the app never silently rewrites this value.
 */
public final class ContextPolicy {

    private ContextPolicy() {}

    /** Below this the P41 floor math refuses to write at all
     *  (CompactionPolicy.MIN_WINDOW) — matching it keeps the two
     *  governance knobs coherent. */
    public static final long MIN = 64_000;

    /** No model this app can name has a real window past 2M. */
    public static final long MAX = 2_000_000;

    /** Slider quantum (tokens) — smooth enough to feel analog. */
    public static final long STEP = 1_000;

    /** Slider presets, ascending, always inside [MIN, MAX]. */
    public static final long[] PRESETS = {64_000, 128_000, 200_000, 512_000, 1_000_000};

    /** Clamp + quantize a raw slider value. 0/negative stays 0 (clear). */
    public static long normalize(long raw) {
        if (raw <= 0) return 0;
        long n = (raw / STEP) * STEP;
        if (n < MIN) n = MIN;
        if (n > MAX) n = MAX;
        return n;
    }

    /** The current override (0 = none) straight from an opencode.json
     *  root map. Defensive against every junk shape. */
    public static long readContextLimit(Map<String, Object> root,
                                        String pid, String mid) {
        Map<String, Object> m = modelNode(root, pid, mid);
        if (m == null) return 0;
        Map<String, Object> limit = Json.obj(m.get("limit"));
        if (limit == null) return 0;
        Object v = limit.get("context");
        if (!(v instanceof Number)) return 0;
        long n = ((Number) v).longValue();
        return n > 0 ? n : 0;
    }

    /**
     * Write (or clear, n ≤ 0) the override IN PLACE. True when the map
     * changed. Pure; the suite pins both directions and the shell
     * cleanup.
     */
    public static boolean mergeContextLimit(Map<String, Object> root,
                                            String pid, String mid, long n) {
        if (root == null || pid == null || pid.isEmpty()
                || mid == null || mid.isEmpty()) return false;
        Map<String, Object> provs = Json.obj(root.get("provider"));
        if (provs == null) {
            if (n <= 0) return false;
            provs = new LinkedHashMap<>();
            root.put("provider", provs);
        }
        Object pRaw = provs.get(pid);
        if (pRaw != null && !(pRaw instanceof Map)) return false;   // user junk: hands off
        Map<String, Object> pdef = Json.obj(pRaw);
        if (pdef == null) {
            if (n <= 0) return false;
            pdef = new LinkedHashMap<>();
            provs.put(pid, pdef);
        }
        Map<String, Object> models = Json.obj(pdef.get("models"));
        if (models == null) {
            if (n <= 0) return false;
            models = new LinkedHashMap<>();
            pdef.put("models", models);
        }
        Object mRaw = models.get(mid);
        if (mRaw != null && !(mRaw instanceof Map)) return false;   // user junk: hands off
        Map<String, Object> mdef = Json.obj(mRaw);
        if (mdef == null) {
            if (n <= 0) return false;
            mdef = new LinkedHashMap<>();
            models.put(mid, mdef);
        }
        Map<String, Object> limit = Json.obj(mdef.get("limit"));
        if (n <= 0) {
            // clear: remove the override, then tidy the shells it left
            boolean changed = false;
            if (limit != null && limit.containsKey("context")) {
                limit.remove("context");
                changed = true;
            }
            if (limit != null && limit.isEmpty()) mdef.remove("limit");
            if (mdef.isEmpty() && models.containsKey(mid)) models.remove(mid);
            return changed;
        }
        long norm = normalize(n);
        if (limit == null) {
            limit = new LinkedHashMap<>();
            mdef.put("limit", limit);
        }
        Object cur = limit.get("context");
        if (cur instanceof Number && ((Number) cur).longValue() == norm)
            return false;
        limit.put("context", norm);
        return true;
    }

    private static Map<String, Object> modelNode(Map<String, Object> root,
                                                 String pid, String mid) {
        if (root == null || pid == null || mid == null) return null;
        Map<String, Object> provs = Json.obj(root.get("provider"));
        if (provs == null) return null;
        Map<String, Object> pdef = Json.obj(provs.get(pid));
        if (pdef == null) return null;
        Map<String, Object> models = Json.obj(pdef.get("models"));
        if (models == null) return null;
        return Json.obj(models.get(mid));
    }
}
