package ai.opencode.app;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P9 — Settings, redesigned from zero. The P8 version was still a plain
 * list ("prototype", per the user). Now:
 *
 *   • HERO server card — gradient, live status dot, sandbox root, restart
 *   • section cards with icon discs, chevrons and press feedback
 *   • custom animated switch (framework-only track + knob, no old Switch)
 *   • SANDBOX section fronts the P9 toolkit: pkg status, install/repair,
 *     rehash, doctor, logs, tool import
 *   • staggered entrances, springy toggles, global motion switch honored
 */
public class SettingsActivity extends Activity implements ServerService.Evt {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView dot, stateTxt, pkgStatus, debStatus, debTitle;
    private ObjectAnimator pulse;
    private LinearLayout heroCard;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        ServerService.subscribe(this);
        refreshState(ServerService.getState(), null);
        refreshPkg();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPkg();
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
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(this, 18);
        root.setPadding(pad, Theme.dp(this, 14), pad, Theme.dp(this, 48));
        scroll.addView(root);

        // header
        TextView h1 = new TextView(this);
        h1.setText("Settings");
        h1.setTextSize(27);
        h1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h1.setTextColor(Theme.TXT);
        root.addView(h1);
        TextView h2 = new TextView(this);
        h2.setText("server · agent · sandbox — everything is live");
        h2.setTextSize(12);
        h2.setTextColor(Theme.TXT_DIM);
        h2.setPadding(0, Theme.dp(this, 2), 0, Theme.dp(this, 4));
        root.addView(h2);

        // ---- HERO: server card
        root.addView(heroCard());

        // ---- agent
        root.addView(Theme.sectionLabel(this, "agent"));
        LinearLayout mk = section();
        mk.addView(rowLink("Default model",
                Models.selected(this) == null ? "auto (server default)"
                        : Models.selected(this)[0] + " / " + Models.selected(this)[1],
                "◆", v -> pickModel()));
        mk.addView(divider());
        mk.addView(rowLink("API keys", "paste keys · import auth.json · endpoints",
                "⚿", v -> startActivity(new Intent(this, KeysActivity.class))));
        mk.addView(divider());
        // P14: unattended mode — the agent answers its own approval requests
        mk.addView(switchRow("Unattended mode (auto-allow)", "auto_allow",
                "agent approves its own tool calls — leave it running hands-free"));
        mk.addView(divider());
        mk.addView(rowLink("Agent GitHub access",
                getSharedPreferences("oc", MODE_PRIVATE).getString("gh_token", null) != null
                        ? "token saved — the AI can clone · commit · push"
                        : "not set — give the AI a scoped token to push for you",
                "⑂", v -> startActivity(new Intent(this, KeysActivity.class))));
        root.addView(mk);

        // ---- sandbox
        root.addView(Theme.sectionLabel(this, "sandbox"));
        LinearLayout sb = section();
        sb.addView(pkgRow());
        sb.addView(divider());
        sb.addView(rowLink("Install / repair toolkit",
                "re-extract the Alpine layer (~4 MB) and re-link commands",
                "⟳", v -> installToolkit()));
        sb.addView(divider());
        sb.addView(rowLink("Sandbox doctor", "what can the agent actually run?",
                "✚", v -> runDoctor()));
        sb.addView(divider());
        // P15: the visual project file manager
        sb.addView(rowLink("Project files →",
                "browse · preview · rename · delete — inside this project",
                "▤", v -> startActivity(new Intent(this, FilesActivity.class))));
        sb.addView(divider());
        sb.addView(rowLink("Logs & shell console", "live server log + native shell",
                "›_", v -> startActivity(new Intent(this, DiagnosticsActivity.class))));
        sb.addView(divider());
        sb.addView(rowLink("Import arm64 tools", "bring your own static binaries",
                "⇩", v -> startActivity(new Intent(this, DiagnosticsActivity.class))));
        root.addView(sb);

