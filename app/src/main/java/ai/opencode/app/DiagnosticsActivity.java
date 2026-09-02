package ai.opencode.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
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
        for (String preset : new String[]{"uname -a", "busybox | head -1", "ls /system/bin"}) {
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
