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

    /** True when at least one provider entry exists — in auth.json OR as
     *  a custom provider with an inline apiKey in opencode.json (P33: the
     *  "no API key yet" subtitle must not nag a user whose only key lives
     *  in a custom provider's options — the belt-and-braces write covers
     *  most cases, but hand-imported configs carry options-only keys). */
    public static boolean hasAnyKey(Context c) {
        if (!readAuth(c).isEmpty()) return true;
        return configHasEmbeddedKey(readConfig(c));
    }

    /** P33 pure rule: does this opencode.json config declare at least one
     *  provider with a non-empty options.apiKey? JVM-pinned. */
    public static boolean configHasEmbeddedKey(Map<String, Object> cfg) {
        if (cfg == null) return false;
        Map<String, Object> provs = Json.map(cfg, "provider");
        if (provs == null) return false;
        for (Object o : provs.values()) {
            Map<String, Object> p = Json.obj(o);
            if (p == null) continue;
            Map<String, Object> opts = Json.map(p, "options");
            if (opts == null) continue;
            String k = Json.str(opts, "apiKey");
            if (k != null && !k.trim().isEmpty()) return true;
        }
        return false;
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
        // P51: every config the APP writes passes the cap sanitizer, so
        // the slider (or any future writer) can never leave a value the
        // server would boot on.
        ContextPolicy.sanitizeConfig(cfg);
        writeJson(Binaries.configFile(c), cfg);
    }

    /**
     * P51: heal opencode.json BEFORE the server reads it — catches
     * values that arrived outside the app's own write path (an agent
     * editing the file, a hand edit through Diagnostics). Clamps every
     * provider model limit.context into [ContextPolicy.MIN, MAX] and
     * drops junk shapes. Returns true when the file was rewritten.
     */
    public static boolean healConfig(Context c) {
        try {
            Map<String, Object> cfg = readConfig(c);
            if (!ContextPolicy.sanitizeConfig(cfg)) return false;
            writeJson(Binaries.configFile(c), cfg);
            return true;
        } catch (Exception e) {
            return false;               // unreadable config is the server's problem
        }
    }

    /** Set the server-wide default model: {"model":"provider/model"}. */
    public static void setDefaultModel(Context c, String providerID, String modelID)
            throws IOException {
        Map<String, Object> cfg = readConfig(c);
        cfg.put("model", providerID + "/" + modelID);
        writeConfig(c, cfg);
    }

    /**
     * P41: pin the post-compaction memory floor in opencode.json — the
     * server reads compaction.preserve_recent_tokens at boot and keeps
     * that many tokens of recent turns VERBATIM when it summarizes a
     * full context (the server default keeps only 2k–15k, which is how
     * the field lost its thread after every silent compaction). The
     * merge rule lives in {@link CompactionPolicy} (pure, suite-pinned):
     * P42 upgraded it to merge-OR-MOVE — the app may update a value it
     * previously wrote (ownership tracked in the
     * {@code compaction_app_written} pref) so a model switch can drag
     * the floor to the new window, while a user-edited value stays
     * untouchable. True when the key was added or moved by this call.
     * Throws only on a failed file write — callers treat that as one
     * diagnostics line, never a boot blocker.
     */
    public static boolean ensureCompactionPreserve(Context c, long limit)
            throws IOException {
        Map<String, Object> cfg = readConfig(c);
        long appWritten = 0;
        try {
            appWritten = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getLong("compaction_app_written", 0);
        } catch (Exception ignored) {}
        long v = CompactionPolicy.mergeOrMovePreserve(cfg, limit, appWritten);
        if (v > 0) {
            writeConfig(c, cfg);
            try {
                c.getSharedPreferences("oc", Context.MODE_PRIVATE).edit()
                        .putLong("compaction_app_written", v).apply();
            } catch (Exception ignored) {}
            return true;
        }
        return false;
    }

    /** P45: the auto-compact master switch's current state. DEFAULT
     *  OFF — the app never silently compacts; that is the shipped
     *  behavior, not an option buried three screens deep. */
    public static boolean compactionAuto(Context c) {
        try {
            return c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getBoolean("compact_auto", false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * P45: pin {@code compaction.auto} to the switch state — the boot
     * path. Hands-off merge (CompactionPolicy.mergeAuto, force=false):
     * a value the user hand-edited into opencode.json is never touched;
     * an absent or app-written key is brought in line with the switch.
     * True when the file changed. One diagnostics line on failure,
     * never a boot blocker (same contract as ensureCompactionPreserve).
     */
    public static boolean ensureCompactionAuto(Context c) throws IOException {
        boolean auto = compactionAuto(c);
        Map<String, Object> cfg = readConfig(c);
        int appWritten;
        try {
            appWritten = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getInt("compaction_auto_app", -1);
        } catch (Exception e) {
            appWritten = -1;
        }
        if (!CompactionPolicy.mergeAuto(cfg, auto, appWritten, false))
            return false;
        writeConfig(c, cfg);
        c.getSharedPreferences("oc", Context.MODE_PRIVATE).edit()
                .putInt("compaction_auto_app", auto ? 1 : 0).apply();
        return true;
    }

    /**
     * P45: the Settings toggle's write-through. The click IS the user's
     * newest intent, so the write is FORCED (the switch owns the key
     * from this moment — ownership recorded in compaction_auto_app) and
     * the switch state is persisted first so a crash between the two
     * writes converges on the clicked state at the next boot.
     */
    public static boolean setCompactionAuto(Context c, boolean auto)
            throws IOException {
        c.getSharedPreferences("oc", Context.MODE_PRIVATE).edit()
                .putBoolean("compact_auto", auto).apply();
        Map<String, Object> cfg = readConfig(c);
        if (!CompactionPolicy.mergeAuto(cfg, auto, -1, true)) return false;
        writeConfig(c, cfg);
        c.getSharedPreferences("oc", Context.MODE_PRIVATE).edit()
                .putInt("compaction_auto_app", auto ? 1 : 0).apply();
        return true;
    }

    /** Stored default model as {providerID, modelID}, or null. */
    public static String[] defaultModel(Context c) {
        String s = Json.str(readConfig(c), "model");
        if (s == null) return null;
        int i = s.indexOf('/');
        if (i <= 0 || i == s.length() - 1) return null;
        return new String[]{s.substring(0, i), s.substring(i + 1)};
    }

    /** P42: the user's context-window cap (the Σ-popover slider) — the
     *  current override in opencode.json, 0 when none. */
    public static long contextLimit(Context c, String pid, String mid) {
        try {
            return ContextPolicy.readContextLimit(readConfig(c), pid, mid);
        } catch (Exception e) {
            return 0;
        }
    }

    /** P42: write (or clear, n ≤ 0) the override. The server reports it
     *  through /config/providers on its next boot, so the Σ meter's
     *  denominator and the compaction trigger follow automatically. */
    public static boolean setContextLimit(Context c, String pid, String mid,
                                          long n) throws IOException {
        Map<String, Object> cfg = readConfig(c);
        if (!ContextPolicy.mergeContextLimit(cfg, pid, mid, n)) return false;
        writeConfig(c, cfg);
        return true;
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