        // ---- environment (P12: Debian 12 + apt via proot)
        root.addView(Theme.sectionLabel(this, "environment"));
        LinearLayout env = section();
        env.addView(debianRow());
        env.addView(divider());
        // P15: the agent's own "environment detection" ask — one tap shows
        // kernel/user/os/tools/storage + whether the dirs proot needs exist.
        env.addView(rowLink("Environment check",
                "kernel · user · os · tools · storage · project bind",
                "ⓘ", v -> runEnvCheck()));
        env.addView(divider());
        env.addView(rowLink("Why two environments?",
                "Lite (Alpine) always works · Debian adds real apt — the\n"
                        + "installer probes proot and falls back automatically",
                "ⓘ", v -> envExplain()));
        root.addView(env);

        // ---- keep alive (P12: background processing setup)
        root.addView(Theme.sectionLabel(this, "keep alive"));
        LinearLayout ka = section();
        ka.addView(batteryRow());
        ka.addView(divider());
        ka.addView(switchRow("Start on boot", "boot_start",
                "launch the sandbox server after a reboot"));
        ka.addView(divider());
        // P17: the wake lock only exists while the agent works — the fix
        // for "my phone feels hot when it's running while doing nothing".
        ka.addView(switchRow("Cool idle", "eco_idle",
                "wake lock ONLY while the agent works — phone stays cool when idle (off = old always-on behavior)"));
        ka.addView(divider());
        ka.addView(rowLink("Notifications",
                "the persistent “OpenCode server” notice keeps the agent alive",
                "◍", v -> openNotifSettings()));
        ka.addView(divider());
        ka.addView(rowLink("Samsung battery setup",
                "on Galaxy: allow the app in “Never sleeping apps”",
                "◈", v -> samsungGuide()));
        ka.addView(divider());
        // P18: every auto-recovered (or given-up) server death lands in
        // files/sandbox-diag.log — exit code, last output, memory pressure.
        // One tap surfaces the ground truth that “no crash file” hid before.
        ka.addView(rowLink("Sandbox incident log",
                "why the sandbox last died · auto-restarts · memory",
                "▤", v -> showIncidentLog()));
        root.addView(ka);

        // ---- projects
        root.addView(Theme.sectionLabel(this, "projects"));
        LinearLayout pr = section();
        pr.addView(rowLink("Project deck", "credit-card launcher · one sandbox per project",
                "▦", v -> startActivity(new Intent(this, HomeActivity.class))));
        root.addView(pr);

        // ---- interface
        root.addView(Theme.sectionLabel(this, "interface"));
        LinearLayout it = section();
        it.addView(switchRow("Animations", "motion",
                "all movement app-wide (also honors system \"remove animations\")"));
        root.addView(it);

