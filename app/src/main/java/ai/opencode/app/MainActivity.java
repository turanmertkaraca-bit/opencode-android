package ai.opencode.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * P6 main screen: a first-run WIZARD instead of a checklist.
 *
 * 1. Binary: bundled in the APK → auto-extracted on first launch with a
 *    progress line (SAF import kept as an advanced fallback).
 * 2. Server: auto-starts as soon as the binary is ready.
 * 3. API keys: the Connect screen writes auth.json / opencode.json in the
 *    app sandbox — no desktop file hunting. Until a key exists, the connect
 *    card is highlighted; chat shows a setup shortcut if a send fails.
 * 4. Chat auto-opens once the server is healthy AND a key exists.
 * 5. Sandbox: auto-installs once (proot + Alpine, bundled), tools remain a
 *    one-tap extra (apk add needs network).
 */
public class MainActivity extends Activity implements ServerService.Evt {

    private static final int REQ_BIN = 1, REQ_AUTH = 2, REQ_CFG = 3;

    private TextView tvServerState, tvBinaryInfo, tvEndpoint, tvHint, tvSandbox,
            tvAuthStatus, tvStep;
    private Button btnServer, btnChat, btnConnect;

    private volatile boolean extracting;
    private volatile boolean sandboxAutoDone; // per-process guard

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        tvServerState = findViewById(R.id.tvServerState);
        tvBinaryInfo  = findViewById(R.id.tvBinaryInfo);
        tvEndpoint    = findViewById(R.id.tvEndpoint);
        tvHint        = findViewById(R.id.tvHint);
        tvSandbox     = findViewById(R.id.tvSandbox);
        tvAuthStatus  = findViewById(R.id.tvAuthStatus);
        tvStep        = findViewById(R.id.tvStep);
        btnServer     = findViewById(R.id.btnServer);
        btnChat       = findViewById(R.id.btnChat);
        btnConnect    = findViewById(R.id.btnConnect);

        findViewById(R.id.btnImportBin).setOnClickListener(v -> pick(REQ_BIN, "application/octet-stream"));
        findViewById(R.id.btnImportAuth).setOnClickListener(v -> pick(REQ_AUTH, "application/json"));
        findViewById(R.id.btnImportCfg).setOnClickListener(v -> pick(REQ_CFG, "application/json"));

        btnConnect.setOnClickListener(v ->
                startActivity(new Intent(this, ProviderSetupActivity.class)));

        btnServer.setOnClickListener(v -> {
            if (ServerService.getState() == ServerService.ST_STARTING
                    || ServerService.getState() == ServerService.ST_HEALTHY) {
                Intent i = new Intent(this, ServerService.class)
                        .setAction(ServerService.ACTION_STOP);
                startService(i);
                ServerService.unsubscribe(this);
                stopService(new Intent(this, ServerService.class));
                refresh();
            } else {
                if (!Binaries.binaryReady(this)) {
                    Toast.makeText(this, "binary still preparing…", Toast.LENGTH_SHORT).show();
                    return;
                }
                ServerService.subscribe(this);
                startForegroundService(new Intent(this, ServerService.class));
                refresh();
            }
        });

        btnChat.setOnClickListener(v ->
                startActivity(new Intent(this, ChatActivity.class)));

        findViewById(R.id.btnSessions).setOnClickListener(v ->
                startActivity(new Intent(this, SessionsActivity.class)));

        findViewById(R.id.btnSandboxInstall).setOnClickListener(v -> installSandbox());
        findViewById(R.id.btnSandboxTools).setOnClickListener(v -> installTools());

        ServerService.subscribe(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeRunWizard();
        refresh();
    }

    @Override
    protected void onDestroy() {
        ServerService.unsubscribe(this);
        super.onDestroy();
    }

    // -------------------------------------------------------------- wizard

