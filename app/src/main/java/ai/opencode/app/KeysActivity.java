package ai.opencode.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P7 API keys — lean, no fluff: pick a provider, paste a key, done.
 * Writes the verified auth.json layout + optional OpenAI-compatible
 * custom endpoints into opencode.json. Takes effect on the next request
 * (no server restart needed; a restart button is here anyway).
 */
public class KeysActivity extends Activity {

    private LinearLayout list;
    private LinearLayout githubSlot;

    @Override
    protected void onResume() {
        super.onResume();
        Theme.syncIfNeeded(this);        // P32: a theme switch elsewhere re-skins here too
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.cr(this, R.color.bg));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(24));
        scroll.addView(root);
        setContentView(scroll);
        Theme.window(this);              // P31: palette owns the window + dialogs

        root.addView(header());
        TextView note = text(13, R.color.text_secondary, false);
        note.setText("Paste an API key for any provider you use. Keys are "
                + "stored in the app's private auth.json — the sandbox "
                + "reloads them by itself the moment a key is saved, "
                + "changed or imported, so the picker and your next "
                + "message see them immediately.\n\n"
                + "P16, verified against the catalog: OpenCode ZEN and "
                + "OpenCode GO are SEPARATE providers with SEPARATE keys — "
                + "both issued at console.opencode.ai, but they are NOT "
                + "interchangeable. The ZEN key runs the zen models (its FREE "
                + "ones — grok-code, mimo, big-pickle… — run with no key at "
                + "all); the GO key runs the Go models (kimi, qwen, deepseek…). "
                + "A model asking for the opencode-go key needs the GO row "
                + "below — that is why your Zen key alone left them dead. If a "
                + "model still errors 401/402, that specific model is paid "
                + "outside your plan. No other key yet? OpenRouter and Groq "
                + "have free tiers.");
        int p2 = dp(10);
        note.setPadding(0, p2, 0, p2);
        root.addView(note);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        // P12: the GitHub key the AGENT uses — separate from model providers.
        // Stored in prefs, exported as GH_TOKEN into Debian/sandbox shells.
        root.addView(section("Agent GitHub access"));
        githubSlot = new LinearLayout(this);
        githubSlot.setOrientation(LinearLayout.VERTICAL);
        githubSlot.addView(githubRow());
        root.addView(githubSlot);

        root.addView(section("Custom endpoint (OpenAI-compatible)"));
        LinearLayout custom = row("＋ Add custom endpoint",
                "any baseURL that speaks the OpenAI API (OpenRouter, "
                        + "Together, Ollama, LM Studio, vLLM…)");
        custom.setOnClickListener(v -> customDialog());
        root.addView(custom);

        root.addView(section("Advanced"));
        LinearLayout imp = row("Import auth.json", "merge a file exported from a desktop opencode install");
        imp.setOnClickListener(v -> importAuth());
        root.addView(imp);
        LinearLayout restart = row("Restart server", "apply config changes the hard way (~5 s)");
        restart.setOnClickListener(v -> {
            ServerService.restart(this);
            Toast.makeText(this, "restarting server…", Toast.LENGTH_SHORT).show();
        });
        root.addView(restart);

        refresh();
    }

    private void refresh() {
        Map<String, Object> auth = AuthStore.readAuth(this);
        list.removeAllViews();
        list.addView(section("Providers"));
        for (String[] k : AuthStore.KNOWN) addProviderRow(k[0], k[1], auth);
        // providers that exist in auth.json or opencode.json but aren't in KNOWN
        Map<String, String> extra = new LinkedHashMap<>();
        for (String pid : auth.keySet()) {
            if (!known(pid)) extra.put(pid, pid);
        }
        Map<String, Object> cfg = AuthStore.readConfig(this);
        Map<String, Object> provs = Json.map(cfg, "provider");
        if (provs != null) {
            for (String pid : provs.keySet()) if (!known(pid) && !extra.containsKey(pid))
                extra.put(pid, pid);
        }
        for (Map.Entry<String, String> e : extra.entrySet())
            addProviderRow(e.getKey(), e.getValue(), auth);
    }

    private boolean known(String id) {
        for (String[] k : AuthStore.KNOWN) if (k[0].equals(id)) return true;
        return false;
    }

    private void addProviderRow(String id, String name, Map<String, Object> auth) {
        String masked = null;
        Object entry = auth.get(id);
        if (entry instanceof Map) {
            Object key = ((Map<?, ?>) entry).get("key");
            if (key instanceof String && ((String) key).length() > 8) {
                String s = (String) key;
                masked = s.substring(0, 5) + "…" + s.substring(s.length() - 4);
            }
        }
        String status = masked == null ? "no key" : "saved · " + masked;
        LinearLayout t = row(name, id + "  ·  " + status);
        ((TextView) t.getChildAt(0)).setTextColor(Theme.cr(this, masked == null
                ? R.color.text_primary : R.color.ok));
        Theme.press(t);
        t.setOnClickListener(v -> keyDialog(id, name));
        list.addView(t);
    }

    private void keyDialog(String id, String name) {
        final EditText input = Sheet.input(this, "API key (" + hintFor(id) + ")",
                null, true);
        input.setTextSize(14);
        input.setTypeface(Typeface.MONOSPACE);
        Sheet sh = Sheet.show(this, name + " (" + id + ")");
        if (!sh.showing()) return;
        sh.add(input);
        sh.add(Sheet.hint(this,
                "Leave empty and press SAVE to remove the stored key."));
        sh.pill("Save", Sheet.PRIMARY, () -> {
            // P16: a FIRST-TIME key changes what /config/providers
            // can offer (opencode-go is invisible to the server until
            // its key exists) — restart so the provider goes live
            // immediately instead of waiting for a manual one.
            // P33: the SAME is true for a CHANGED key — the field
            // report was exact: "the app thinks i have no api key
            // even tho it says i have it in api settings". The old
            // server process kept the OLD key in memory (or knew of
            // no key at all) and answered every send with key
            // errors, while this screen happily showed the new one
            // saved. Whenever the stored value actually changes,
            // the sandbox now re-loads it automatically.
            String previous = storedKey(id);
            String entered = input.getText().toString().trim();
            boolean first = previous == null;
            boolean changed = previous == null
                    ? !entered.isEmpty()
                    : !previous.equals(entered);
            try {
                AuthStore.setApiKey(this, id, input.getText().toString());
                if (first || changed) {
                    ServerService.restart(this);
                    Toast.makeText(this, entered.isEmpty()
                            ? "key removed — restarting the sandbox"
                            : "saved — applying to the sandbox…",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "saved", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "save failed: " + e, Toast.LENGTH_LONG).show();
            }
            refresh();
        });
        sh.pill("Cancel", Sheet.QUIET, null);
        sh.focus(input);
    }

    /** The key string currently stored for a provider (null = none). */
    private String storedKey(String id) {
        Object entry = AuthStore.readAuth(this).get(id);
        if (entry instanceof Map) {
            Object k = ((Map<?, ?>) entry).get("key");
            if (k instanceof String && !((String) k).isEmpty()) return (String) k;
        }
        return null;
    }

    private String hintFor(String id) {
        for (String[] k : AuthStore.KNOWN) if (k[0].equals(id)) return k[2];
        return "sk-…";
    }

    // ------------------------------------------------------- agent github

    /**
     * P12: the token the AGENT itself uses for git push / releases against
     * github.com/turanmertkaraca-bit/opencode-android. Deliberately NOT a
     * model-provider row: it is exported as GH_TOKEN inside the sandbox
     * (Debian shells) instead of auth.json. The guidance steers toward a
     * FINE-GRAINED token scoped to this one repo — a full-permission PAT
     * in the sandbox would let any tool the agent runs read it.
     */
    private View githubRow() {
        String cur = getSharedPreferences("oc", MODE_PRIVATE)
                .getString("gh_token", null);
        String masked = null;
        if (cur != null && cur.length() > 10)
            masked = cur.substring(0, 4) + "…" + cur.substring(cur.length() - 4);
        LinearLayout t = row("GitHub token (for the agent)",
                masked == null
                        ? "not set — lets the AI clone, commit and push this repo"
                        : "saved · " + masked);
        ((TextView) t.getChildAt(0)).setTextColor(Theme.cr(this,
                masked == null ? R.color.text_primary : R.color.ok));
        Theme.press(t);
        t.setOnClickListener(v -> githubDialog());
        return t;
    }

    private void githubDialog() {
        final EditText input = Sheet.input(this,
                "github_pat_… / ghp_…  (leave empty to remove)", null, true);
        input.setTextSize(14);
        input.setTypeface(Typeface.MONOSPACE);
        Sheet sh = Sheet.show(this, "Agent GitHub token");
        if (!sh.showing()) return;
        sh.add(input);
        sh.add(Sheet.hint(this,
                "Create a FINE-GRAINED token limited to\n"
                + "turanmertkaraca-bit/opencode-android →\n"
                + "Contents: Read and write. Nothing else.\n\n"
                + "github.com/settings/personal-access-tokens/new\n\n"
                + "It becomes GH_TOKEN in the sandbox so the AI can analyze "
                + "and push new versions. In Debian it can `apt install git` "
                + "and commit right away. A scoped token keeps a runaway "
                + "tool from touching anything else."));
        sh.pill("Save", Sheet.PRIMARY, () -> {
            String v = input.getText().toString().trim();
            getSharedPreferences("oc", MODE_PRIVATE).edit()
                    .putString("gh_token", v.isEmpty() ? null : v)
                    .apply();
            Toast.makeText(this, v.isEmpty() ? "token removed"
                    : "saved — active in new sandbox shells", Toast.LENGTH_SHORT).show();
            // P33: the row updates IN PLACE — recreate() here was a
            // whole-activity teardown for one line of masked text,
            // the same "feels bad" wait the theme switch had.
            githubSlot.removeAllViews();
            githubSlot.addView(githubRow());
            refresh();
        });
        sh.pill("Cancel", Sheet.QUIET, null);
        sh.focus(input);
    }

    private void customDialog() {
        // P34: the custom-provider form on the Sheet — five labeled input
        // wells, the same stack the rest of the app speaks.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int gap = Theme.dp(this, 8);
        final EditText eId = field(box, "id (lowercase, e.g. myprovider)");
        final EditText eName = field(box, "display name (e.g. My Provider)");
        final EditText eUrl = field(box, "base URL (e.g. https://openrouter.ai/api/v1)");
        final EditText eKey = field(box, "API key (optional here)");
        final EditText eModel = field(box, "default model id (optional, e.g. anthropic/claude-3.5-sonnet)");
        for (int i = 1; i < box.getChildCount(); i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                    box.getChildAt(i).getLayoutParams();
            lp.topMargin = gap;
            box.getChildAt(i).setLayoutParams(lp);
        }
        Sheet sh = Sheet.show(this, "Custom provider");
        if (!sh.showing()) return;
        sh.scroll(box, 0.6f);
        sh.pill("Add", Sheet.PRIMARY, () -> {
            try {
                AuthStore.addCustomProvider(this,
                        eId.getText().toString(),
                        eName.getText().toString(),
                        eUrl.getText().toString(),
                        eKey.getText().toString(),
                        eModel.getText().toString());
                // P33: the sandbox picks new providers up by itself —
                // "restart server to load it" was another step the
                // user had to know about (and another "why is my
                // provider not there" dead end when skipped).
                ServerService.restart(this);
                Toast.makeText(this,
                        "provider added — applying to the sandbox…",
                        Toast.LENGTH_LONG).show();
                refresh();
            } catch (Exception e) {
                Toast.makeText(this, "failed: " + e, Toast.LENGTH_LONG).show();
            }
        });
        sh.pill("Cancel", Sheet.QUIET, null);
        sh.focus(eId);
    }

    private EditText field(LinearLayout box, String hint) {
        EditText e = Sheet.input(this, hint, null, true);
        box.addView(e);
        return e;
    }

    private void importAuth() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, 7);
        } catch (Exception e) {
            Toast.makeText(this, "no file picker: " + e, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 7 || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            File tmp = new File(getCacheDir(), "import-auth.json");
            Binaries.copyFromUri(this, uri, tmp);
            AuthStore.importAuth(this, tmp);
            tmp.delete();
            // P33: an imported auth.json may carry entirely different
            // credentials — the running server must re-read it, by itself.
            ServerService.restart(this);
            Toast.makeText(this, "auth.json merged — applying to the sandbox…",
                    Toast.LENGTH_LONG).show();
            refresh();
        } catch (Exception e) {
            Toast.makeText(this, "import failed: " + e, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ ui bits

    private LinearLayout header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(20, R.color.accent_light, false);
        back.setText("‹");
        back.setPadding(0, 0, dp(18), 0);
        back.setOnClickListener(v -> finish());
        h.addView(back);
        TextView title = text(18, R.color.text_primary, true);
        title.setText("API keys");
        h.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return h;
    }

    private TextView section(String s) {
        TextView t = text(11, R.color.text_secondary, true);
        t.setText(s.toUpperCase());
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private LinearLayout row(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        box.setPadding(p, dp(10), p, dp(10));
        box.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        box.setLayoutParams(lp);
        TextView t1 = text(15, R.color.text_primary, false);
        t1.setText(title);
        TextView t2 = text(12, R.color.text_secondary, false);
        t2.setText(sub);
        box.addView(t1);
        box.addView(t2);
        return box;
    }

    private TextView text(int sizeSp, int colorRes, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextSize(sizeSp);
        tv.setTextColor(Theme.cr(this, colorRes));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
