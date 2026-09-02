import java.util.*;

/**
 * P11 self-test (host JVM): pure-logic mirrors of the ChatActivity/Models
 * changes. The live-server counterpart runs in probe7.sh.
 */
public class TestP11 {

    static int pass = 0, fail = 0;
    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name + (detail == null ? "" : "  [" + detail + "]")); }
        else    { fail++; System.out.println("* FAIL  " + name + (detail == null ? "" : "  [" + detail + "]")); }
    }
    static void check(String name, boolean ok) { check(name, ok, null); }

    // ---- 1:1 mirrors of the P11 code under test -------------------------
    static List<String> buildBodies(String[] sel, String agent, String q) {
        LinkedHashMap<String, String> variants = new LinkedHashMap<>();
        String text = "{\"type\":\"text\",\"text\":" + quote(q) + "}";
        String model = sel == null ? null
                : "\"model\":{\"providerID\":" + quote(sel[0])
                + ",\"modelID\":" + quote(sel[1]) + "}";
        String ag = "\"agent\":" + quote(agent);
        String parts = "\"parts\":[" + text + "]";
        if (model != null) variants.put("ma", "{" + model + "," + ag + "," + parts + "}");
        if (model != null) variants.put("m", "{" + model + "," + parts + "}");
        variants.put("a", "{" + ag + "," + parts + "}");
        variants.put("bare", "{" + parts + "}");
        return new ArrayList<>(variants.values());
    }

    static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static boolean isModelNotFound(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.US);
        return l.contains("model not found") || l.contains("unknown model")
                || l.contains("providedmodelnotfound");
    }

    static boolean isStreamFlake(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.US);
        return l.contains("upstream idle timeout")
                || l.contains("streaming response failed")
                || l.contains("cannot connect")
                || l.contains("connection reset")
                || l.contains("econnreset");
    }

    static final class Prov { String id; List<String> models = new ArrayList<>(); }
    static boolean available(List<Prov> provs, String provider, String id) {
        if (provs == null || provider == null || id == null) return false;
        for (Prov p : provs) {
            if (!provider.equals(p.id)) continue;
            for (String m : p.models) if (id.equals(m)) return true;
        }
        return false;
    }

    // ---- error strings captured LIVE from the v1.18.25 server (probe2/3) --
    static final String LIVE_NOT_FOUND =
            "Cause([Fail(ProviderModelNotFoundError: Model not found: opencode/gpt-5-nano. "
            + "Did you mean: gpt-5-nano, gpt-5.4-nano?)])";
    static final String LIVE_FLAKE =
            "Streaming response failed: [504] Upstream idle timeout exceeded";
    static final String LIVE_CONN =
            "AI_APICallError: Cannot connect to API: Unable to connect.";

    public static void main(String[] a) {
        // 1) variant ORDER: model variants first, agent dropped before model
        List<String> bodies = buildBodies(new String[]{"opencode", "mimo-v2.5-free"}, "build", "hi");
        check("4 bodies when model picked", bodies.size() == 4, String.valueOf(bodies.size()));
        check("order ma → m → a → bare",
                bodies.get(0).contains("\"model\"") && bodies.get(0).contains("\"agent\"")
             && bodies.get(1).contains("\"model\"") && !bodies.get(1).contains("\"agent\"")
             && !bodies.get(2).contains("\"model\"") && bodies.get(2).contains("\"agent\"")
             && !bodies.get(3).contains("\"model\"") && !bodies.get(3).contains("\"agent\""),
             bodies.get(1).substring(0, 40));
        check("model variants precede agent-only",
                bodies.get(0).indexOf("\"model\"") >= 0
             && bodies.get(1).indexOf("\"model\"") < bodies.get(2).indexOf("\"parts\""), null);
        List<String> bareOnly = buildBodies(null, "plan", "hi");
        check("2 bodies when no model picked", bareOnly.size() == 2, String.valueOf(bareOnly.size()));

        // 2) "Model not found" detection against the LIVE server phrasing
        check("detects live ProviderModelNotFoundError", isModelNotFound(LIVE_NOT_FOUND));
        check("detects plain 'Model not found'", isModelNotFound("Model not found: opencode/x"));
        check("no false positive on auth error",
                !isModelNotFound("Unauthorized: bad API key"));
        check("no false positive on success", !isModelNotFound(null));

        // 3) stream-flake detection against LIVE phrasing
        check("detects live 504 idle timeout", isStreamFlake(LIVE_FLAKE));
        check("detects live cannot-connect", isStreamFlake(LIVE_CONN));
        check("no flake false positive on not-found", !isStreamFlake(LIVE_NOT_FOUND));

        // 4) catalog validation (stale pick self-heal gate)
        Prov zen = new Prov(); zen.id = "opencode";
        zen.models.addAll(Arrays.asList("nemotron-3.5-lightning-free", "nemotron-3-ultra-free",
                "ling-3.0-flash-fin-free", "muse-spark-1.2-contributor-free",
                "mimo-v2.5-free", "big-pickle"));
        List<Prov> provs = new ArrayList<>(); provs.add(zen);
        check("stale P7-era pick NOT in live catalog",
                !available(provs, "opencode", "gpt-5-nano"));
        check("current free pick IS in live catalog",
                available(provs, "opencode", "mimo-v2.5-free"));
        check("wrong provider not found",
                !available(provs, "openrouter", "mimo-v2.5-free"));
        check("null/empty safe", !available(null, "x", "y") && !available(provs, null, "y"));

        System.out.println();
        System.out.println("P11 logic: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
