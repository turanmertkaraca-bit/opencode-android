package ai.opencode.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P35 — the offscreen render half of the agent's eyes. One page at a
 * time, never on screen, never auto-opened: a headless WebView (the same
 * engine the canvas viewer uses) loads the page with JS on, storage on,
 * and file + network access OFF — a self-contained page renders exactly
 * as it will in the canvas, and anything external is refused and REPORTED
 * instead of silently failing. Console lines, a DOM outline probe and a
 * software-render screenshot feed the JSON report; the optional visual
 * description walks the existing free vision ladder (keyless).
 *
 * Containment is the house rule: one busy gate, one hard watchdog, the
 * render process dying is a report line (never a crash), and every path
 * — including failure — destroys the WebView, clears the gate and calls
 * the callback exactly once.
 */
public final class HtmlRenderer {

    /** Called exactly once with the report (or an ok:false error body). */
    public interface Cb { void done(Map<String, Object> report); }

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final int SETTLE_MS = 700;      // let rAF/JS init land
    private static final int TIMEOUT_MS = 20_000;  // hard cap per render
    private static final int SHOT_W = 1024;
    private static final int SHOT_H = 1920;

    private HtmlRenderer() {}

    /** True while a render is in flight (the server answers busy). */
    public static boolean busy() { return BUSY.get(); }

