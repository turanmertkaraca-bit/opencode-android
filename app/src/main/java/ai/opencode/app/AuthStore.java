package ai.opencode.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P6: provider credentials & config, managed IN THE APP — no more hunting
 * for auth.json on a desktop machine.
 *
 * Files (verified layout, P1):
 *   files/home/.local/share/opencode/auth.json    — {providerID:{type,key}}
 *   files/home/.config/opencode/opencode.json     — {"model":"pid/mid",...}
 *
 * auth.json shape: the server reads XDG_DATA_HOME/opencode/auth.json with
 * entries keyed by providerID (binary scan: Me() joins XDG_DATA_HOME,
 * "opencode","auth.json"; CLI lists "credentials" from it; entries carry
 * apiKey / type / expiry for OAuth). We write the API-key form:
 *   {"anthropic":{"type":"api","key":"sk-ant-..."}, ...}
 *
 * Custom OpenAI-compatible providers (scan evidence in the binary:
 * api.npm === "@ai-sdk/openai-compatible" → configured with baseURL):
 *   opencode.json gets
 *   "provider":{"<id>":{"npm":"@ai-sdk/openai-compatible","name":"...",
 *     "options":{"baseURL":"https://host/v1","apiKey":"..."},
 *     "models":{"<model>":{"name":"<model>"}}}}
 * plus an auth.json entry for the key (belt & braces).
 */
public final class AuthStore {

    private AuthStore() {}

    /** Known providers for the Connect screen: id, display name, key hint.
     *  P12: the first row is THE "opencode" provider — display name no
     *  longer says only "Zen", because users with a plain opencode API key
     *  couldn't tell where it goes (the exact P12 report).
     *
     *  P16 — the P14 claim "Zen and Go are plans on the same gateway, same
     *  row, same key" was WRONG and the user proved it in the field: the
     *  bundled models.dev catalog (and the server) carry TWO distinct
     *  opencode providers — "opencode" (OpenCode Zen, 97 models) and
     *  "opencode-go" (OpenCode Go, 34 models) — each authenticated with
     *  its OWN key. With no Go row, users were stuck at "add opencode go
     *  key to use this" with nowhere to add it. Both rows now exist,
     *  ids match the catalog/server exactly. */
    public static final String[][] KNOWN = {
            {"opencode",     "OpenCode Zen", "your opencode ZEN key (console.opencode.ai) — zen models · SEPARATE from the Go key · free ones run keyless"},
            {"opencode-go",  "OpenCode Go",  "your opencode GO key (console.opencode.ai) — SEPARATE from the Zen key, powers the Go models"},
            {"anthropic",    "Anthropic",    "sk-ant-…"},
            {"openai",       "OpenAI",       "sk-…"},
            {"google",       "Google",       "AIza…"},
            {"openrouter",   "OpenRouter",   "sk-or-…"},
            {"groq",         "Groq",         "gsk_…"},
            {"xai",          "xAI",          "xai-…"},
            {"mistral",      "Mistral",      "…"},
            {"deepseek",     "DeepSeek",     "sk-…"},
            {"together",     "TogetherAI",   "…"},
            {"perplexity",   "Perplexity",   "pplx-…"},
    };

    // ------------------------------------------------------------- file io