        // ---- about
        root.addView(Theme.sectionLabel(this, "about"));
        LinearLayout ab = section();
        ab.addView(rowLink("Version", "0.18.0-p18 · unstoppable sandbox (auto-restart + incident log) + graceful send timeouts + Σ pill that explains itself", "◆", v -> {}));
        ab.addView(divider());
        ab.addView(rowLink("Source & releases",
                "github.com/turanmertkaraca-bit/opencode-android", "⑂", v -> {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
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

    // ------------------------------------------------------------ hero

    private View heroCard() {
        heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setBackground(Theme.ripple(this, Theme.cardBg(0, Theme.dp(this, 22))));
        int p = Theme.dp(this, 20);
        heroCard.setPadding(p, p, p, p);

        TextView brand = new TextView(this);
        brand.setText("OPENCODE");
        brand.setTextSize(10);
        brand.setLetterSpacing(0.2f);
        brand.setTextColor(0xB3FFFFFF);
        heroCard.addView(brand);

        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setPadding(0, Theme.dp(this, 10), 0, 0);
        dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(13);
        dot.setTextColor(Theme.TXT_DIM);
        st.addView(dot);
        stateTxt = new TextView(this);
        stateTxt.setTextSize(14);
        stateTxt.setTypeface(Typeface.DEFAULT_BOLD);
        stateTxt.setTextColor(0xFFFFFFFF);
        stateTxt.setPadding(Theme.dp(this, 8), 0, 0, 0);
        stateTxt.setMaxLines(2);
        st.addView(stateTxt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heroCard.addView(st);

        TextView restart = new TextView(this);
        restart.setText("Restart server");
        restart.setTextSize(13);
        restart.setTypeface(Typeface.DEFAULT_BOLD);
        restart.setTextColor(0xFFFFFFFF);
        restart.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(Theme.dp(this, 14));
        bg.setStroke(1, 0x55FFFFFF);
        restart.setBackground(bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Theme.dp(this, 14);
        restart.setLayoutParams(rlp);
        restart.setPadding(Theme.dp(this, 18), Theme.dp(this, 9), Theme.dp(this, 18), Theme.dp(this, 9));
        Theme.press(restart);
        restart.setOnClickListener(v -> {
            Theme.pop(restart);
            ServerService.restart(this);
            Toast.makeText(this, "restarting…", Toast.LENGTH_SHORT).show();
        });
        heroCard.addView(restart);
        return heroCard;
    }

    // ------------------------------------------------------------ sandbox

    private View pkgRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(Theme.dp(this, 16), Theme.dp(this, 11), Theme.dp(this, 16), Theme.dp(this, 11));
        TextView t = new TextView(this);
        t.setText("Package manager (pkg)");
        t.setTextSize(14);
        t.setTextColor(Theme.TXT);
        r.addView(t);
        pkgStatus = new TextView(this);
        pkgStatus.setTextSize(11);
        pkgStatus.setTextColor(Theme.TXT_DIM);
        pkgStatus.setPadding(0, 2, 0, 0);
        r.addView(pkgStatus);
        TextView hint = new TextView(this);
        hint.setText("the agent can run:  pkg install python3 git nodejs gcc …");
        hint.setTextSize(11);
        hint.setTextColor(Theme.ACCENT_LT);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setPadding(0, Theme.dp(this, 6), 0, 0);
        r.addView(hint);
        return r;
    }

    private void refreshPkg() {
        if (pkgStatus == null) return;
        if (Sandbox.ready(this)) {
            long sz = Sandbox.sizeOf(Sandbox.alpineDir(this));
            pkgStatus.setText("installed · " + Binaries.human(sz)
                    + " · alpine " + Sandbox.REPO_VER);
            pkgStatus.setTextColor(Theme.OK);
        } else {
            pkgStatus.setText("not installed yet — installing on first boot…");
            pkgStatus.setTextColor(Theme.WARN);
        }
    }

    private void installToolkit() {
        Toast.makeText(this, "installing toolkit…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final boolean ok = Sandbox.ensure(this, null);
            ui.post(() -> {
                refreshPkg();
                Toast.makeText(this, ok ? "toolkit ready — try pkg install python3"
                                : "install failed — see Logs & shell console",
                        Toast.LENGTH_LONG).show();
            });
        }, "oc-toolkit").start();
    }

    // ------------------------------------------------------- environment

    /** P12: the Debian 12 row — status line + install/repair action. */
    private View debianRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(Theme.dp(this, 16), Theme.dp(this, 11), Theme.dp(this, 16), Theme.dp(this, 11));
        r.setBackground(Theme.ripple(this, null));
        r.setClickable(true);
        r.setOnClickListener(v -> installDebian());

        debTitle = new TextView(this);
        debTitle.setText("Debian 12 + apt  ·  install ▸");
        debTitle.setTextSize(14);
        debTitle.setTextColor(Theme.TXT);
        r.addView(debTitle);
        debStatus = new TextView(this);
        debStatus.setTextSize(11);
        debStatus.setTextColor(Theme.TXT_DIM);
        debStatus.setPadding(0, 2, 0, 0);
        r.addView(debStatus);
        TextView hint = new TextView(this);
        hint.setText("shared rootfs — packages install ONCE, every project\nbinds only its own folder.  agent:  apt install python3 …");
        hint.setTextSize(11);
        hint.setTextColor(Theme.ACCENT_LT);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setPadding(0, Theme.dp(this, 6), 0, 0);
        r.addView(hint);
        refreshDebian();
        return r;
    }

    private void refreshDebian() {
        if (debStatus == null) return;
        debStatus.setText(Debian.status(this)
                + (Debian.extracted(this)
                        ? " · " + Binaries.human(Debian.sizeOf(this)) : ""));
        debStatus.setTextColor(Debian.active(this) ? Theme.OK
                : Debian.extracted(this) ? Theme.WARN : Theme.TXT_DIM);
        if (debTitle != null) {
            debTitle.setText(Debian.extracted(this)
                    ? "Debian 12 + apt  ·  tap to repair / re-probe ▸"
                    : "Debian 12 + apt  ·  tap to install ▸");
        }
    }

    /** Install (or repair + re-probe) the Debian layer with progress UI. */
    private void installDebian() {
        TextView prog = new TextView(this);
        prog.setTypeface(Typeface.MONOSPACE);
        prog.setTextSize(12);
        prog.setTextColor(Theme.TXT);
        prog.setPadding(Theme.dp(this, 20), Theme.dp(this, 12),
                Theme.dp(this, 20), Theme.dp(this, 12));
        prog.setText("starting…");
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Debian environment")
                .setView(prog)
                .setNegativeButton("Run in background", (d, w) ->
                        Toast.makeText(this, "installing in background…",
                                Toast.LENGTH_SHORT).show())
                .show();
        final android.app.AlertDialog fDlg = dlg;
        new Thread(() -> Debian.install(this, msg -> ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            prog.setText(msg);
        })), "oc-debian").start();
        // the thread finishes when install does; poll for completion to
        // enable the Done button state refresh without blocking the user
        new Thread(() -> {
            while (true) {
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                boolean done = Debian.extracted(this);
                ui.post(() -> {
                    refreshDebian();
                    if (done && fDlg != null && fDlg.isShowing() && !isFinishing())
                        fDlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                                .setText("Close");
                });
                if (done) return;
            }
        }, "oc-debian-watch").start();
    }