    /** Runs the automatic part of the setup; idempotent, safe every resume. */
    private void maybeRunWizard() {
        if (!Binaries.binaryReady(this)) {
            autoExtract();
            return; // nothing else can run yet
        }
        // binary ready → auto-start server (once per process)
        autoStartServer();
        // healthy + key → auto-open chat (once per app open)
        if (ServerService.getState() == ServerService.ST_HEALTHY
                && AuthStore.hasAnyKey(this) && autoChatArmed) {
            autoChatArmed = false;
            startActivity(new Intent(this, ChatActivity.class));
        }
        // sandbox: auto-install once, ever (bundled ~6 MB, needs no network)
        if (ServerService.getState() == ServerService.ST_HEALTHY
                && !Sandboxes.installed(this)
                && prefs().getBoolean("auto_sandbox_done", false) == false
                && !sandboxAutoDone) {
            sandboxAutoDone = true;
            prefs().edit().putBoolean("auto_sandbox_done", true).apply();
            installSandbox();
        }
    }

    private static volatile boolean autoChatArmed = true;

    private void autoExtract() {
        if (extracting) return;
        if (prefs().getBoolean("extract_done", false) && Binaries.binaryReady(this)) return;
        // If the user imported a broken file, extraction would wipe it — only
        // auto-extract when there is no binary file at all.
        if (Binaries.binaryFile(this).exists()) return;
        extracting = true;
        tvBinaryInfo.setText("unpacking the agent (one-time, ~175 MB)…");
        new Thread(() -> {
            try {
                Binaries.extractBundled(this, msg -> runOnUiThread(() -> {
                    if (extracting) tvBinaryInfo.setText("unpacking agent… " + msg);
                }));
                String ver = Binaries.probeVersion(this, Binaries.binaryFile(this));
                prefs().edit()
                        .putBoolean("extract_done", true)
                        .putString("bin_ver", ver == null ? "1.18.25" : ver)
                        .apply();
                runOnUiThread(() -> {
                    extracting = false;
                    refresh();
                    maybeRunWizard();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    extracting = false;
                    tvBinaryInfo.setText("unpack failed: " + e.getMessage()
                            + " — use “Import opencode binary” below");
                    refresh();
                });
            }
        }, "extract-bundled").start();
    }

    private void autoStartServer() {
        int st = ServerService.getState();
        boolean runningish = st == ServerService.ST_STARTING || st == ServerService.ST_HEALTHY;
        if (runningish) return;
        if (st == ServerService.ST_EXITED && prefs().getBoolean("auto_started_once", false)) {
            return; // a real failure — let the user read it and start manually
        }
        if (!prefs().getBoolean("auto_started_once", false)) {
            prefs().edit().putBoolean("auto_started_once", true).apply();
            ServerService.subscribe(this);
            startForegroundService(new Intent(this, ServerService.class));
        }
    }

    // ---- ServerService.Evt ----
    @Override
    public void on(int newState, String detail) {
        runOnUiThread(() -> {
            refresh();
            maybeRunWizard();
        });
    }