    private static Map<String, Object> readJson(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 4_000_000)];
            int n = in.read(buf);
            if (n <= 0) return new LinkedHashMap<>();
            return Json.obj(Json.parse(new String(buf, 0, n)));
        } catch (Exception e) {
            return null; // malformed → caller decides (we recreate)
        }
    }

    private static void writeJson(File f, Map<String, Object> root) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(Json.write(root).getBytes("UTF-8"));
        }
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("rename failed");
        }
    }

    private static Map<String, Object> root(File f) throws IOException {
        Map<String, Object> r = readJson(f);
        return r == null ? new LinkedHashMap<>() : r;
    }

    // ---------------------------------------------------------------- auth

    /** providerID → entry map (never null; empty when no file). */
    public static Map<String, Object> readAuth(Context c) {
        try {
            return root(Binaries.authFile(c));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** True when at least one provider entry exists. */
    public static boolean hasAnyKey(Context c) {
        return !readAuth(c).isEmpty();
    }

    /** True when this provider has an entry. */
    public static boolean hasKey(Context c, String providerID) {
        return readAuth(c).containsKey(providerID);
    }

    /** Save (or replace) one provider's API key. */
    public static void setApiKey(Context c, String providerID, String key)
            throws IOException {
        if (providerID == null || providerID.trim().isEmpty()) return;
        if (key == null) key = "";
        key = key.trim();
        Map<String, Object> auth = root(Binaries.authFile(c));
        if (key.isEmpty()) {
            auth.remove(providerID);
        } else {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "api");
            entry.put("key", key);
            auth.put(providerID, entry);
        }
        writeJson(Binaries.authFile(c), auth);
    }

    // -------------------------------------------------------------- config

    /** opencode.json root (never null). */
    public static Map<String, Object> readConfig(Context c) {
        try {
            return root(Binaries.configFile(c));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static void writeConfig(Context c, Map<String, Object> cfg)
            throws IOException {
        writeJson(Binaries.configFile(c), cfg);
    }

    /** Set the server-wide default model: {"model":"provider/model"}. */
    public static void setDefaultModel(Context c, String providerID, String modelID)
            throws IOException {
        Map<String, Object> cfg = readConfig(c);
        cfg.put("model", providerID + "/" + modelID);
        writeConfig(c, cfg);
    }

    /** Stored default model as {providerID, modelID}, or null. */
    public static String[] defaultModel(Context c) {
        String s = Json.str(readConfig(c), "model");
        if (s == null) return null;
        int i = s.indexOf('/');
        if (i <= 0 || i == s.length() - 1) return null;
        return new String[]{s.substring(0, i), s.substring(i + 1)};
    }

    /**
     * Register an OpenAI-compatible provider in opencode.json and (optionally)
     * keep its key in auth.json too. modelID may be null (user can pick later
     * from the fetched list — most compatible endpoints report their models).
     */
    @SuppressWarnings("unchecked")
    public static void addCustomProvider(Context c, String id, String name,
                                         String baseURL, String apiKey,
                                         String modelID) throws IOException {
        id = id.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        if (id.isEmpty()) throw new IOException("provider id is empty");
        if (baseURL == null || baseURL.trim().isEmpty())
            throw new IOException("base URL is empty");
        Map<String, Object> cfg = readConfig(c);

        Map<String, Object> provs = Json.map(cfg, "provider");
        if (provs == null) { provs = new LinkedHashMap<>(); cfg.put("provider", provs); }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("baseURL", baseURL.trim());
        if (apiKey != null && !apiKey.trim().isEmpty())
            options.put("apiKey", apiKey.trim());

        Map<String, Object> pdef = new LinkedHashMap<>();
        pdef.put("npm", "@ai-sdk/openai-compatible");
        pdef.put("name", name == null || name.trim().isEmpty() ? id : name.trim());
        pdef.put("options", options);
        if (modelID != null && !modelID.trim().isEmpty()) {
            Map<String, Object> models = new LinkedHashMap<>();
            Map<String, Object> mdef = new LinkedHashMap<>();
            mdef.put("name", modelID.trim());
            models.put(modelID.trim(), mdef);
            pdef.put("models", models);
        }
        provs.put(id, pdef);
        writeConfig(c, cfg);

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            setApiKey(c, id, apiKey);
        }
    }

    /** Merge a SAF-imported auth.json over the existing one. */
    public static void importAuth(Context c, File src) throws IOException {
        Map<String, Object> imported = readJson(src);
        if (imported == null) throw new IOException("not a valid auth.json");
        Map<String, Object> auth = root(Binaries.authFile(c));
        auth.putAll(imported);
        writeJson(Binaries.authFile(c), auth);
    }
}