    /** P15 — the agent's own environment-detection ask, surfaced: runs the
     *  Debian env report (kernel · user · os · tools · storage · project)
     *  and also verifies the dirs proot needs actually exist. */
    private void runEnvCheck() {
        android.app.AlertDialog waiting = new android.app.AlertDialog.Builder(this)
                .setTitle("Environment")
                .setMessage("checking…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            // dir audit — the exact paths the field report called out
            java.io.File deb = Debian.dir(this);
            java.io.File tmp = new java.io.File(deb, "tmp");
            java.io.File home = Binaries.homeDir(this);
            java.io.File dl = new java.io.File("/storage/emulated/0/Download");
            String dirs = "debian dir      : " + (deb.isDirectory() ? "ok" : "MISSING")
                    + "\ndebian/tmp (PROOT_TMP_DIR): " + (tmp.isDirectory() ? "ok" : "MISSING")
                    + "\nfiles/home      : " + (home.isDirectory() ? "ok" : "MISSING")
                    + "\nDownload bind   : " + (dl.isDirectory() ? "ok" : "not visible");
            final String rep = dirs + "\n\n" + Debian.envReport(this);
            ui.post(() -> {
                try {
                    waiting.dismiss();
                } catch (Exception ignored) {}
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Environment")
                        .setMessage(rep)
                        .setPositiveButton("copy", (d, w) -> {
                            ClipboardManager cm =
                                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("env", rep));
                            Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("close", null)
                        .show();
            });
        }).start();
    }