    /** Render one page offscreen; cb fires exactly once, ≤ ~25 s later. */
    public static void render(final Context ctx, final File page,
                              final boolean describe, final Cb cb) {
        if (!BUSY.compareAndSet(false, true)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "busy");
            m.put("detail", "a render is already running — try again shortly");
            cb.done(m);
            return;
        }
        final byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(page.toPath());
        } catch (Exception e) {
            BUSY.set(false);
            fail(cb, "read-failed", String.valueOf(e));
            return;
        }
        H.post(() -> start(ctx.getApplicationContext(), bytes, page, describe, cb));
    }

    // ------------------------------------------------- the job

    private static final class Job {
        final Context ctx;
        final byte[] bytes;
        final File page;
        final boolean describe;
        final Cb cb;
        final List<String> console = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final boolean[] extBlocked = {false};
        final boolean[] finished = {false};
        final Runnable[] watchdog = new Runnable[1];
        WebView wv;
        Map<String, Object> outline;
        Job(Context c, byte[] b, File p, boolean d, Cb call) {
            ctx = c; bytes = b; page = p; describe = d; cb = call;
        }
    }

    private static void start(final Context ctx, final byte[] bytes,
                              final File page, final boolean describe,
                              final Cb cb) {
        final Job j = new Job(ctx, bytes, page, describe, cb);
        try {
            WebView wv = new WebView(ctx);
            j.wv = wv;
            WebSettings st = wv.getSettings();
            st.setJavaScriptEnabled(true);
            st.setDomStorageEnabled(true);
            st.setAllowFileAccess(false);
            st.setAllowContentAccess(false);
            st.setBlockNetworkLoads(true);   // self-contained pages only
            // software layer so the unattached view can paint for the shot
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            wv.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onConsoleMessage(ConsoleMessage m) {
                    String sev = m != null
                            && m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                            ? "error" : "warn";
                    RenderCheck.addLine(j.console, "[" + sev + "] "
                            + RenderCheck.capOne(m == null ? "" : m.message())
                            + " (line " + (m == null ? 0 : m.lineNumber()) + ")");
                    return true;
                }
            });
            wv.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v,
                                                                  String url) {
                    j.extBlocked[0] = true;
                    RenderCheck.addLine(j.notes, "external load refused: "
                            + RenderCheck.capOne(url == null ? "" : url));
                    return true;
                }
                @Override public void onPageFinished(WebView v, String url) {
                    H.postDelayed(() -> probe(j), SETTLE_MS);
                }
                // a dead render process is caught by the hard watchdog —
                // the report completes with timedOut + whatever was captured
            });
            j.watchdog[0] = () -> { if (!j.finished[0]) done(j, true); };
            H.postDelayed(j.watchdog[0], TIMEOUT_MS);
            wv.measure(View.MeasureSpec.makeMeasureSpec(SHOT_W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(SHOT_H, View.MeasureSpec.AT_MOST));
            wv.layout(0, 0, SHOT_W, SHOT_H);
            wv.loadDataWithBaseURL("http://localhost/",
                    new String(j.bytes, StandardCharsets.UTF_8),
                    "text/html", "utf-8", null);
        } catch (Throwable t) {
            RenderCheck.addLine(j.notes, "renderer failed to start: contained");
            done(j, false);
        }
    }

    /** The DOM outline probe — runs once the page settled. */
    private static void probe(final Job j) {
        if (j.finished[0]) return;
        try {
            j.wv.evaluateJavascript(OUTLINE_JS, (res) -> {
                if (j.finished[0]) return;
                try {
                    Object o = Json.parse(res == null ? "" : res);
                    if (o instanceof String) o = Json.parse((String) o);
                    Map<String, Object> m = Json.obj(o);
                    j.outline = RenderCheck.shapeOutline(m);
                } catch (Throwable t) {
                    j.outline = RenderCheck.shapeOutline(null);
                }
                done(j, false);
            });
        } catch (Throwable t) {
            j.outline = RenderCheck.shapeOutline(null);
            done(j, false);
        }
    }

    /** The single exit: report, teardown, gate clear, callback once. */
    private static void done(final Job j, final boolean timedOut) {
        if (j.finished[0]) return;
        j.finished[0] = true;
        H.removeCallbacks(j.watchdog[0]);
        try {
            if (j.extBlocked[0])
                RenderCheck.addLine(j.notes,
                        "page tried external resources — self-contained pages only");
            String look = null;
            Bitmap shot = shot(j.wv);
            if (shot != null && j.describe) look = describe(j.ctx, shot, j.notes);
            if (shot != null) shot.recycle();
            if (shot == null)
                RenderCheck.addLine(j.notes,
                        "screenshot unavailable — console and DOM outline still apply");
            Map<String, Object> rep = RenderCheck.report(
                    RenderCheck.verdict(j.console),
                    RenderCheck.capLines(j.console),
                    j.outline == null ? RenderCheck.shapeOutline(null) : j.outline,
                    RenderCheck.capLines(j.notes),
                    look, timedOut);
            j.cb.done(rep);
        } catch (Throwable t) {
            fail(j.cb, "internal", String.valueOf(t));
        } finally {
            try { if (j.wv != null) j.wv.destroy(); } catch (Throwable ignored) {}
            BUSY.set(false);
        }
    }

    private static void fail(Cb cb, String code, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", code);
        m.put("detail", RenderCheck.capOne(detail == null ? "" : detail));
        cb.done(m);
    }

    /** Software paint of the settled page — null when it will not paint. */
    private static Bitmap shot(WebView wv) {
        try {
            int w = wv.getWidth() > 0 ? wv.getWidth() : SHOT_W;
            int h = wv.getHeight() > 0 ? wv.getHeight() : SHOT_H;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            wv.draw(c);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** One-paragraph look via the free vision ladder (keyless). */
    private static String describe(Context ctx, Bitmap shot, List<String> notes) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            shot.compress(Bitmap.CompressFormat.JPEG, 70, bo);
            byte[] jpg = bo.toByteArray();
            IOException last = null;
            for (int i = 0; i < Vision.CANDIDATES.length; i++) {
                String[] m = Vision.modelAt(i);
                try {
                    return RenderCheck.capOne(Vision.describe(m[1],
                            RenderCheck.LOOK_PROMPT, jpg,
                            Vision.zenKey(ctx), 45_000));
                } catch (IOException e2) {
                    last = e2;   // rotate to the next free model
                }
            }
            RenderCheck.addLine(notes,
                    "visual description unavailable (no vision model answered"
                    + (last == null ? "" : ": " + last.getMessage()) + ")");
            return null;
        } catch (Throwable t) {
            RenderCheck.addLine(notes, "visual description failed: contained");
            return null;
        }
    }

    /** The in-page DOM probe: counts, geometry, overflow, headings. */
    private static final String OUTLINE_JS =
            "(function(){var q=function(s){return document.querySelectorAll(s).length};"
            + "var hs=[].slice.call(document.querySelectorAll('h1,h2,h3'),0,8)"
            + ".map(function(e){return (e.tagName+': '+(e.textContent||'').trim())"
            + ".slice(0,120)});"
            + "var b=document.body;"
            + "var r={title:(document.title||'').slice(0,160),"
            + "elements:q('*'),"
            + "buttons:q('button,input[type=button],input[type=submit],[role=button]'),"
            + "links:q('a'),inputs:q('input,textarea,select'),"
            + "canvases:q('canvas'),svgs:q('svg'),"
            + "textLen:(b?(b.innerText||'').length:0),"
            + "scrollW:(b?b.scrollWidth:0),scrollH:(b?b.scrollHeight:0),"
            + "overflowX:(b?b.scrollWidth>window.innerWidth+8:false),"
            + "viewportW:window.innerWidth,viewportH:window.innerHeight,"
            + "headings:hs};"
            + "return JSON.stringify(r);})()";
}
