package ai.opencode.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * P7 diagnostics: server log tail, proxy state, binary facts + re-unpack,
 * a native shell console (the "small Termux" — runs in the app's own
 * exec-allowed home, no proot), and SAF import of static arm64 binaries
 * into bin/ (bring your own git/rg/…).
 */
public class DiagnosticsActivity extends Activity {

    private static final int REQ_IMPORT = 51;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView tvLog, tvBin, tvProxy, tvShellOut, tvBinDir;
    private boolean running = true;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (!running) return;
            tvLog.setText(ServerService.getTail());
            tvProxy.setText(proxyText());
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.bg));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(32));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(header());

        root.addView(section("server"));
        LinearLayout srv = row("state", "");
        ((TextView) srv.getChildAt(0)).setText(serverText());
        root.addView(srv);
        tvLog = monoText();
        root.addView(tvLog);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView r1 = chip("Restart");
        r1.setOnClickListener(v -> {
            ServerService.restart(this);
            Toast.makeText(this, "restarting…", Toast.LENGTH_SHORT).show();
        });
        TextView r2 = chip("Stop");
        r2.setTextColor(getColor(R.color.err));
        r2.setOnClickListener(v -> {
            Intent i = new Intent(this, ServerService.class)
                    .setAction(ServerService.ACTION_STOP);
            startService(i);
        });
        btns.addView(r1);
        btns.addView(r2);
        root.addView(btns);

        root.addView(section("last Java crash — the actual trace"));
        root.addView(crashSection());

        // P27 phase 2: sandbox weight + boot budget — the trim report, the
        // boot hygiene line, and the measured cold-boot numbers, so the
        // "size + speed" work is ON THE RECORD, not a claim.
        root.addView(section("sandbox weight (P27 curated rootfs)"));
        TextView tvSize = monoText();
        StringBuilder sz = new StringBuilder();
        try {
            Debian.ensureDirs(this);
            long deb = Debian.sizeOf(this);
            String trim = readSmall(new java.io.File(Debian.dir(this), "trim-report.txt"));
            String hyg = readSmall(new java.io.File(Debian.dir(this), "hygiene.txt"));
            sz.append("debian layer: ").append(Binaries.human(deb)).append('\n');
            if (trim != null) sz.append("trim: ").append(trim.replace('\n', ' ')).append('\n');
            if (hyg != null) sz.append("boot hygiene: ").append(hyg.replace('\n', ' ')).append('\n');
            if (trim == null && hyg == null)
                sz.append("no trim/hygiene report yet (install Debian or reboot the sandbox)\n");
        } catch (Exception e) {
            sz.append("(unavailable: ").append(e.getMessage()).append(")\n");
        }
        // P27: the app+server memory budget — what the sandbox costs right
        // now (app = our own VmRSS; server = the opencode child's, found by
        // exact binary-path match like the orphan sweep, read-only).
        sz.append(rssBudget());
        tvSize.setText(sz.toString());
        root.addView(tvSize);
        root.addView(note("The curated rootfs drops docs/man pages/legacy "
                + "timezones/locale archives/perl at install and sweeps the "
                + "npm + apt caches at every boot. The opencode server binary "
                + "is upstream (bun-embedded runtime — its size is structural); "
                + "it ships once, compressed, never duplicated."));

        root.addView(section("contained errors — caught so the app didn't crash"));
        TextView tvTrail = monoText();
        String trail = Trail.read(this);
        if (trail.length() > 4000) trail = "…\n" + trail.substring(trail.length() - 4000);
        tvTrail.setText(trail.isEmpty()
                ? "· none yet — nothing has been contained"
                : trail);
        root.addView(tvTrail);
        root.addView(note("P23: every failure the app CONTAINS instead of dying "
                + "from lands here (and in a chat line). If anything ever misbehaves, "
                + "paste these lines — they name the exact thrower."));

        root.addView(section("last exits — why Android stopped the app"));
        TextView tvExits = monoText();
        tvExits.setText(exitInfoText());
        root.addView(tvExits);
        root.addView(note("This is the system's own kill record for the app "
                + "process — it names the killer even for deaths that left no "
                + "crash file (LOW MEMORY = the system freed RAM, ANR = the UI "
                + "froze, NATIVE crash = our bug, signal = external kill). If "
                + "the sandbox or the whole app ever dies again, paste these "
                + "lines + the sandbox incident log below."));

        root.addView(section("network (DNS)"));
        tvProxy = monoText();
        root.addView(tvProxy);
        final TextView bridge = chip("DNS bridge: "
                + (getSharedPreferences("oc", MODE_PRIVATE)
                        .getBoolean("dns_bridge", false) ? "ON" : "OFF"));
        bridge.setOnClickListener(v -> {
            boolean now = !getSharedPreferences("oc", MODE_PRIVATE)
                    .getBoolean("dns_bridge", false);
            getSharedPreferences("oc", MODE_PRIVATE).edit()
                    .putBoolean("dns_bridge", now).apply();
            bridge.setText("DNS bridge: " + (now ? "ON" : "OFF"));
            Toast.makeText(this, now
                    ? "bridge ON — restart server to apply"
                    : "bridge OFF — restart server to apply",
                    Toast.LENGTH_LONG).show();
        });
        root.addView(bridge);
        root.addView(note("The bundled agent is an Android (bionic) build: it "
                + "resolves DNS through the OS like every normal app — no proxy "
                + "needed. The DNS bridge is an escape hatch for exotic VPN/DNS "
                + "setups: it runs a local CONNECT proxy and routes the agent's "
                + "traffic through it. Leave OFF unless provider calls fail with "
                + "DNS errors."));

        root.addView(section("agent binary"));
        tvBin = monoText();
        File bin = Binaries.binaryFile(this);
        tvBin.setText(bin.exists()
                ? bin.getAbsolutePath() + "\n" + Binaries.human(bin.length())
                + " · sha " + Binaries.sha256(bin)
                : "not extracted yet");
        root.addView(tvBin);
        LinearLayout b2 = new LinearLayout(this);
        b2.setOrientation(LinearLayout.HORIZONTAL);
        TextView ver = chip("opencode --version");
        ver.setOnClickListener(v -> runShell("opencode --version", tvShellOut));
        TextView re = chip("Re-unpack");
        re.setOnClickListener(v -> confirmReunpack());
        b2.addView(ver);
        b2.addView(re);
        root.addView(b2);

        root.addView(section("shell (native · no proot)"));
        final EditText cmd = new EditText(this);
        cmd.setHint("command, e.g. uname -a");
        cmd.setTextSize(14);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setSingleLine(true);
        root.addView(cmd);
        LinearLayout b3 = new LinearLayout(this);
        b3.setOrientation(LinearLayout.HORIZONTAL);
        TextView run = chip("Run");
        run.setOnClickListener(v -> runShell(cmd.getText().toString(), tvShellOut));
        b3.addView(run);
        for (String preset : new String[]{"uname -a", "busybox | head -1", "pkg version",
                "pkg list | head -12", "pkg rehash", "ls /system/bin"}) {
            TextView c = chip(preset);
            c.setOnClickListener(v -> { cmd.setText(preset); runShell(preset, tvShellOut); });
            b3.addView(c);
        }
        root.addView(b3);
        tvShellOut = monoText();
        tvShellOut.setText("·");
        root.addView(tvShellOut);

        root.addView(section("bin/ — your own static arm64 tools"));
        LinearLayout imp = row("Import binary", "git, rg, fd, jq… any static arm64 ELF — lands in bin/, first on PATH");
        imp.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_IMPORT);
            } catch (Exception e) {
                Toast.makeText(this, "no file picker", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(imp);
        tvBinDir = monoText();
        root.addView(tvBinDir);

        refreshBinDir();
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        running = false;
        ui.removeCallbacks(refresher);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null
                || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            String name = queryName(uri);
            if (name == null || name.isEmpty()) name = "tool-" + System.currentTimeMillis();
            name = new File(name).getName().replaceAll("[^A-Za-z0-9._-]", "_");
            File dst = new File(Shims.binDir(this), name);
            long n = Binaries.copyFromUri(this, uri, dst);
            Binaries.makeExec(dst);
            Toast.makeText(this, name + " (" + Binaries.human(n) + ") → bin/",
                    Toast.LENGTH_LONG).show();
            refreshBinDir();
        } catch (Exception e) {
            Toast.makeText(this, "import failed: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void refreshBinDir() {
        File[] files = Shims.binDir(this).listFiles();
        StringBuilder sb = new StringBuilder();
        if (files != null) for (File f : files) sb.append(f.getName()).append('\n');
        if (sb.length() == 0) sb.append("(empty — busybox arrives on first server start)");
        tvBinDir.setText(sb.toString());
    }

    private void confirmReunpack() {
        new AlertDialog.Builder(this)
                .setTitle("Re-unpack bundled agent?")
                .setMessage("Extracts the opencode binary from the APK again "
                        + "(use after a failed update). The server restarts.")
                .setPositiveButton("Extract", (d, w) -> {
                    ServerService.restart(this);
                    new Thread(() -> {
                        try {
                            Binaries.extractBundled(this, m -> {});
                            ui.post(() -> {
                                Toast.makeText(this, "binary re-unpacked",
                                        Toast.LENGTH_SHORT).show();
                                File bin = Binaries.binaryFile(this);
                                tvBin.setText(bin.getAbsolutePath() + "\n"
                                        + Binaries.human(bin.length())
                                        + " · sha " + Binaries.sha256(bin));
                            });
                        } catch (Exception e) {
                            ui.post(() -> Toast.makeText(this,
                                    "re-unpack failed: " + e, Toast.LENGTH_LONG).show());
                        }
                    }, "oc-reunpack").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runShell(String cmd, TextView out) {
        if (cmd == null || cmd.trim().isEmpty()) return;
        out.setText("running…");
        new Thread(() -> {
            String result;
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
                pb.directory(Binaries.homeDir(this));
                pb.redirectErrorStream(true);
                Binaries.applyEnv(this, pb);
                Process p = pb.start();
                result = Api.readAll(p.getInputStream());
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroy();
                    result = "(timed out after 30 s)\n" + result;
                } else {
                    result = "exit " + p.exitValue() + "\n" + result;
                }
            } catch (Exception e) {
                result = "error: " + e;
            }
            final String f = result;
            ui.post(() -> out.setText(f));
        }, "oc-shell").start();
    }

    private String serverText() {
        switch (ServerService.getState()) {
            case ServerService.ST_HEALTHY: return "● running · 127.0.0.1:" + Api.PORT;
            case ServerService.ST_STARTING: return "● starting…";
            case ServerService.ST_EXITED: return "● exited";
            case ServerService.ST_STOPPED: return "● stopped";
            default: return "● idle";
        }
    }

    private String proxyText() {
        int p = ProxyServer.port();
        StringBuilder sb = new StringBuilder();
        sb.append(p > 0 ? "listening on 127.0.0.1:" + p
                : "not running (bind failed?)");
        sb.append(" · connections: ").append(ProxyServer.connections());
        String errs = ProxyServer.errors();
        if (!errs.isEmpty()) sb.append("\n---- last events ----\n").append(errs);
        return sb.toString();
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
        title.setText("Diagnostics");
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

    /** P23: the FULL last-crash.txt content + copy/clear — the boot screen
     *  had this, but warm launches skip the boot screen, so the field
     *  could only ever paste "crash file exists (1 KB)". One tap now. */
    private LinearLayout crashSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        File cf = App.crashFile(this);
        TextView tvCrash = monoText();
        if (cf.exists() && cf.length() > 0) {
            tvCrash.setText(readBounded(cf, 4000));
        } else {
            tvCrash.setText("· no Java crash file — good");
        }
        box.addView(tvCrash);
        if (cf.exists() && cf.length() > 0) {
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            TextView copy = chip("Copy trace");
            copy.setOnClickListener(v -> {
                try {
                    String body = readBounded(cf, 100_000);
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("opencode crash", body));
                    Toast.makeText(this, "crash trace copied — paste it to the dev",
                            Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "copy failed: " + e, Toast.LENGTH_SHORT).show();
                }
            });
            TextView clr = chip("Clear");
            clr.setOnClickListener(v -> {
                try { cf.delete(); } catch (Exception ignored) {}
                tvCrash.setText("· cleared");
            });
            btns.addView(copy);
            btns.addView(clr);
            box.addView(btns);
        }
        return box;
    }

    /** Tail-bounded file read for display. Never throws. */
    private static String readBounded(File f, int maxChars) {
        try {
            String s = Api.readAll(new java.io.FileInputStream(f));
            if (s.length() <= maxChars) return s;
            return "…" + s.substring(s.length() - maxChars);
        } catch (Throwable t) {
            return "(unreadable: " + t + ")";
        }
    }

    /** P27: one-liner read for optional report files — null when absent
     *  (unlike readBounded, absence is a normal state here, not an error). */
    private static String readSmall(File f) {
        try {
            if (!f.isFile()) return null;
            String s = Api.readAll(new java.io.FileInputStream(f)).trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** P27: VmRSS (kB) out of a /proc/<pid>/status payload. Pure. */
    static long parseVmRssKb(String status) {
        if (status == null) return -1;
        for (String line : status.split("\n")) {
            if (line.startsWith("VmRSS:")) {
                String digits = line.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    try { return Long.parseLong(digits); } catch (Exception ignored) {}
                }
            }
        }
        return -1;
    }

    /** P27: the app+server RSS budget line for Diagnostics. The server
     *  child is located by exact binary-path match (the orphan-sweep
     *  rule, read-only) so no other app's process is ever touched. */
    private String rssBudget() {
        StringBuilder b = new StringBuilder();
        try {
            long appRss = parseVmRssKb(Api.readAll(
                    new java.io.FileInputStream("/proc/self/status")));
            b.append("app RSS: ").append(appRss > 0 ? Binaries.human(appRss * 1024) : "?");
            long serverRss = -1;
            File bin = Binaries.binaryFile(this);
            File[] dirs = new File("/proc").listFiles();
            if (dirs != null && bin.exists()) {
                for (File d : dirs) {
                    try { Integer.parseInt(d.getName()); } catch (Exception e) { continue; }
                    String cl;
                    try {
                        cl = Api.readAll(new java.io.FileInputStream(new File(d, "cmdline")));
                    } catch (Exception e) { continue; }
                    if (!Resilience.isOcCmdline(cl, bin.getAbsolutePath())) continue;
                    long r = parseVmRssKb(Api.readAll(
                            new java.io.FileInputStream(new File(d, "status"))));
                    if (r > serverRss) serverRss = r;
                }
            }
            b.append(" · server RSS: ")
             .append(serverRss > 0 ? Binaries.human(serverRss * 1024)
                    : "not running");
            long mem = -1;
            try {
                mem = Resilience.parseMemAvailableKb(Api.readAll(
                        new java.io.FileInputStream("/proc/meminfo")));
            } catch (Exception ignored) {}
            if (mem > 0) b.append(" · device free: ").append(Binaries.human(mem * 1024));
            b.append('\n');
        } catch (Exception e) {
            b.append("memory budget unavailable: ").append(e.getMessage()).append('\n');
        }
        return b.toString();
    }

    /** P21: the system's own record of why OUR process exited, newest
     *  first. This is the evidence that was missing all along: the field
     *  deaths left no Java crash file because they were NOT Java crashes
     *  — ApplicationExitInfo names the real killer (LMKD / ANR / native /
     *  signal) from the device, with no adb, and it works RETROACTIVELY
     *  for deaths that already happened. */
    private String exitInfoText() {
        StringBuilder b = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                java.util.List<ApplicationExitInfo> xs =
                        am.getHistoricalProcessExitReasons(getPackageName(), 0, 8);
                if (xs.isEmpty()) {
                    b.append("no exit records — the app process has not died");
                }
                for (ApplicationExitInfo x : xs) {
                    b.append(Resilience.formatExitLine(x.getTimestamp(),
                            x.getReason(), x.getStatus(), x.getDescription()));
                    b.append('\n');
                }
            } catch (Exception e) {
                b.append("exit records unavailable: ").append(e.getMessage());
            }
        } else {
            b.append("exit records need Android 11+ — this device is older; "
                    + "use the sandbox incident log instead");
        }
        return b.toString().trim();
    }

    private LinearLayout row(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        box.setPadding(p, dp(10), p, dp(10));
        box.setBackgroundResource(R.drawable.bg_card);
        TextView t1 = text(14, R.color.text_primary, false);
        t1.setText(title);
        box.addView(t1);
        return box;
    }

    private TextView chip(String label) {
        TextView t = text(13, R.color.text_primary, false);
        t.setText(label);
        t.setBackgroundResource(R.drawable.bg_chip);
        int p = dp(12);
        t.setPadding(p, dp(8), p, dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(12, R.color.text_secondary, false);
        t.setText(s);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private TextView monoText() {
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setTextColor(getColor(R.color.text_primary));
        t.setBackgroundColor(getColor(R.color.surface));
        int p = dp(10);
        t.setPadding(p, p, p, p);
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(int sizeSp, int colorRes, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextSize(sizeSp);
        tv.setTextColor(getColor(colorRes));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
