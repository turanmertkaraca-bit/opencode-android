package ai.opencode.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * P8 boot: launch → (home? chat : auto-boot → home).
 *
 * Boot is a quiet log: unpack the bundled binary if needed, start the
 * foreground service, wait for health, then hand off to the PROJECT DECK
 * (HomeActivity) — the new home screen with the credit-card carousel.
 * The last-used project is pre-warmed as the server's sandbox root so
 * opening its card needs no restart. Any failure shows Retry/Diagnostics.
 */
public class MainActivity extends Activity implements ServerService.Evt {

    private TextView log;
    private TextView crash;
    private View retry, diag;
    private final StringBuilder buf = new StringBuilder();
    private volatile boolean launched, failed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        log = findViewById(R.id.tvLog);
        crash = findViewById(R.id.tvCrash);
        retry = findViewById(R.id.btnRetry);
        diag = findViewById(R.id.btnDiag);

        retry.setOnClickListener(v -> { hideButtons(); failed = false; boot(); });
        diag.setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));

        File cf = App.crashFile(this);
        if (cf.exists()) {
            crash.setVisibility(View.VISIBLE);
            crash.setOnClickListener(v -> showCrash(cf));
        }

        if (ServerService.healthy()) {
            goHome();
            return;
        }
        ServerService.subscribe(this);
        boot();
    }

    @Override
    protected void onDestroy() {
        ServerService.unsubscribe(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------- boot

    private void boot() {
        line("OpenCode · starting");
        // P8: pre-warm the last project's sandbox root BEFORE the server
        // spawns — opening its card later is instant (no restart).
        try {
            Projects.P last = Projects.last(this);
            if (last != null && Projects.validDir(last.path)) {
                ServerService.setStartDir(new File(last.path));
                line("sandbox: " + last.name);
            }
        } catch (Exception ignored) {}
        new Thread(() -> {
            try {
                if (!Binaries.binaryReady(this)) {
                    line("unpacking bundled agent (~60 MB, first run only)…");
                    Binaries.extractBundled(this, msg -> line("  " + msg));
                }
                File bin = Binaries.binaryFile(this);
                line("binary ok · " + Binaries.human(bin.length())
                        + " · sha " + Binaries.sha256(bin));
                line("starting server…");
                int st = ServerService.getState();
                if (st == ServerService.ST_IDLE || st == ServerService.ST_STOPPED
                        || st == ServerService.ST_EXITED) {
                    startForegroundService(new Intent(this, ServerService.class));
                }
                // wait for health (service pushes events too; this loop is
                // the belt-and-braces path if the UI missed a transition)
                long t0 = System.currentTimeMillis();
                while (!failed && System.currentTimeMillis() - t0 < 90_000) {
                    Thread.sleep(400);
                    if (ServerService.healthy()) { goHome(); return; }
                    int s = ServerService.getState();
                    if (s == ServerService.ST_EXITED) {
                        fail("server exited · " + ServerService.getTail());
                        return;
                    }
                }
                if (!failed) fail("server did not become healthy in 90 s");
            } catch (Exception e) {
                fail(String.valueOf(e));
            }
        }, "oc-boot").start();
    }

    private void fail(String msg) {
        failed = true;
        line("✕ " + msg);
        runOnUiThread(() -> {
            retry.setVisibility(View.VISIBLE);
            diag.setVisibility(View.VISIBLE);
        });
    }

    private void hideButtons() {
        retry.setVisibility(View.GONE);
        diag.setVisibility(View.GONE);
    }

    private void goHome() {
        synchronized (this) {
            if (launched) return;
            launched = true;
        }
        runOnUiThread(() -> {
            try {
                startActivity(new Intent(this, HomeActivity.class));
                finish();
            } catch (Exception e) {
                fail("cannot open home: " + e);
            }
        });
    }

    // ------------------------------------------------------------ log ui

    private void line(final String s) {
        runOnUiThread(() -> {
            buf.append(s).append('\n');
            if (buf.length() > 8000) buf.delete(0, 4000);
            log.setText(buf.toString());
            ScrollView sv = (ScrollView) log.getParent();
            if (sv != null) sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void showCrash(File f) {
        String txt;
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            txt = o.toString("UTF-8");
        } catch (Exception e) {
            txt = "(unreadable: " + e + ")";
        }
        final String body = txt;
        new AlertDialog.Builder(this)
                .setTitle("Crash report")
                .setMessage(body.length() > 4000 ? body.substring(0, 4000) + "\n…(full text in Diagnostics)" : body)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", body));
                    Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Delete", (d, w) -> { f.delete(); crash.setVisibility(View.GONE); })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------------------------------------------------- service events

    @Override
    public void on(int newState, String detail) {
        if (newState == ServerService.ST_HEALTHY) goHome();
        else if (newState == ServerService.ST_EXITED && !launched) {
            fail(detail == null ? "server exited" : detail);
        }
    }
}