    private void envExplain() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Two environments")
                .setMessage(
                        "LITE (always active)\n"
                      + "Static busybox + the Alpine layer. Agent shells run "
                      + "natively — `pkg install python3 git nodejs gcc …` "
                      + "installs into the shared Alpine layer.\n\n"
                      + "DEBIAN (recommended, opt-in)\n"
                      + "A real Debian 12 userland with apt through proot. "
                      + "ONE shared rootfs for every project, so packages "
                      + "install once; each session binds only its own "
                      + "project folder at the real device path.\n\n"
                      + "The installer probes proot first — if this device "
                      + "can't run it, the Lite shell stays active and "
                      + "nothing breaks.")
                .setPositiveButton("Got it", null)
                .show();
    }

    // -------------------------------------------------------- keep alive

    private View batteryRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(Theme.dp(this, 16), Theme.dp(this, 11), Theme.dp(this, 16), Theme.dp(this, 11));
        r.setBackground(Theme.ripple(this, null));
        r.setClickable(true);
        r.setOnClickListener(v -> requestBatteryExemption());
        TextView t = new TextView(this);
        t.setText("Battery optimization — off");
        t.setTextSize(14);
        t.setTextColor(Theme.TXT);
        r.addView(t);
        TextView s = new TextView(this);
        s.setText("without the exemption Android can kill the agent mid-run");
        s.setTextSize(11);
        s.setTextColor(Theme.TXT_DIM);
        s.setPadding(0, 2, 0, 0);
        r.addView(s);
        refreshBattery(r);
        return r;
    }

    private void refreshBattery(LinearLayout row) {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean exempt = pm.isIgnoringBatteryOptimizations(getPackageName());
            TextView t = (TextView) row.getChildAt(0);
            t.setText(exempt
                    ? "Battery optimization — exempt ✓"
                    : "Battery optimization — off");
            t.setTextColor(exempt ? Theme.OK : Theme.TXT);
        } catch (Exception ignored) {}
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "already exempt ✓", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "open Settings → Battery → app exceptions",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** P18: the sandbox's own black box — files/sandbox-diag.log. */
    private void showIncidentLog() {
        String body;
        try {
            java.io.File f = new java.io.File(getFilesDir(), "sandbox-diag.log");
            body = f.exists()
                    ? ai.opencode.app.Api.readAll(new java.io.FileInputStream(f))
                    : "";
        } catch (Exception e) { body = ""; }
        if (body.trim().isEmpty()) {
            body = "No incidents recorded — the sandbox has not died since "
                    + "this build was installed. \u2713";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Sandbox incident log")
                .setMessage("each line: timestamp \u00b7 event \u00b7 detail \u00b7 free memory\n\n"
                        + body)
                .setPositiveButton("Close", null)
                .show();
    }

    private void openNotifSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Exception e) {
            Toast.makeText(this, "open Settings → Apps → OpenCode → Notifications",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void samsungGuide() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Samsung keep-alive")
                .setMessage(
                        "Galaxy phones are the most aggressive at killing "
                      + "background apps. For unattended agent runs:\n\n"
                      + "1. Settings → Device care → Battery → Background "
                      + "usage limits → Never sleeping apps → Add → "
                      + "OpenCode\n"
                      + "2. Device care → Battery → More battery settings → "
                      + "disable “Put unused apps to sleep” for OpenCode\n"
                      + "3. Keep the battery-optimization exemption ON "
                      + "(row above)\n"
                      + "4. Leave the OpenCode notification in the shade — "
                      + "that IS the keep-alive signal.\n\n"
                      + "With those four, the agent keeps working with the "
                      + "screen off.")
                .setPositiveButton("Open battery settings", (d, w) -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------- model

    private void pickModel() {
        if (!ServerService.healthy()) {
            Toast.makeText(this, "server not ready — start a chat first", Toast.LENGTH_SHORT).show();
            return;
        }
        ui.post(() -> Toast.makeText(this, "loading catalog…", Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            final List<Models.Prov> provs = Models.fetch(this);
            ui.post(() -> showModelSheet(provs));
        }, "oc-models").start();
    }

    private void showModelSheet(List<Models.Prov> provs) {
        if (isFinishing() || isDestroyed()) return;
        int total = 0;
        for (Models.Prov p : provs) total += p.models.size();
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("Default model · " + total + " available");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, dp(8), p, 0);
        final EditText search = new EditText(this);
        search.setHint("search provider or model…");
        search.setTextSize(14);
        search.setSingleLine(true);
        box.addView(search);
        final android.widget.ListView lv = new android.widget.ListView(this);
        box.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(430)));
        b.setView(box);
        final android.app.AlertDialog dlg = b.create();

        final List<Object[]> items = new ArrayList<>();
        Runnable refill = () -> {
            String q = search.getText().toString().toLowerCase(Locale.US).trim();
            items.clear();
            String[] cur = Models.selected(this);
            for (Models.Prov pr : provs) {
                boolean provHit = q.isEmpty()
                        || pr.id.toLowerCase(Locale.US).contains(q)
                        || pr.name.toLowerCase(Locale.US).contains(q);
                List<Models.Mdl> shown = new ArrayList<>();
                for (Models.Mdl m : pr.models) {
                    if (q.isEmpty() || provHit
                            || m.id.toLowerCase(Locale.US).contains(q)
                            || m.name.toLowerCase(Locale.US).contains(q)) shown.add(m);
                }
                if (shown.isEmpty() && !provHit) continue;
                items.add(new Object[]{"h", pr, null});
                int cap = Math.min(shown.size(), 300);
                for (int i = 0; i < cap; i++) items.add(new Object[]{"m", pr, shown.get(i)});
                if (shown.size() > cap) items.add(new Object[]{"t", pr,
                        "… " + (shown.size() - cap) + " more (refine search)"});
            }
            lv.setAdapter(new android.widget.BaseAdapter() {
                public int getCount() { return items.size(); }
                public Object getItem(int i) { return items.get(i); }
                public long getItemId(int i) { return i; }
                public View getView(int i, View cv, ViewGroup parent) {
                    Object[] it = items.get(i);
                    LinearLayout row = new LinearLayout(SettingsActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    int pd = dp(14);
                    row.setPadding(pd, dp(8), pd, dp(8));
                    if ("h".equals(it[0])) {
                        Models.Prov pr = (Models.Prov) it[1];
                        TextView t = text(12, pr.configured ? Theme.OK : Theme.ACCENT_LT, true);
                        t.setText(pr.name + (pr.configured ? "  ✓ ready"
                                : pr.usable ? "  (no key)" : "  (add API key)"));
                        row.addView(t);
                    } else if ("m".equals(it[0])) {
                        Models.Mdl m = (Models.Mdl) it[2];
                        Models.Prov pr = (Models.Prov) it[1];
                        boolean isCur = cur != null && cur[0].equals(pr.id)
                                && cur[1].equals(m.id);
                        TextView t1 = text(14, isCur ? Theme.OK : Theme.TXT, isCur);
                        t1.setText((isCur ? "✓ " : "") + m.name);
                        t1.setSingleLine(true);
                        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        TextView t2 = text(11, Theme.TXT_DIM, false);
                        t2.setText(pr.id + "/" + m.id);
                        t2.setSingleLine(true);
                        t2.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                        row.addView(t1);
                        row.addView(t2);
                    } else {
                        TextView t = text(12, Theme.TXT_DIM, false);
                        t.setText(String.valueOf(it[2]));
                        row.addView(t);
                    }
                    return row;
                }
            });
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c2, int d) {}
            public void onTextChanged(CharSequence s, int a, int c2, int d) {}
            public void afterTextChanged(android.text.Editable s) { refill.run(); }
        });
        refill.run();
        lv.setOnItemClickListener((parent, v, pos, id4) -> {
            Object[] it = items.get(pos);
            if (!"m".equals(it[0])) return;
            Models.Prov pr = (Models.Prov) it[1];
            Models.Mdl m = (Models.Mdl) it[2];
            Models.save(this, pr.id, m.id);
            try { AuthStore.setDefaultModel(this, pr.id, m.id); } catch (Exception ignored) {}
            dlg.dismiss();
            recreate();
        });
        dlg.show();
    }

    // ---------------------------------------------------------- doctor

    private void runDoctor() {
        // P11 FIX: the old code called dlg.setView(tv) AFTER show() — on
        // Android that is silently IGNORED, so the dialog sat on
        // "checking…" forever (the exact user report). Now ONE TextView is
        // installed at show-time and updated in place, per check, so
        // progress is visible even on slow probes.
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextColor(Theme.TXT);
        tv.setPadding(Theme.dp(this, 20), Theme.dp(this, 12), Theme.dp(this, 20), Theme.dp(this, 12));
        tv.setText("checking…");
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Sandbox doctor")
                .setView(tv)
                .setPositiveButton("Done", null)
                .show();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            java.util.function.Consumer<String> checking = msg -> ui.post(() -> {
                if (!isFinishing() && !isDestroyed())
                    tv.setText(sb.toString() + "checking " + msg + "…\n");
            });

            checking.accept("the opencode binary");
            String ver = Binaries.probeVersion(this, Binaries.binaryFile(this));
            sb.append("opencode binary: ")
              .append(ver == null ? "not ready" : ver).append('\n');

            checking.accept("the sandbox toolkit");
            sb.append("sandbox toolkit: ").append(Sandbox.ready(this)
                    ? "installed (" + Sandbox.REPO_VER + ")" : "not installed").append('\n');

            checking.accept("the Debian layer");
            sb.append("debian: ").append(Debian.status(this)).append('\n');

            checking.accept("pkg");
            sb.append("pkg: ").append(which("pkg") ? "available" : "missing").append('\n');

            // P12 evidence probe: does the DYNAMIC musl loader survive this
            // device's seccomp? (P11 screenshot showed SIGSYS on `ls`.)
            checking.accept("musl loader (dynamic)");
            sb.append("musl loader: ").append(muslProbe()).append('\n');

            checking.accept("debian proot");
            if (Debian.extracted(this)) {
                StringBuilder po = new StringBuilder();
                int rc = Debian.runGuest(this, "echo debian-ok", po, 30);
                sb.append("debian proot: ").append(rc == 0 && po.toString().contains("debian-ok")
                        ? "works ✓" : "failed rc=" + rc).append('\n');
            } else {
                sb.append("debian proot: not installed\n");
            }

            String[][] checks = {
                    {"python3 (alpine)", "python3"},
                    {"git (alpine or shim)", "git"},
                    {"node", "node"},
                    {"gcc", "gcc"},
                    {"bash (shim)", "bash"},
                    {"busybox", "busybox"},
                    {"tar/gzip", "tar"},
            };
            for (String[] c : checks) {
                checking.accept(c[0]);
                boolean ok = which(c[1]);
                sb.append(c[0]).append(": ")
                  .append(ok ? "available" : "not yet").append('\n');
            }

            sb.append('\n').append("PATH = bin (static applets) → wrappers (alpine) → shims → /system/bin\n")
              .append("agent shells run in the app's exec-allowed home,\n")
              .append("cwd = the open project's folder (per-project sandbox).\n")
              .append("Lite tools:  pkg install python3 py3-pip git nodejs gcc make\n")
              .append("Debian tools:  apt install <anything>  (Settings → environment)\n")
              .append("downloads ride the in-app proxy; signatures verified.\n")
              .append("opencode zen FREE models need no API key at all.\n")
              .append("Zen and Go keys are SEPARATE (P16): OpenCode Zen runs\n")
              .append("the zen models, OpenCode Go runs the Go models — both\n")
              .append("live in API keys, one row each.");
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                tv.setText(sb.toString());
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

    /**
     * P12 evidence probe — runs the Alpine DYNAMIC busybox through the musl
     * loader exactly like the P9 wrappers do, and reports the outcome.
     * "ok" → the old wrappers were never the problem on this device;
     * "Bad system call" → seccomp kills dynamic musl (static applets now
     * shadow them, so the shell still works).
     */
    private String muslProbe() {
        if (!Sandbox.ready(this)) return "toolkit not installed";
        File al = Sandbox.alpineDir(this);
        String lb = new File(al, "lib/ld-musl-aarch64.so.1").getAbsolutePath();
        String bb = new File(al, "bin/busybox").getAbsolutePath();
        try {
            ProcessBuilder pb = new ProcessBuilder(lb,
                    "--library-path", al + "/lib:" + al + "/usr/lib",
                    bb, "echo", "oc-envprobe");
            pb.redirectErrorStream(true);
            Binaries.applyEnv(this, pb);
            Process p = pb.start();
            String o = Api.readAll(p.getInputStream()).trim();
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
            int rc = p.exitValue();
            if (rc == 0 && o.contains("oc-envprobe")) return "ok";
            return "exit " + rc + (o.isEmpty() ? "" : " · " + lastLine(o));
        } catch (Exception e) {
            return "spawn failed · " + e.getClass().getSimpleName();
        }
    }

    private static String lastLine(String s) {
        int i = s.lastIndexOf('\n');
        String l = i >= 0 ? s.substring(i + 1) : s;
        return l.length() > 60 ? l.substring(0, 60) : l;
    }

    // ---------------------------------------------------------- widgets

    private LinearLayout section() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackground(Theme.ripple(this, Theme.panel(this)));
        return s;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Theme.STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = Theme.dp(this, 52);
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout rowLink(String title, String sub, String icon, View.OnClickListener oc) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setClickable(true);
        r.setBackground(Theme.ripple(this, null));
        r.setPadding(Theme.dp(this, 16), Theme.dp(this, 11), Theme.dp(this, 16), Theme.dp(this, 11));

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(14);
        ic.setTextColor(Theme.ACCENT_LT);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1FA5B4FF);
        g.setShape(GradientDrawable.OVAL);
        ic.setBackground(g);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Theme.dp(this, 30), Theme.dp(this, 30));
        ilp.rightMargin = Theme.dp(this, 12);
        ic.setLayoutParams(ilp);
        r.addView(ic);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Theme.TXT);
        col.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11);
            s.setTextColor(Theme.TXT_DIM);
            s.setPadding(0, 2, 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (oc != null) {
            TextView chev = new TextView(this);
            chev.setText("›");
            chev.setTextSize(16);
            chev.setTextColor(Theme.TXT_DIM);
            r.addView(chev);
        }
        Theme.press(r);
        r.setOnClickListener(oc);
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

        // P9 custom switch: animated track + knob (framework-only)
        // defaults: motion ON (matches Theme.motionOn), dns_bridge OFF,
        // auto_allow OFF (approving tool calls silently is opt-in — P14)
        boolean dflt = !"dns_bridge".equals(keyOrNull)
                && !"auto_allow".equals(keyOrNull);
        boolean on = getSharedPreferences("oc", MODE_PRIVATE).getBoolean(keyOrNull, dflt);
        FrameLayout sw = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(Theme.dp(this, 13));
        track.setColor(on ? Theme.ACCENT : Theme.SURFACE2);
        track.setStroke(1, Theme.STROKE);
        View tv = new View(this);
        tv.setBackground(track);
        sw.addView(tv, new FrameLayout.LayoutParams(
                Theme.dp(this, 46), Theme.dp(this, 26)));

        View knob = new View(this);
        GradientDrawable kg = new GradientDrawable();
        kg.setShape(GradientDrawable.OVAL);
        kg.setColor(0xFFFFFFFF);
        knob.setBackground(kg);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(
                Theme.dp(this, 20), Theme.dp(this, 20),
                Gravity.START | Gravity.CENTER_VERTICAL);
        klp.leftMargin = Theme.dp(this, 3);
        knob.setLayoutParams(klp);
        knob.setTranslationX(on ? Theme.dp(this, 20) : 0);
        sw.addView(knob);

        sw.setOnClickListener(v -> {
            boolean now = !getSharedPreferences("oc", MODE_PRIVATE)
                    .getBoolean(keyOrNull, dflt);
            getSharedPreferences("oc", MODE_PRIVATE).edit()
                    .putBoolean(keyOrNull, now).apply();
            knob.animate().translationX(now ? Theme.dp(this, 20) : 0)
                    .setDuration(160).setInterpolator(Theme.DECEL).start();
            if (Theme.motionOn(this)) Theme.pop(sw);
            tv.getBackground().setColorFilter(null);
            track.setColor(now ? Theme.ACCENT : Theme.SURFACE2);
            tv.setBackground(track);
            if ("dns_bridge".equals(keyOrNull) && now) {
                Toast.makeText(this, "bridge on — restart server to apply",
                        Toast.LENGTH_LONG).show();
            }
        });
        r.addView(sw);
        return r;
    }

    private TextView text(int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------- state

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
                s = "running" + (d != null ? "  ·  " + d.getName() : "");
                break;
            case ServerService.ST_STARTING: color = Theme.WARN; s = "starting…"; break;
            case ServerService.ST_EXITED: color = Theme.ERR; s = "exited — restart below"; break;
            case ServerService.ST_STOPPED: color = Theme.ERR; s = "stopped — restart below"; break;
            default: color = Theme.TXT_DIM; s = "idle"; break;
        }
        dot.setTextColor(color);
        stateTxt.setText(s);
        if (pulse != null) { pulse.cancel(); pulse = null; }
        dot.setAlpha(1f);
        if (st == ServerService.ST_STARTING && Theme.motionOn(this)) pulse = Theme.pulse(dot);
    }
}
