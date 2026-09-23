package ai.opencode.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * P53 — the storage manager screen. The field: "storage manager i need
 * one because the app gains weight too fast like 8gb is crazy".
 *
 * Shows a categorized breakdown (projects, sandbox layers, caches, logs,
 * sessions, exports) with the big total on top, and lets the user safely
 * reclaim the clearable parts. Every clear goes through Storage.clear(),
 * whose safety contract refuses project source, git repos, session stores
 * and keys.
 *
 * The scan walks real trees (tens of thousands of files in a grown
 * Debian rootfs), so it ALWAYS runs on a worker thread and posts the
 * result back — the P49 "Settings lags / black screen / ANR" lesson,
 * applied from the first frame.
 */
public class StorageActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout listCol;
    private TextView totalTxt, statusTxt;
    private volatile boolean scanning;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        Theme.window(this);              // palette owns the window + dialogs
    }

    @Override
    protected void onResume() {
        super.onResume();
        Theme.syncIfNeeded(this);        // a theme switch elsewhere re-skins here
        reload();
    }

    // ------------------------------------------------------------- ui

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.bg_home);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(this, 18);
        root.setPadding(pad, Theme.dp(this, 14), pad, Theme.dp(this, 48));
        scroll.addView(root);

        TextView h1 = new TextView(this);
        h1.setText("Storage");
        h1.setTextSize(27);
        h1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h1.setTextColor(Theme.TXT);
        root.addView(h1);
        TextView h2 = new TextView(this);
        h2.setText("what is using space · clear the safe parts");
        h2.setTextSize(12);
        h2.setTextColor(Theme.TXT_DIM);
        h2.setPadding(0, Theme.dp(this, 2), 0, Theme.dp(this, 10));
        root.addView(h2);

        totalTxt = new TextView(this);
        totalTxt.setText("App storage: …");
        totalTxt.setTextSize(30);
        totalTxt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        totalTxt.setTextColor(Theme.TXT);
        root.addView(totalTxt);

        statusTxt = new TextView(this);
        statusTxt.setText("calculating…");
        statusTxt.setTextSize(12);
        statusTxt.setTextColor(Theme.TXT_DIM);
        statusTxt.setPadding(0, Theme.dp(this, 4), 0, Theme.dp(this, 12));
        root.addView(statusTxt);

        TextView clearAll = new TextView(this);
        clearAll.setText("Clear all caches & logs");
        clearAll.setTextSize(14);
        clearAll.setTypeface(Typeface.DEFAULT_BOLD);
        clearAll.setTextColor(Theme.ON_ACCENT);
        clearAll.setGravity(Gravity.CENTER);
        clearAll.setBackground(Theme.allowPill(this));
        clearAll.setPadding(Theme.dp(this, 18), Theme.dp(this, 13),
                Theme.dp(this, 18), Theme.dp(this, 13));
        Theme.press(clearAll);
        clearAll.setOnClickListener(v -> {
            Theme.haptic(clearAll);
            confirmClearAll();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Theme.dp(this, 14);
        root.addView(clearAll, clp);

        listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listCol.setBackground(Theme.panel(this));
        root.addView(listCol);
        return scroll;
    }

    // ---------------------------------------------------------- scanning

    private void reload() {
        if (dead() || scanning) return;
        scanning = true;
        if (statusTxt != null) statusTxt.setText("calculating…");
        new Thread(() -> {
            List<Storage.Item> items;
            long total;
            try {
                items = Storage.scan(StorageActivity.this);
                total = Storage.total(items);
            } catch (Throwable t) {
                items = new ArrayList<>();
                total = 0;
            }
            final List<Storage.Item> fi = items;
            final long ft = total;
            ui.post(() -> {
                scanning = false;
                if (dead()) return;
                render(fi, ft);
            });
        }, "oc-storage-scan").start();
    }

    private void render(List<Storage.Item> items, long total) {
        if (listCol == null) return;
        listCol.removeAllViews();
        totalTxt.setText("App storage: " + Storage.human(total));
        if (items == null || items.isEmpty()) {
            statusTxt.setText("nothing measured — the app data is empty or the scan was blocked");
            return;
        }
        statusTxt.setText(items.size() + " areas · largest first");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) listCol.addView(divider());
            listCol.addView(itemRow(items.get(i)));
        }
    }

    private View itemRow(final Storage.Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setBackground(Theme.ripple(this, null));
        row.setPadding(Theme.dp(this, 14), Theme.dp(this, 11),
                Theme.dp(this, 14), Theme.dp(this, 11));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(it.label);
        t.setTextSize(14);
        t.setTextColor(Theme.TXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        TextView p = new TextView(this);
        p.setText(it.path == null ? "" : it.path.getAbsolutePath());
        p.setTextSize(10);
        p.setTextColor(Theme.TXT_FAINT);
        p.setSingleLine(true);
        p.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(p);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView sz = new TextView(this);
        sz.setText(Storage.human(it.bytes));
        sz.setTextSize(13);
        sz.setTypeface(Typeface.DEFAULT_BOLD);
        sz.setTextColor(it.clearable ? Theme.ACCENT_LT : Theme.TXT_DIM);
        sz.setPadding(Theme.dp(this, 10), 0, 0, 0);
        row.addView(sz);

        if (it.clearable) {
            TextView clr = new TextView(this);
            clr.setText("Clear");
            clr.setTextSize(12);
            clr.setTypeface(Typeface.DEFAULT_BOLD);
            clr.setTextColor(Theme.ACCENT_LT);
            clr.setGravity(Gravity.CENTER);
            clr.setBackground(Theme.outlinePill(this));
            clr.setPadding(Theme.dp(this, 14), Theme.dp(this, 7),
                    Theme.dp(this, 14), Theme.dp(this, 7));
            Theme.press(clr);
            clr.setOnClickListener(v -> {
                Theme.haptic(clr);
                confirmClear(it);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = Theme.dp(this, 10);
            row.addView(clr, clp);
        }

        Theme.press(row);
        row.setOnClickListener(v -> showDetail(it));
        return row;
    }

    // ---------------------------------------------------------- clearing

    private void showDetail(final Storage.Item it) {
        Sheet sh = Sheet.show(this, it.label);
        if (!sh.showing()) return;
        sh.sub(Storage.human(it.bytes) + " · " + kindName(it.kind));
        if (it.path != null) sh.msg(it.path.getAbsolutePath());
        if (!it.clearable) {
            sh.msg("Kept safe — project source, git repos, chats, keys and "
                    + "settings are never deleted by the storage manager.");
        } else if (it.kind == Storage.Kind.SANDBOX) {
            sh.msg("Clears only caches and temp files inside the sandbox "
                    + "(apt/npm caches, tmp). The rootfs and installed "
                    + "packages stay.");
        }
        if (it.clearable) {
            final String label = it.clearLabel == null ? "Clear" : it.clearLabel;
            sh.pill(label, Sheet.DANGER, () -> doClear(it));
        }
        sh.pill("Close", Sheet.QUIET, null);
    }

    private void confirmClear(final Storage.Item it) {
        String body = it.kind == Storage.Kind.SANDBOX
                ? "Removes only caches and temp files inside the sandbox. "
                  + "The rootfs and installed packages stay."
                : "Deletes " + (it.path == null ? "" : it.path.getAbsolutePath())
                  + " (" + Storage.human(it.bytes) + ").";
        Sheet.show(this, "Clear " + it.label + "?")
                .msg(body)
                .pill(it.clearLabel == null ? "Clear" : it.clearLabel,
                        Sheet.DANGER, () -> doClear(it))
                .pill("Cancel", Sheet.QUIET, null);
    }

    private void confirmClearAll() {
        Sheet.show(this, "Clear all caches & logs?")
                .msg("Deletes app caches, logs, temp files and exports. "
                        + "Project source, git repos, chats, API keys and "
                        + "settings are never touched.")
                .pill("Clear all", Sheet.DANGER, this::clearAll)
                .pill("Cancel", Sheet.QUIET, null);
    }

    private void doClear(final Storage.Item it) {
        if (statusTxt != null) statusTxt.setText("clearing…");
        new Thread(() -> {
            final long freed = Storage.clear(StorageActivity.this, it);
            ui.post(() -> {
                if (dead()) return;
                Toast.makeText(this, freed > 0
                                ? "reclaimed " + Storage.human(freed)
                                : "nothing to clear",
                        Toast.LENGTH_SHORT).show();
                reload();
            });
        }, "oc-storage-clear").start();
    }

    private void clearAll() {
        if (statusTxt != null) statusTxt.setText("clearing…");
        new Thread(() -> {
            long freed = 0;
            try {
                List<Storage.Item> items = Storage.scan(StorageActivity.this);
                for (Storage.Item it : items) {
                    if (it == null || !it.clearable) continue;
                    if (it.kind == Storage.Kind.CACHE
                            || it.kind == Storage.Kind.LOG
                            || it.kind == Storage.Kind.EXPORT) {
                        freed += Storage.clear(StorageActivity.this, it);
                    }
                }
            } catch (Throwable ignored) {}
            final long f = freed;
            ui.post(() -> {
                if (dead()) return;
                Toast.makeText(this, "reclaimed " + Storage.human(f),
                        Toast.LENGTH_LONG).show();
                reload();
            });
        }, "oc-storage-clear-all").start();
    }

    // ----------------------------------------------------------- helpers

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Theme.STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = Theme.dp(this, 14);
        v.setLayoutParams(lp);
        return v;
    }

    private static String kindName(Storage.Kind k) {
        if (k == null) return "other";
        switch (k) {
            case PROJECT: return "project";
            case SANDBOX: return "sandbox";
            case CACHE:   return "cache";
            case LOG:     return "log";
            case SESSION: return "sessions";
            case EXPORT:  return "export";
            default:      return "other";
        }
    }

    private boolean dead() {
        return isFinishing() || isDestroyed();
    }
}
