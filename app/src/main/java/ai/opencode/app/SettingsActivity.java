package ai.opencode.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * P8 — Settings, restyled: one animated hub with rounded sections,
 * staggered entrances, switch rows, and a sandbox doctor that SHOWS what
 * the agent can actually run (instead of hoping). Replaces the P4-era
 * gray-list feel ("Android 4") without adding any libraries.
 */
public class SettingsActivity extends Activity implements ServerService.Evt {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView dot, stateTxt;
    private ObjectAnimator pulse;
    private int animDelay;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        ServerService.subscribe(this);
        refreshState(ServerService.getState(), null);
    }

    @Override
    protected void onDestroy() {
        ServerService.unsubscribe(this);
        if (pulse != null) pulse.cancel();
        super.onDestroy();
    }

    // ------------------------------------------------------------- ui

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.bg_home);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(this, 20);
        root.setPadding(pad, Theme.dp(this, 12), pad, Theme.dp(this, 40));
        scroll.addView(root);

        // header
        TextView h1 = new TextView(this);
        h1.setText("Settings");
        h1.setTextSize(26);
        h1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h1.setTextColor(Theme.TXT);
        root.addView(h1);
        TextView h2 = new TextView(this);
        h2.setText("tuned for snappiness — every toggle here is live");
        h2.setTextSize(12);
        h2.setTextColor(Theme.TXT_DIM);
        h2.setPadding(0, 0, 0, Theme.dp(this, 6));
        root.addView(h2);

        // ---- server
        root.addView(Theme.sectionLabel(this, "server"));
        LinearLayout statusCard = section();
        LinearLayout srow = row(statusCard);
        dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(12);
        dot.setTextColor(Theme.TXT_DIM);
        dot.setPadding(0, 0, Theme.dp(this, 8), 0);
        srow.addView(dot);
        stateTxt = new TextView(this);
        stateTxt.setTextSize(13);
        stateTxt.setTextColor(Theme.TXT);
        srow.addView(stateTxt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusCard.addView(srow);
        statusCard.addView(buttonRow("Restart server", v -> {
            ServerService.restart(this);
            Toast.makeText(this, "restarting…", Toast.LENGTH_SHORT).show();
        }));
        statusCard.addView(switchRow("DNS bridge (VPN / exotic DNS only)",
                "dns_bridge", "off by default — the bundled binary resolves DNS natively"));
        root.addView(statusCard);

        // ---- model & keys
        root.addView(Theme.sectionLabel(this, "model & keys"));
        LinearLayout mk = section();
        String[] sel = Models.selected(this);
        mk.addView(rowLink("Default model",
                sel == null ? "auto (server default)" : sel[0] + " / " + sel[1],
                v -> pickModel()));
        mk.addView(rowLink("API keys", "paste keys, import auth.json, custom endpoints",
                v -> startActivity(new Intent(this, KeysActivity.class))));
        root.addView(mk);

        // ---- projects
        root.addView(Theme.sectionLabel(this, "projects"));
        LinearLayout pr = section();
        pr.addView(rowLink("Project deck", "cards · each project opens its own sandbox",
                v -> startActivity(new Intent(this, HomeActivity.class))));
        root.addView(pr);

        // ---- sandbox doctor
        root.addView(Theme.sectionLabel(this, "sandbox"));
        LinearLayout sb = section();
        sb.addView(rowLink("Sandbox doctor", "checking what the agent can run…",
                v -> runDoctor()));
        sb.addView(rowLink("Server logs & shell console", "live log tail + native shell",
                v -> startActivity(new Intent(this, DiagnosticsActivity.class))));
        sb.addView(rowLink("Import arm64 tools", "bring your own git / rg / … static binaries",
                v -> startActivity(new Intent(this, DiagnosticsActivity.class))));
        root.addView(sb);

        // ---- interface
        root.addView(Theme.sectionLabel(this, "interface"));
        LinearLayout it = section();
        it.addView(switchRow("Animations", "motion",
                "turn off to remove all movement (also honors system \"remove animations\")"));
        root.addView(it);

        // ---- about
        root.addView(Theme.sectionLabel(this, "about"));
        LinearLayout ab = section();
        ab.addView(rowInfo("Version", "0.8.0-p8 · native agent chat"));
        ab.addView(rowLink("Source & releases", "github.com/turanmertkaraca-bit/opencode-android",
                v -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("repo",
                            "https://github.com/turanmertkaraca-bit/opencode-android"));
                    Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show();
                }));
        root.addView(ab);

        // staggered entrance
        for (int i = 0; i < root.getChildCount(); i++) {
            Theme.enter(root.getChildAt(i), i * 45L);
        }
        return scroll;
    }

    private LinearLayout section() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackground(Theme.ripple(this, Theme.panel(this)));
        return s;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Theme.dp(this, 16), Theme.dp(this, 12), Theme.dp(this, 16), Theme.dp(this, 12));
        parent.addView(r);
        return r;
    }

    private View rowLink(String title, String sub, View.OnClickListener oc) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setClickable(true);
        r.setBackground(Theme.ripple(this, null));
        r.setPadding(Theme.dp(this, 16), Theme.dp(this, 11), Theme.dp(this, 16), Theme.dp(this, 11));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Theme.TXT);
        r.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11);
            s.setTextColor(Theme.TXT_DIM);
            s.setPadding(0, 2, 0, 0);
            r.addView(s);
        }
        Theme.press(r);
        r.setOnClickListener(oc);
        return r;
    }

    private View rowInfo(String title, String sub) {
        return rowLink(title, sub, null);
    }

    private View buttonRow(String label, View.OnClickListener oc) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(Theme.dp(this, 16), 0, Theme.dp(this, 16), Theme.dp(this, 12));
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Theme.ACCENT_LT);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_chip);
        b.setPadding(Theme.dp(this, 16), Theme.dp(this, 9), Theme.dp(this, 16), Theme.dp(this, 9));
        Theme.press(b);
        b.setOnClickListener(oc);
        r.addView(b);
        return r;
    }

    private View switchRow(String label, String keyOrNull, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Theme.dp(this, 16), Theme.dp(this, 8), Theme.dp(this, 12), Theme.dp(this, 8));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(Theme.TXT);
        col.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11);
            s.setTextColor(Theme.TXT_DIM);
            s.setPadding(0, 2, Theme.dp(this, 8), 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (keyOrNull == null) return r;
        Switch sw = new Switch(this);
        boolean on = getSharedPreferences("oc", MODE_PRIVATE).getBoolean(keyOrNull, false);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b2, v) -> {
            getSharedPreferences("oc", MODE_PRIVATE).edit().putBoolean(keyOrNull, v).apply();
            if ("dns_bridge".equals(keyOrNull) && v) {
                Toast.makeText(this, "bridge on — restart server to apply",
                        Toast.LENGTH_LONG).show();
            }
        });
        r.addView(sw);
        return r;
    }

    // ------------------------------------------------------------- model

    private void pickModel() {
        if (!ServerService.healthy()) {
            Toast.makeText(this, "server not ready — start a chat first", Toast.LENGTH_SHORT).show();
            return;
        }
        ui.post(() -> Toast.makeText(this, "loading models…", Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            List<Models.Item> flat = Models.flatten(Models.fetch(this));
            List<String> lines = new ArrayList<>();
            for (Models.Item it : flat) lines.add(it.provider + " / " + it.id);
            final List<String> fLines = lines;
            final List<Models.Item> fFlat = flat;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fLines.isEmpty()) {
                    Toast.makeText(this, "no models — check API keys", Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Default model")
                        .setItems(fLines.toArray(new String[0]), (d, w) -> {
                            Models.Item it = fFlat.get(w);
                            Models.save(this, it.provider, it.id);
                            try { AuthStore.setDefaultModel(this, it.provider, it.id); }
                            catch (Exception ignored) {}
                            Toast.makeText(this, "model → " + it.provider + "/" + it.id,
                                    Toast.LENGTH_SHORT).show();
                            recreate();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }, "oc-models").start();
    }

    // ------------------------------------------------------------- doctor

    private void runDoctor() {
        TextView title = new TextView(this);
        title.setText("checking…");
        title.setTextSize(13);
        title.setTextColor(Theme.TXT_DIM);
        title.setPadding(Theme.dp(this, 20), Theme.dp(this, 12), Theme.dp(this, 20), Theme.dp(this, 12));
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Sandbox doctor")
                .setView(title)
                .setPositiveButton("Done", null)
                .show();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            String ver = Binaries.probeVersion(this, Binaries.binaryFile(this));
            sb.append("opencode binary: ").append(ver == null ? "not ready" : ver).append('\n');
            String[][] checks = {
                    {"busybox", "busybox"},
                    {"bash (shim)", "bash"},
                    {"git (shim)", "git"},
                    {"python3", "python3"},
                    {"node", "node"},
                    {"gcc", "gcc"},
                    {"tar/gzip (busybox applets)", "tar"},
            };
            for (String[] c : checks) {
                sb.append(c[0]).append(": ").append(which(c[1]) ? "available" : "missing").append('\n');
            }
            sb.append('\n').append("PATH = files/bin → files/shims → /system/bin\n")
              .append("agent shells run inside the app's exec-allowed home,\n")
              .append("cwd = the open project's folder (per-project sandbox).\n")
              .append("Add real tools: Diagnostics → Import arm64 tools.");
            String out = sb.toString();
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                TextView tv = new TextView(this);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(12);
                tv.setTextColor(Theme.TXT);
                tv.setPadding(Theme.dp(this, 20), Theme.dp(this, 12), Theme.dp(this, 20), Theme.dp(this, 12));
                tv.setText(out);
                dlg.setView(tv);
            });
        }, "oc-doctor").start();
    }

    private boolean which(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "command -v " + cmd);
            pb.redirectErrorStream(true);
            Binaries.applyEnv(this, pb);
            Process p = pb.start();
            String o = Api.readAll(p.getInputStream());
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
            return p.exitValue() == 0 && o.trim().length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------- state

    @Override
    public void on(int newState, String detail) {
        ui.post(() -> refreshState(newState, detail));
    }

    private void refreshState(int st, String detail) {
        if (dot == null || stateTxt == null) return;
        int color;
        String s;
        switch (st) {
            case ServerService.ST_HEALTHY:
                color = Theme.OK;
                File d = ServerService.servingDir();
                s = "running" + (d != null ? " · sandbox: " + d.getName() : "");
                break;
            case ServerService.ST_STARTING: color = Theme.WARN; s = "starting…"; break;
            case ServerService.ST_EXITED: color = Theme.ERR; s = "exited"; break;
            case ServerService.ST_STOPPED: color = Theme.ERR; s = "stopped"; break;
            default: color = Theme.TXT_DIM; s = "idle"; break;
        }
        dot.setTextColor(color);
        stateTxt.setText(s);
        if (pulse != null) { pulse.cancel(); pulse = null; }
        dot.setAlpha(1f);
        if (st == ServerService.ST_STARTING && Theme.motionOn(this)) pulse = Theme.pulse(dot);
    }
}
