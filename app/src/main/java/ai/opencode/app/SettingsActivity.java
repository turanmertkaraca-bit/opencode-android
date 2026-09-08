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
        Theme.window(this);              // P31: palette owns the window + dialogs
        ServerService.subscribe(this);
        refreshState(ServerService.getState(), null);
        refreshPkg();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Theme.syncIfNeeded(this);        // P32: a theme switch elsewhere re-skins here too
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

        // ---- P34: ESSENTIALS — the make-it-work cluster, at the TOP in
        // its own accent-washed family (the field: "important settings
        // stay at top of the settings screen with a difrent color pallet
        // so its easyer to use and see"). The accent-tinted cards cannot
        // be mistaken for the neutral infrastructure sections below, and
        // the four rows cover the features the user actually reaches for.
        root.addView(Theme.sectionLabel(this, "essentials"));
        root.addView(essentialCard("✦", "Interactive canvas",
                "the agent explains with live HTML pages — ask from any chat",
                v -> canvasFromSettings()));
        root.addView(cardGap());
        root.addView(essentialCard("Ⓡ", "Credit limit", creditLimitSub(),
                v -> creditDialog()));
        root.addView(cardGap());
        root.addView(essentialCard("◆", "Default model",
                Models.selected(this) == null ? "auto (server default)"
                        : Models.selected(this)[0] + " / " + Models.selected(this)[1],
                v -> pickModel()));
        root.addView(cardGap());
        root.addView(essentialCard("⚿", "API keys",
                "paste keys · import auth.json · custom endpoints",
                v -> startActivity(new Intent(this, KeysActivity.class))));
        root.addView(cardGap());
        boolean autoAllow = getSharedPreferences("oc", MODE_PRIVATE)
                .getBoolean("auto_allow", false);
        root.addView(essentialCard("◈", "Unattended mode",
                autoAllow ? "ON — the agent approves its own tool calls"
                          : "OFF — approve every tool call yourself",
                v -> {
                    android.content.SharedPreferences.Editor ed =
                            getSharedPreferences("oc", MODE_PRIVATE).edit();
                    ed.putBoolean("auto_allow",
                            !getSharedPreferences("oc", MODE_PRIVATE)
                                    .getBoolean("auto_allow", false));
                    ed.apply();
                    Toast.makeText(this, "unattended mode "
                            + (getSharedPreferences("oc", MODE_PRIVATE)
                            .getBoolean("auto_allow", false) ? "ON" : "OFF"),
                            Toast.LENGTH_SHORT).show();
                    rebuildUi();
                }));

        // ---- agent
        root.addView(Theme.sectionLabel(this, "agent"));
        LinearLayout mk = section();
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
        env.addView(divider());
        // P27 phase 2: the CURATED rootfs — ~50 MB of docs/man/timezones/
        // perl gone at install, caches swept every boot. Default ON.
        env.addView(divider());
        // P31: the factory reset for the TOOL ENVIRONMENT — keys, GitHub
        // token, projects and every chat survive; only extracted tooling.
        env.addView(rowLink("Reset sandbox environment\u2026",
                "wipe Debian + Alpine + shims \u00b7 keys/chats/settings survive",
                "\u267B", v -> confirmEnvReset()));
        env.addView(divider());
        env.addView(switchRow("Curated rootfs", "curate_rootfs",
                "trim docs · man · timezones · perl · locale archives (~50 MB); boot sweeps npm/apt caches (~108 MB)"));
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
        // P31: auto-hibernate — the sandbox stops itself when the app sits
        // unused in the background; reopening drops you back into your chat.
        ka.addView(switchRow("Auto-hibernate (save RAM)", "hibernate",
                "app in the background + no runs + nothing waiting \u2192 the sandbox stops itself; reopening restores your chat from disk"));
        ka.addView(divider());
        ka.addView(rowLink("Hibernate after", hibernateLabel(),
                "\u25F4", v -> pickHibernate()));
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

        // ---- P31: safety — P34: the credit limit lives in ESSENTIALS now
        // (it IS the safety feature; burying it five sections deep was the
        // field's discoverability report)

        // ---- projects
        root.addView(Theme.sectionLabel(this, "projects"));
        LinearLayout pr = section();
        pr.addView(rowLink("Project deck", "credit-card launcher · one sandbox per project",
                "▦", v -> startActivity(new Intent(this, HomeActivity.class))));
        root.addView(pr);

        // ---- interface
        root.addView(Theme.sectionLabel(this, "interface"));
        LinearLayout it = section();
        // P33: Graphite is the default face — the picker says so.
        it.addView(rowLink("Theme", Theme.paletteName(Theme.currentId(this))
                        + " · tap to change",
                "\u25C9", v -> pickTheme()));
        it.addView(divider());
        it.addView(switchRow("Animations", "motion",
                "all movement app-wide (also honors system \"remove animations\")"));
        root.addView(it);

        // ---- about
        root.addView(Theme.sectionLabel(this, "about"));
        LinearLayout ab = section();
        ab.addView(rowLink("Version", "0.34.0-p34 · one Sheet system for every box, back always lands on the deck, essentials on top", "◆", v -> {}));
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

    // ------------------------------------------------------ P34 essentials

    /** Rebuild the whole settings tree in place (the file's established
     *  pattern for state changes that repaint several rows). */
    private void rebuildUi() {
        root.removeAllViews();
        setContentView(buildUi());
    }

    /** The 8dp breathing room between essential cards. */
    private View cardGap() {
        View g = new View(this);
        g.setLayoutParams(new LinearLayout.LayoutParams(1, Theme.dp(this, 8)));
        return g;
    }

    /** ONE accent-washed card — the "different color pallet" the field
     *  asked for: ACCENT_BG fill, accent hairline, an accent icon disc
     *  and accent-tinted title. Unmistakably separate from the neutral
     *  SURFACE infrastructure cards below, on every palette including
     *  Paper (tokens are palette-owned). */
    private LinearLayout essentialCard(String glyph, String title,
                                       String sub, View.OnClickListener oc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.ACCENT_BG);
        bg.setCornerRadius(Theme.dp(this, 16));
        bg.setStroke(Theme.dp(this, 1), (Theme.ACCENT & 0x00FFFFFF) | 0x55000000);
        card.setBackground(Theme.ripple(this, bg));
        card.setPadding(Theme.dp(this, 16), Theme.dp(this, 13),
                Theme.dp(this, 16), Theme.dp(this, 13));
        card.setClickable(true);
        card.setOnClickListener(v -> {
            Theme.haptic(v);
            if (oc != null) oc.onClick(v);
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout discWrap = new FrameLayout(this);
        View disc = new View(this);
        disc.setBackground(Theme.circle(Theme.TINT_ACCENT));
        discWrap.addView(disc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView g = new TextView(this);
        g.setText(glyph);
        g.setTextSize(13);
        g.setTextColor(Theme.ACCENT_LT);
        g.setGravity(Gravity.CENTER);
        discWrap.addView(g, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        LinearLayout.LayoutParams dwlp = new LinearLayout.LayoutParams(
                Theme.dp(this, 26), Theme.dp(this, 26));
        dwlp.rightMargin = Theme.dp(this, 10);
        head.addView(discWrap, dwlp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Theme.ACCENT_LT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(head);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11);
        s.setTextColor(Theme.TXT_DIM);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Theme.dp(this, 4);
        slp.leftMargin = Theme.dp(this, 36);
        card.addView(s, slp);
        return card;
    }

    /** P34: Essentials → Interactive canvas. The explainer sheet with the
     *  ONE-TAP hand-off: opens the last project's chat with the ask
     *  pre-typed (the askCanvas extra), so the feature the field called
     *  invisible is reachable from the very top of Settings. */
    private void canvasFromSettings() {
        Sheet sh = Sheet.show(this, "✦ Interactive canvas");
        if (!sh.showing()) return;
        sh.msg("The agent writes a self-contained HTML page — sliders, "
                + "buttons, live visuals — to " + CanvasDoc.FILE_NAME
                + " in the project, and you open it right in the app. "
                + "Ask for one from any chat.");
        sh.pill("Open chat & ask", Sheet.PRIMARY, () -> {
            try {
                String name = null, path = null;
                String[] last = Resume.parseLastScreen(getSharedPreferences(
                        "oc", MODE_PRIVATE).getString(Resume.KEY, null));
                if ("chat".equals(last[0]) && Projects.validDir(last[2])) {
                    name = last[1];
                    path = last[2];
                } else {
                    Projects.P p = Projects.last(this);
                    if (p != null) { name = p.name; path = p.path; }
                }
                if (path == null || !Projects.validDir(path)) {
                    Toast.makeText(this,
                            "open a project first — the canvas rides a chat",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (!ServerService.pendingRestart()
                        && ServerService.needsSwitch(new File(path))) {
                    ServerService.switchTo(this, new File(path));
                }
                Intent i = new Intent(this, ChatActivity.class);
                i.putExtra("project", name);
                i.putExtra("path", path);
                i.putExtra("askCanvas", " ");
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "cannot open chat: " + e,
                        Toast.LENGTH_SHORT).show();
            }
        });
        sh.pill("Close", Sheet.QUIET, null);
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
        brand.setTextColor(Theme.onCard(0xB3));   // P32: palette-owned ink
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
        stateTxt.setTextColor(Theme.TXT);
        stateTxt.setPadding(Theme.dp(this, 8), 0, 0, 0);
        stateTxt.setMaxLines(2);
        st.addView(stateTxt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heroCard.addView(st);

        TextView restart = new TextView(this);
        restart.setText("Restart server");
        restart.setTextSize(13);
        restart.setTypeface(Typeface.DEFAULT_BOLD);
        restart.setTextColor(Theme.TXT);
        restart.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.onCard(0x33));          // P32: palette-owned ink
        bg.setCornerRadius(Theme.dp(this, 14));
        bg.setStroke(1, Theme.onCard(0x55));
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
    // ============================================ P31: theme / safety / hibernate / reset

    private String hibernateLabel() {
        int m = getSharedPreferences("oc", MODE_PRIVATE).getInt("hibernate_min",
                Hibernate.DEFAULT_MINUTES);
        return "after " + m + " min in the background (no runs)";
    }

    private void pickHibernate() {
        String[] labels = new String[Hibernate.MINUTE_CHOICES.length];
        for (int i = 0; i < labels.length; i++)
            labels[i] = Hibernate.MINUTE_CHOICES[i] + " minutes";
        // P34: the picker rides the Sheet — one app-wide presentation.
        Sheet sh = Sheet.show(this, "Hibernate after");
        if (!sh.showing()) return;
        sh.sub("how long the app idles in the background before the "
                + "sandbox stops itself (a reopen restores your chat)");
        for (int i = 0; i < labels.length; i++) {
            final int minutes = Hibernate.MINUTE_CHOICES[i];
            sh.row("◑", labels[i], null, Theme.TXT, () -> {
                sh.dismiss();
                getSharedPreferences("oc", MODE_PRIVATE).edit()
                        .putInt("hibernate_min", minutes)
                        .apply();
                Toast.makeText(this, "sandbox sleeps after "
                        + minutes + " idle minutes "
                        + "in the background", Toast.LENGTH_SHORT).show();
                rebuildUi();
            });
        }
        sh.pill("Cancel", Sheet.QUIET, null);
    }

    private String creditLimitSub() {
        double cap = RunHub.spendCap();
        double spent = RunHub.spendTotal();
        if (cap <= 0) return spent > 0
                ? "no limit set · " + CreditLimit.fmt(spent) + " spent total"
                : "no limit set \u2014 set one so a runaway loop can\u2019t burn money";
        int v = CreditLimit.verdict(spent, cap);
        return CreditLimit.stateLine(spent, cap)
                + (v == CreditLimit.BLOCK ? " \u00b7 spending paused"
                   : v == CreditLimit.WARN ? " \u00b7 nearing the cap" : "");
    }

    private void creditDialog() {
        // P34: the Pixel-style editor the field asked for ("a slider
        // wouldn't make sense — you wouldn't know the price range — so a
        // direct input is necessary but it still feels old"). The number
        // input stays the source of truth; quick-cap chips cover the
        // common choices, the amount field is big and calm, validation
        // is INLINE (the old toast kept the dialog up but showed
        // nothing), and Save/Reset are stacked full-width pills.
        double cur = RunHub.spendCap();
        final EditText in = Sheet.input(this,
                "cap in dollars, e.g. 5 or 5.50 \u2014 empty = no limit",
                cur > 0 ? String.format(java.util.Locale.US, "%g", cur) : "",
                true);
        in.setTextSize(18);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        final TextView err = new TextView(this);
        err.setTextSize(12);
        err.setTextColor(Theme.ERR);
        err.setPadding(0, Theme.dp(this, 6), 0, 0);
        in.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (err.length() > 0) err.setText("");   // typing clears the error
            }
            public void afterTextChanged(android.text.Editable s) {}
        });

        final Sheet sh = Sheet.show(this, "Credit limit");
        if (!sh.showing()) return;
        sh.sub("All-time spend on this device: "
                + CreditLimit.fmt(RunHub.spendTotal()));
        sh.msg("When total spending reaches the cap, every send is "
                + "refused until you raise or clear it. The counter "
                + "tracks what the app actually observed; reset it if "
                + "you already paid elsewhere.");
        sh.add(in);
        sh.add(err);
        sh.add(Sheet.chipRow(this, CreditLimit.QUICK_CAPS,
                java.util.Collections.singleton(CreditLimit.CAP_NONE),
                v -> {
                    err.setText("");
                    in.setText(v == CreditLimit.CAP_NONE
                            ? "" : CreditLimit.chipLabel(v).replace("$", ""));
                }));
        sh.pillKeep("Save", Sheet.PRIMARY, (s) -> {
            double v = CreditLimit.parseCap(in.getText().toString());
            if (v < 0) {
                err.setText("that is not a dollar amount (example: 5 or 5.50)");
                return;                       // the sheet stays, error inline
            }
            s.dismiss();
            getSharedPreferences("oc", MODE_PRIVATE).edit()
                    .putString("spend_cap", v <= 0 ? ""
                            : String.valueOf(v)).apply();
            Toast.makeText(this, v <= 0 ? "credit limit cleared"
                    : "credit limit set to " + CreditLimit.fmt(v),
                    Toast.LENGTH_SHORT).show();
            rebuildUi();
        });
        sh.pill("Reset spend counter", Sheet.QUIET, () -> {
            RunHub.resetSpendTotal();
            Toast.makeText(this, "spend counter zeroed",
                    Toast.LENGTH_SHORT).show();
            rebuildUi();
        });
        sh.pill("Cancel", Sheet.QUIET, null);
        sh.focus(in);
    }

    private void pickTheme() {
        // P32: this dialog builder was the field crash — a dead
        // (LinearLayout) cast of android.R.layout.simple_list_item_1 (a
        // TextView!) threw ClassCastException on EVERY tap of the Theme
        // row, before the sheet ever opened. The dead cast is gone, and
        // the whole builder is contained: a failure now costs one honest
        // toast + an incident-log line, never the app.
        try {
            String cur = Theme.currentId(this);
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            int pad = Theme.dp(this, 4);
            wrap.setPadding(pad, pad, pad, pad);
            final Sheet[] holder = new Sheet[1];
            for (int i = 0; i < Theme.PALETTES.length; i++) {
                final String id = Theme.PALETTES[i];
                // P32: the picker rows are REAL rows now — name on the
                // left, three live swatches (bg · surface2 · accent)
                // straight from the palette table on the right, so the
                // user sees what they are choosing before they commit.
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                boolean on = id.equals(cur);
                TextView t = new TextView(this);
                t.setTextSize(15);
                t.setTextColor(on ? Theme.ACCENT_LT : Theme.TXT);
                t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                t.setText((on ? "\u2713  " : "") + Theme.paletteName(id)
                        + (id.equals(Theme.DEFAULT_PALETTE) ? "  \u00b7 default" : ""));
                t.setPadding(Theme.dp(this, 18), Theme.dp(this, 13),
                        Theme.dp(this, 18), Theme.dp(this, 13));
                row.addView(t, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                int[] p = Theme.PALETTE_DATA[Theme.paletteIndex(id)];
                int[] swatch = {p[0], p[2], p[4]};        // bg · surface2 · accent
                for (int sc : swatch) {
                    View dot = new View(this);
                    GradientDrawable d = Theme.circle(sc);
                    d.setStroke(Theme.dp(this, 1), Theme.STROKE);
                    dot.setBackground(d);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            Theme.dp(this, 14), Theme.dp(this, 14));
                    lp.rightMargin = Theme.dp(this, 7);
                    row.addView(dot, lp);
                }
                wrap.addView(row);
                row.setBackground(Theme.ripple(this, null));
                Theme.press(row);
                row.setOnClickListener(v -> {
                    Theme.haptic(v);
                    // P33: the theme lands the SAME FRAME — save + apply,
                    // dismiss the sheet, then rebuild this screen in place
                    // from the new tokens. The old flow called recreate(),
                    // which tore the whole activity down and rebuilt it
                    // with window animations — seconds of dead air the
                    // field reported as "takes a while, feels bad". A
                    // rebuild of ONE view tree is milliseconds: the new
                    // palette is on screen before the sheet finishes its
                    // 180 ms slide-out. Other open screens pick the palette
                    // up on their next resume (per-screen syncIfNeeded).
                    try {
                        getSharedPreferences("oc", MODE_PRIVATE).edit()
                                .putString("theme", id).apply();
                        Theme.apply(this);
                        // P36: the launcher icon follows the theme
                        // (contained — a PackageManager nit logs one
                        // line, never breaks the switch that already
                        // succeeded).
                        LauncherIcon.sync(this, id);
                        if (holder[0] != null && holder[0].showing())
                            holder[0].dismiss();
                        setContentView(buildUi());
                        Theme.window(this);   // chrome + retint + restamp
                        refreshState(ServerService.getState(), null);
                        refreshPkg();
                    } catch (Exception e) {
                        // final-version insurance: a failed re-skin must
                        // never take Settings down (the P32 lesson, kept)
                        Toast.makeText(this, "theme switch failed \u2014 logged",
                                Toast.LENGTH_SHORT).show();
                        ServerService.appendDiagStatic(this, "theme-apply",
                                Resilience.traceLine(e));
                    }
                });
            }
            // P34: the theme picker rides the Sheet — and the sheet itself
            // dismisses BEFORE the rebuild, so its own window never has to
            // outlive the palette swap that repainted its host.
            Sheet sh = Sheet.show(this, "Theme");
            if (!sh.showing()) return;
            sh.add(wrap);
            sh.pill("Cancel", Sheet.QUIET, null);
            holder[0] = sh;
        } catch (Exception e) {
            // final-version insurance: the picker failing must never take
            // Settings down (and the incident log keeps it diagnosable)
            Toast.makeText(this, "theme picker failed \u2014 logged", Toast.LENGTH_SHORT).show();
            ServerService.appendDiagStatic(this, "theme-picker",
                    Resilience.traceLine(e));
        }
    }

    /** P31: the environment factory reset — honest sheet, guarded wipe,
     *  optional immediate Debian reinstall. */
    private void confirmEnvReset() {
        StringBuilder msg = new StringBuilder("WILL be wiped (re-extracted from "
                + "the bundled, sha-verified assets on next boot):\n");
        for (String n : EnvironmentReset.targetNames()) msg.append(" \u2022 ").append(n).append('\n');
        msg.append("\nSTAYS exactly as it is:\n");
        for (String n : EnvironmentReset.keptNames()) msg.append(" \u2022 ").append(n).append('\n');
        Sheet.show(this, "Reset sandbox environment?")
                .msg(msg.toString())
                .pill("Reset", Sheet.DANGER, () -> runEnvReset())
                .pill("Cancel", Sheet.QUIET, null);
    }

    private void runEnvReset() {
        Toast.makeText(this, "resetting the environment\u2026", Toast.LENGTH_SHORT).show();
        final File files = getFilesDir();
        final File cache = getCacheDir();
        new Thread(() -> {
            Throwable t = Resilience.guard(() -> {
                ServerService.stopForDelete(this);   // no respawn mid-wipe
                int n = EnvironmentReset.wipe(
                        EnvironmentReset.targets(files, cache), files, cache);
                ServerService.appendDiagStatic(this, "env-reset",
                        "environment reset \u2014 " + n + " entries wiped "
                                + "(keys/chats/settings kept)");
                ui.post(() -> Sheet.show(this, "Environment reset")
                        .msg(n + " entries wiped. Reinstall the Debian "
                                + "environment now (takes a minute), or let it "
                                + "happen on demand later? The Lite toolkit "
                                + "reinstalls itself on the next launch either way.")
                        .pill("Reinstall now", Sheet.PRIMARY, () -> {
                            ServerService.restart(this);
                            installDebian();
                        })
                        .pill("Later", Sheet.QUIET, () -> {
                            ServerService.restart(this);
                            Toast.makeText(this, "environment will rebuild on demand",
                                    Toast.LENGTH_SHORT).show();
                        }));
            });
            if (t != null) {
                Trail.record(this, "env reset", t);
                ui.post(() -> Toast.makeText(this, "reset failed: " + t.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "oc-env-reset").start();
    }

    private void installDebian() {
        TextView prog = new TextView(this);
        prog.setTypeface(Typeface.MONOSPACE);
        prog.setTextSize(12);
        prog.setTextColor(Theme.TXT);
        prog.setPadding(Theme.dp(this, 4), Theme.dp(this, 8),
                Theme.dp(this, 4), Theme.dp(this, 8));
        prog.setText("starting…");
        // P34: the progress rides the Sheet; the quiet pill flips to
        // "Close" when the watcher thread sees the install finish
        // (the old code mutated the framework button).
        final Sheet sh = Sheet.show(this, "Debian environment");
        if (!sh.showing()) return;
        sh.add(prog);
        final TextView bgPill = sh.pillView("Run in background", Sheet.QUIET,
                () -> Toast.makeText(this, "installing in background…",
                        Toast.LENGTH_SHORT).show());
        sh.add(bgPill);
        new Thread(() -> Debian.install(this, msg -> ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            prog.setText(msg);
        })), "oc-debian").start();
        // the thread finishes when install does; poll for completion to
        // enable the Close state refresh without blocking the user
        new Thread(() -> {
            while (true) {
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                boolean done = Debian.extracted(this);
                ui.post(() -> {
                    refreshDebian();
                    if (done && sh.showing() && !isFinishing())
                        bgPill.setText("Close");
                });
                if (done) return;
            }
        }, "oc-debian-watch").start();
    }

    /** P15 — the agent's own environment-detection ask, surfaced: runs the
     *  Debian env report (kernel · user · os · tools · storage · project)
     *  and also verifies the dirs proot needs actually exist. */
    private void runEnvCheck() {
        Sheet waiting = Sheet.show(this, "Environment");
        if (waiting.showing()) waiting.msg("checking…");
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
                waiting.dismiss();
                Sheet.show(this, "Environment")
                        .msg(rep)
                        .pill("copy", Sheet.PRIMARY, () -> {
                            ClipboardManager cm =
                                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("env", rep));
                            Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show();
                        })
                        .pill("close", Sheet.QUIET, null);
            });
        }).start();
    }

    private void envExplain() {
        Sheet.show(this, "Two environments")
                .msg(
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
                .pill("Got it", Sheet.QUIET, null);
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
        // P34: scrollable on the Sheet — a long log used to overflow the
        // framework box with no way to reach the top.
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Theme.TXT);
        tv.setLineSpacing(Theme.dp(this, 1), 1f);
        tv.setText("each line: timestamp \u00b7 event \u00b7 detail \u00b7 free memory\n\n"
                + body);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        Sheet.show(this, "Sandbox incident log")
                .scroll(sv, 0.55f)
                .pill("Close", Sheet.QUIET, null);
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
        Sheet.show(this, "Samsung keep-alive")
                .msg(
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
                .pill("Open battery settings", Sheet.PRIMARY, () -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {}
                })
                .pill("Close", Sheet.QUIET, null);
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
        // P34: the default-model picker rides the Sheet system.
        final Sheet sh = Sheet.show(this, "Default model · " + total + " available");
        if (!sh.showing()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(4);
        box.setPadding(p, 0, p, 0);
        final EditText search = Sheet.input(this, "search provider or model…", null, true);
        search.setTextSize(14);
        box.addView(search);
        final android.widget.ListView lv = new android.widget.ListView(this);
        sh.add(box);
        sh.addFixed(lv, 430);
        sh.focus(search);

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
            sh.dismiss();
            recreate();
        });
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
        tv.setPadding(Theme.dp(this, 4), Theme.dp(this, 8), Theme.dp(this, 4), Theme.dp(this, 8));
        tv.setText("checking…");
        // P34: the doctor rides the Sheet — one TextView installed at
        // show-time and updated in place, per check, so progress is
        // visible even on slow probes (the P11 lesson kept).
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        final Sheet sh = Sheet.show(this, "Sandbox doctor");
        if (!sh.showing()) return;
        sh.scroll(sv, 0.55f);
        sh.pill("Done", Sheet.QUIET, null);
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
        g.setColor(Theme.ICON_DISC);   // P31 token
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
        kg.setColor(Theme.TXT);   // switch knob
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
            // P27: theme switch re-reads the palette and rebuilds this
            // screen (every other screen re-reads on its next creation).
            if ("amoled".equals(keyOrNull)) {
                Theme.apply(SettingsActivity.this);
                recreate();
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