    private void pick(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_BIN) importBinary(uri);
        else if (req == REQ_AUTH) importJson(uri, Binaries.authFile(this), "auth.json");
        else if (req == REQ_CFG) importJson(uri, Binaries.configFile(this), "opencode.json");
    }

    private void importBinary(Uri uri) {
        File bin = Binaries.binaryFile(this);
        toast("Copying binary…");
        new Thread(() -> {
            try {
                long n = Binaries.copyFromUri(this, uri, bin);
                if (!Binaries.isElf(bin)) {
                    bin.delete();
                    uiToast("NOT an ELF — pick the extracted 'opencode' file (~175 MB),"
                            + " or reinstall the app to re-enable bundled unpacking");
                    runOnUiThread(this::refresh);
                    return;
                }
                Binaries.makeExec(bin);
                String ver = Binaries.probeVersion(this, bin);
                prefs().edit()
                        .putBoolean("extract_done", true)
                        .putLong("bin_size", n)
                        .putString("bin_ver", ver == null ? "?" : ver)
                        .apply();
                runOnUiThread(() -> { refresh(); maybeRunWizard(); });
                uiToast("Imported: opencode " + (ver == null ? "(version probe failed)" : ver));
            } catch (Exception e) {
                uiToast("Import failed: " + e.getMessage());
            }
        }, "import-bin").start();
    }

    private void importJson(Uri uri, File dst, String name) {
        try {
            long n = Binaries.copyFromUri(this, uri, dst);
            toast(name + " imported (" + Binaries.human(n) + ")");
            if (name.startsWith("auth")) ServerService.restart(this);
            refresh();
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- sandbox

    private void installSandbox() {
        requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        toast("Installing sandbox…");
        new Thread(() -> {
            try {
                Sandboxes.install(this, msg -> runOnUiThread(() -> tvSandbox.setText(msg)));
                runOnUiThread(this::refresh);
                uiToast("Sandbox ready — the agent can now run Linux commands");
            } catch (Exception e) {
                uiToast("Sandbox install failed: " + e.getMessage());
            }
        }, "sandbox-install").start();
    }

    private void installTools() {
        if (!Sandboxes.installed(this)) {
            toast("Install the sandbox first");
            return;
        }
        toast("Installing tools (needs network, a few minutes)…");
        new Thread(() -> {
            try {
                Sandboxes.installTools(this, msg -> runOnUiThread(() -> tvSandbox.setText(msg)));
                runOnUiThread(this::refresh);
                uiToast("Tools installed: " + Sandboxes.TOOLS_LIST);
            } catch (Exception e) {
                uiToast("Tools install failed: " + e.getMessage());
            }
        }, "sandbox-tools").start();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("state", MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ ui

    private void refresh() {
        int st = ServerService.getState();
        String s;
        switch (st) {
            case ServerService.ST_HEALTHY:  s = "● running · 127.0.0.1:" + Api.PORT; break;
            case ServerService.ST_STARTING: s = "◌ starting…"; break;
            case ServerService.ST_EXITED:   s = "✕ exited — " + ServerService.getTail().trim(); break;
            case ServerService.ST_STOPPED:  s = "○ stopped"; break;
            default:                        s = "○ idle"; break;
        }
        tvServerState.setText(s);

        boolean binOk = Binaries.binaryReady(this);
        if (extracting) {
            // progress text is set by the extraction thread
        } else if (binOk) {
            String ver = prefs().getString("bin_ver", "1.18.25");
            tvBinaryInfo.setText("opencode " + ver + " · " + Binaries.human(
                    Binaries.binaryFile(this).length()) + " · ready");
        } else if (Binaries.binaryFile(this).exists()) {
            tvBinaryInfo.setText("imported file is not a valid ELF binary");
        } else {
            tvBinaryInfo.setText("no binary yet");
        }

        tvEndpoint.setText("GET " + Api.baseUrl() + "/project · SSE /event");

        btnServer.setEnabled(binOk);
        btnServer.setText(st == ServerService.ST_STARTING || st == ServerService.ST_HEALTHY
                ? "Stop server" : "Start server");
        btnChat.setEnabled(st == ServerService.ST_HEALTHY);

        boolean hasKey = AuthStore.hasAnyKey(this);
        int nKeys = AuthStore.readAuth(this).size();
        tvAuthStatus.setText(hasKey
                ? "✓ " + nKeys + " provider" + (nKeys == 1 ? "" : "s") + " connected"
                : "no API keys yet — needed before the agent can answer");
        btnConnect.setText(hasKey ? "API keys & providers" : "Connect API keys — required");
        btnConnect.setBackgroundResource(hasKey
                ? R.drawable.bg_btn_secondary : R.drawable.bg_btn_primary);
        btnConnect.setTextColor(getResources().getColor(hasKey
                ? R.color.text_primary : R.color.on_accent));

        tvSandbox.setText(Sandboxes.status(this));

        // wizard step line
        if (!binOk) {
            tvStep.setText("Step 1/3 — unpacking the agent…");
        } else if (!hasKey) {
            tvStep.setText("Step 2/3 — connect your API keys (tap below)");
        } else if (st != ServerService.ST_HEALTHY) {
            tvStep.setText("Step 3/3 — starting the server…");
        } else {
            tvStep.setText("✓ ready — open chat, pick a model, talk to your agent");
        }
        tvHint.setText("Advanced: import a custom binary / auth.json / opencode.json."
                + " Keys entered in “Connect API keys” are stored in the app's"
                + " private opencode home — no desktop files needed.");
    }

    private void uiToast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}
