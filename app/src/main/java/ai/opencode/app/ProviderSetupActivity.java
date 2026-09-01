package ai.opencode.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P6: connect provider APIs INSIDE the app.
 *
 * Known providers get a key field each (stored to auth.json as
 * {"<id>":{"type":"api","key":...}}). A custom OpenAI-compatible provider
 * form writes opencode.json (npm @ai-sdk/openai-compatible + baseURL +
 * apiKey — shape verified in the shipped binary). An auth.json import is
 * kept for people migrating from desktop.
 *
 * Saving restarts the server so the new credentials take effect (cold start
 * ≈ 5 s).
 */
public class ProviderSetupActivity extends Activity {

    private static final int REQ_AUTH = 9;

    private LinearLayout list;
    private final Map<String, EditText> keyFields = new LinkedHashMap<>();
    private EditText etCustomName, etCustomUrl, etCustomKey, etCustomModel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(root());
        list = findViewById(R.id.psuList);

        add(head("Connect your APIs", 22, true, R.color.accent_light));
        add(head("Paste an API key for any provider you use. Keys are stored in"
                + " the app's private opencode home — nothing leaves the device"
                + " except calls to the provider itself.", 13, false, R.color.text_secondary));

        for (String[] kp : AuthStore.KNOWN) {
            list.addView(providerRow(kp[0], kp[1], kp[2]), pad(10));
        }

        // custom provider section
        LinearLayout custom = card();
        custom.addView(head("Custom OpenAI-compatible provider", 15, true, R.color.text_primary));
        custom.addView(head("For self-hosted or unlisted endpoints (vLLM, Ollama"
                + " bridges, LiteLLM, Azure gateways…).", 12, false, R.color.text_secondary));
        etCustomName  = field("Name (e.g. My Gateway)", false);
        etCustomUrl   = field("Base URL (e.g. https://host/v1)", false);
        etCustomKey   = field("API key (optional)", true);
        etCustomModel = field("Default model id (optional)", false);
        custom.addView(etCustomName);
        custom.addView(etCustomUrl);
        custom.addView(etCustomKey);
        custom.addView(etCustomModel);
        list.addView(custom, pad(10));

        Button imp = btn("Import auth.json instead", R.drawable.bg_btn_secondary, R.color.text_primary);
        imp.setOnClickListener(v -> pick());
        list.addView(imp, pad(10));

        Button save = btn("Save & restart server", R.drawable.bg_btn_primary, R.color.on_accent);
        save.setOnClickListener(v -> saveAll());
        list.addView(save, pad(16));

        Button done = btn("Back", R.drawable.bg_btn_secondary, R.color.text_primary);
        done.setOnClickListener(v -> finish());
        list.addView(done, pad(6));
    }

    // ----------------------------------------------------------------- save

    private void saveAll() {
        int saved = 0;
        StringBuilder errs = new StringBuilder();
        for (Map.Entry<String, EditText> e : keyFields.entrySet()) {
            String key = e.getValue().getText().toString().trim();
            if (key.isEmpty()) continue;
            try {
                AuthStore.setApiKey(this, e.getKey(), key);
                saved++;
            } catch (Exception ex) {
                errs.append(e.getKey()).append(": ").append(ex.getMessage()).append('\n');
            }
        }
        // custom provider (only when the URL is filled in)
        String url = etCustomUrl.getText().toString().trim();
        if (!url.isEmpty()) {
            try {
                AuthStore.addCustomProvider(this,
                        etCustomName.getText().toString().trim().isEmpty()
                                ? "custom" : etCustomName.getText().toString().trim(),
                        etCustomName.getText().toString().trim(),
                        url,
                        etCustomKey.getText().toString().trim(),
                        etCustomModel.getText().toString().trim());
                saved++;
            } catch (Exception ex) {
                errs.append("custom: ").append(ex.getMessage()).append('\n');
            }
        }
        if (saved == 0) {
            toast(errs.length() == 0 ? "Nothing to save — paste at least one key"
                    : "Save failed:\n" + errs);
            return;
        }
        if (errs.length() > 0) toast("Some entries failed:\n" + errs);
        toast("Saved " + saved + " change(s) — restarting server…");
        ServerService.restart(this);
        finish();
    }

    // ----------------------------------------------------------------- import

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try {
            startActivityForResult(i, REQ_AUTH);
        } catch (Exception e) {
            toast("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_AUTH || res != RESULT_OK || data == null
                || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            File tmp = new File(getCacheDir(), "auth.import.json");
            Binaries.copyFromUri(this, uri, tmp);
            AuthStore.importAuth(this, tmp);
            tmp.delete();
            toast("auth.json imported — restarting server…");
            ServerService.restart(this);
            finish();
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- ui helpers

    private View root() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(getColor2(R.color.bg));
        LinearLayout root = new LinearLayout(this);
        root.setId(R.id.psuRoot);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        sc.addView(root);
        list = new LinearLayout(this);
        list.setId(R.id.psuList);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        return sc;
    }

    private void add(View v) { list.addView(v); }

    private LinearLayout providerRow(String id, String name, String hint) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = head(name, 15, true, R.color.text_primary);
        n.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(n);
        boolean saved = AuthStore.hasKey(this, id);
        row.addView(head(saved ? "✓ saved" : "", 12, false, 0xFF3FB950));
        card.addView(row);

        card.addView(head("id: " + id + " · key like " + hint, 11, false, R.color.text_secondary));

        EditText et = field(saved ? "saved — paste a new key to replace" : "paste key…", true);
        keyFields.put(id, et);
        card.addView(et);
        return card;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        return c;
    }

    private TextView head(String text, float sizeSp, boolean bold, int colorRes) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setTextColor(colorRes == 0 ? getColor2(R.color.text_primary) : getColor2(colorRes));
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(getColor2(R.color.text_primary));
        e.setHintTextColor(getColor2(R.color.text_secondary));
        e.setTextSize(14f);
        e.setMaxLines(1);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setBackground(getResources().getDrawable(R.drawable.bg_input));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private Button btn(String text, int bg, int colorRes) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setBackgroundResource(bg);
        b.setTextColor(getColor2(colorRes));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private LinearLayout.LayoutParams pad(int dpTop) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(dpTop);
        return lp;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("deprecation")
    private int getColor2(int id) { return getResources().getColor(id); }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}
