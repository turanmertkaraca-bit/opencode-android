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
 * P1 skeleton main screen: import the binary, import credentials, drive the
 * in-process server, open chat. Pixel-dark, minimalist, zero dependencies.
 */
public class MainActivity extends Activity implements ServerService.Evt {

    private static final int REQ_BIN = 1, REQ_AUTH = 2, REQ_CFG = 3;

    private TextView tvServerState, tvBinaryInfo, tvEndpoint, tvHint, tvSandbox;
    private Button btnServer, btnChat;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        tvServerState = findViewById(R.id.tvServerState);
        tvBinaryInfo  = findViewById(R.id.tvBinaryInfo);
        tvEndpoint    = findViewById(R.id.tvEndpoint);
        tvHint        = findViewById(R.id.tvHint);
        tvSandbox     = findViewById(R.id.tvSandbox);
        btnServer     = findViewById(R.id.btnServer);
        btnChat       = findViewById(R.id.btnChat);

        findViewById(R.id.btnImportBin).setOnClickListener(v -> pick(REQ_BIN, "application/octet-stream"));
        findViewById(R.id.btnImportAuth).setOnClickListener(v -> pick(REQ_AUTH, "application/json"));
        findViewById(R.id.btnImportCfg).setOnClickListener(v -> pick(REQ_CFG, "application/json"));

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
                File bin = Binaries.binaryFile(this);
                if (!bin.exists() || !Binaries.isElf(bin)) {
                    Toast.makeText(this, "Import the opencode binary first", Toast.LENGTH_LONG).show();
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
        refresh();
    }

    @Override
    protected void onDestroy() {
        ServerService.unsubscribe(this);
        super.onDestroy();
    }

    // ---- ServerService.Evt ----
    @Override
    public void on(int newState, String detail) {
        runOnUiThread(this::refresh);
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
                    uiToast("NOT an ELF — that looks like the tar.gz. Pick the extracted 'opencode' file (~175 MB)");
                    return;
                }
                Binaries.makeExec(bin);
                String sha = Binaries.sha256(bin);
                String ver = Binaries.probeVersion(this, bin);
                prefs().edit()
                        .putString("bin_sha", sha)
                        .putLong("bin_size", n)
                        .putString("bin_ver", ver == null ? "?" : ver)
                        .apply();
                runOnUiThread(this::refresh);
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
        toast("Installing sandbox\u2026");
        new Thread(() -> {
            try {
                Sandboxes.install(this, msg -> runOnUiThread(() -> tvSandbox.setText(msg)));
                runOnUiThread(this::refresh);
                uiToast("Sandbox ready \u2014 stop & start the server so the agent uses it");
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
        toast("Installing tools (needs network, a few minutes)\u2026");
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

        File bin = Binaries.binaryFile(this);
        if (bin.exists() && Binaries.isElf(bin)) {
            String ver = prefs().getString("bin_ver", "?");
            tvBinaryInfo.setText("opencode " + ver
                    + " · " + Binaries.human(bin.length())
                    + " · sha256 " + Binaries.sha256(bin) + "…");
            btnServer.setEnabled(true);
            btnServer.setText(st == ServerService.ST_STARTING || st == ServerService.ST_HEALTHY
                    ? "Stop server" : "Start server");
        } else if (bin.exists()) {
            tvBinaryInfo.setText("imported file is not an ELF binary");
            btnServer.setEnabled(false);
        } else {
            tvBinaryInfo.setText("no binary imported");
            btnServer.setEnabled(false);
            btnServer.setText("Start server");
        }

        tvEndpoint.setText("GET " + Api.baseUrl() + "/project · SSE /event");

        btnChat.setEnabled(st == ServerService.ST_HEALTHY);

        tvSandbox.setText(Sandboxes.status(this));
        tvHint.setText("Get files from your desktop machine:\n"
                + "• opencode-linux-arm64-android.tar.gz → extract → pick the 175 MB 'opencode' file\n"
                + "• ~/.local/share/opencode/auth.json → provider credentials\n"
                + "• ~/.config/opencode/opencode.json → config (default model)");
    }

    private void uiToast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}
