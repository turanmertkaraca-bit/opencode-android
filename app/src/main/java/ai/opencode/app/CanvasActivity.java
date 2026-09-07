package ai.opencode.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;

/**
 * P31 — the interactive canvas. A full-screen sandboxed viewer for the
 * self-contained HTML pages the agent writes when the user asks for an
 * interactive explanation (⌘ → "✦ Interactive canvas…", or the ▶ chip
 * on a tool card that produced an .html file, or Files → open page).
 *
 * Sandbox posture (the security story, in one place):
 *   • JavaScript ON and DOM storage ON — the pages are interactive,
 *     that is the entire point;
 *   • file access OFF, content access OFF — the page cannot read the
 *     device, other files, or the sandbox through the WebView;
 *   • the HTML is loaded as a STRING (no file:// URL), so the page has
 *     no origin to reach back through;
 *   • nothing auto-opens: a page renders only after an explicit tap.
 *
 * Zero dependencies — a plain WebView under the app's own top bar.
 */
public class CanvasActivity extends Activity {

    private WebView web;
    private String html;
    private String sourceName = "page";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.window(this);                  // palette follows the app

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);

        // ---- top bar (matches the chat header's rhythm)
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = Theme.dp(this, 14);
        bar.setPadding(pad, Theme.dp(this, 10), pad, Theme.dp(this, 10));
        bar.setBackgroundColor(Theme.SURFACE);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(20);
        back.setTextColor(Theme.TXT);
        back.setPadding(0, 0, Theme.dp(this, 14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("▶ Interactive canvas");
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.TXT);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reload = new TextView(this);
        reload.setText("⟳");
        reload.setTextSize(16);
        reload.setTextColor(Theme.ACCENT_LT);
        reload.setTypeface(android.graphics.Typeface.MONOSPACE);
        reload.setPadding(Theme.dp(this, 10), Theme.dp(this, 2),
                Theme.dp(this, 10), Theme.dp(this, 4));
        reload.setBackgroundResource(R.drawable.bg_chip);
        Theme.press(reload);
        reload.setOnClickListener(v -> {
            Theme.haptic(v);
            if (html != null) {
                loadHtml();
                Toast.makeText(this, "reloaded", Toast.LENGTH_SHORT).show();
            }
        });
        bar.addView(reload);
        root.addView(bar);
        View hair = new View(this);
        hair.setBackgroundColor(Theme.STROKE);
        hair.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(hair);

        // ---- error slot (file problems render here, never a crash)
        TextView err = new TextView(this);
        err.setTextSize(14);
        err.setTextColor(Theme.WARN);
        err.setPadding(pad, pad, pad, pad);
        err.setVisibility(View.GONE);

        // ---- the viewer
        web = new WebView(this);
        web.setBackgroundColor(Theme.BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);        // the whole point
        s.setDomStorageEnabled(true);        // page-local state (sliders etc.)
        s.setAllowFileAccess(false);         // no device files
        s.setAllowContentAccess(false);      // no content providers
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(
                    WebView v, String url) {
                // a self-contained page has no business navigating away;
                // external links are refused with one honest toast
                if (url != null && (url.startsWith("http://")
                        || url.startsWith("https://"))) {
                    Toast.makeText(CanvasActivity.this,
                            "the page tried to open " + url
                                    + " — external links stay inside the sandbox",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // ---- resolve the source: a file path (tool card / Files) or
        // inline html (future callers). One honest error line on failure.
        String path = getIntent() == null ? null : getIntent().getStringExtra("path");
        if (path != null) {
            File f = new File(path);
            sourceName = f.getName();
            title.setText("▶ " + sourceName);
            String guard = CanvasDoc.readGuard(f);
            if (guard != null) {
                showLoadError(root, err, guard);
                return;
            }
            try (FileInputStream in = new FileInputStream(f)) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                html = o.toString("UTF-8");
            } catch (Exception e) {
                showLoadError(root, err, "could not read the page: " + e.getMessage());
                return;
            }
        } else {
            html = getIntent() == null ? null : getIntent().getStringExtra("html");
        }
        if (html == null || html.trim().isEmpty()) {
            showLoadError(root, err, "the page is empty");
            return;
        }
        loadHtml();
    }

    private void loadHtml() {
        try {
            // null base URL → the page gets no origin to fetch from;
            // scripts/styles must be inline (the prompt enforces this)
            web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        } catch (Exception e) {
            Toast.makeText(this, "render failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showLoadError(LinearLayout root, TextView err, String msg) {
        err.setText("⚠ " + msg);
        err.setVisibility(View.VISIBLE);
        root.addView(err, root.getChildCount());
        if (web != null) web.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // in-page history (anchor jumps) unwinds before the activity
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }
}
