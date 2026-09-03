package ai.opencode.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P7 — the whole app in one screen. Chat-first like the opencode TUI:
 *
 *   ⌘ palette        = the TUI's Ctrl+P (sessions, model, agent, keys, …)
 *   Build/Plan chip  = the TUI's Tab
 *   ✦ Thinking cards = reasoning parts, COLLAPSED by default, tap to open
 *   tool cards       = tool parts, COLLAPSED by default, live status dots,
 *                      expand to input/output (+patch file lists)
 *   permission card  = pinned above the composer, Allow once/Always/Deny
 *   model chip       = EVERY provider/model from /config/providers, search
 *
 * No wizard, no setup cards, no gated screens. If the server is down the
 * transcript keeps working and the status line says what is wrong; ⌘ →
 * Restart server fixes it. Every render path is try/caught — a bad part
 * must degrade to a plain line, never crash (the P6 device crash lesson).
 */
public class ChatActivity extends Activity
        implements ServerService.Evt, ServerService.EventListener {

    // row kinds
    static final int K_USER = 0, K_ASSISTANT = 1, K_REASON = 2, K_TOOL = 3, K_SYS = 4, K_ERR = 5;

    // P8: which project sandbox this chat is attached to (from the deck)
    private String projectName;

    static final class Row {
        int kind;
        String key;                 // stable identity for SSE upserts
        StringBuffer text = new StringBuffer();
        int shown;                  // P9: chars painted so far (streaming caret)
        String tool, status, title, meta;
        StringBuffer input = new StringBuffer();
        StringBuffer output = new StringBuffer();
        boolean open;               // collapsed by default for tool/reason
        long ts;
    }

    static final class MsgInfo {
        String role;
        String meta;                // "⇅ 12.3k tok · $0.0041"
        double cost;                // P12 session-total accumulation
        long tok;
        boolean errorShown;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    /**
     * P10 — was a single-thread executor, which was a deadlock in disguise:
     * POST /session/{id}/message holds its thread until the agent run
     * finishes, and a permission ask arrives exactly MID-RUN — so the
     * Allow/Always/Deny reply (and the abort call) queued behind it and
     * never ran. Buttons "didn't work". A pool lets replies/abort/history
     * run while a message POST is in flight.
     */
    private final ExecutorService ex = Executors.newCachedThreadPool();
    /** Dedicated pool for permission replies — must NEVER wait on anything. */
    private final ExecutorService permEx = Executors.newCachedThreadPool();
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, MsgInfo> msgs = new HashMap<>();
    private final Map<String, Integer> idxByKey = new HashMap<>();
    private final Map<String, Integer> typeCount = new HashMap<>();

    private LinearLayout list;
    private ScrollView scroll;
    private TextView tvTitle, tvSub, tvStatus, chipMode, chipModel, btnSend;
    private TextView tvSpend;               // P14: dedicated session-spend pill
    private EditText input;
    private LinearLayout permSlot;
    // P9: hero empty state + in-place view cache for smooth streaming
    private LinearLayout emptyHero, suggestBox;
    private TextView btnSessions;
    private final Map<String, View> viewByKey = new HashMap<>();
    private final Map<String, TextView> bodyByKey = new HashMap<>();
    private final Map<String, TextView> metaByKey = new HashMap<>();
    private final Handler smoother = new Handler(Looper.getMainLooper());
    private boolean smootherRunning;
    // P8 polish
    private View veil;
    private LinearLayout typing;
    private TextView scrollPill;
    private boolean pillShown;
    private final List<ObjectAnimator> typingAnims = new ArrayList<>();

    private String sessionId, sessionTitle;
    private String agent = "build";     // Tab parity: build <-> plan
    private volatile boolean busy;
    private volatile long lastPartTs;
    // P11 self-heal state: the zen free-model lineup ROTATES server-side, so
    // a saved pick can go stale ("Model not found" on EVERY send — the exact
    // user report). These track the current run to clear + retry.
    private volatile String lastUserText;
    private volatile boolean runHadOutput;
    private volatile boolean modelFixRetried;
    private volatile boolean flakeRetried;
    private boolean pinnedBottom = true;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (busy && System.currentTimeMillis() - lastPartTs > 3500) {
                setBusy(false);
            } else if (busy) {
                ui.postDelayed(this, 1200);
            }
        }
    };

    // ---------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_chat);
        list = findViewById(R.id.list);
        scroll = findViewById(R.id.scroll);
        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvStatus = findViewById(R.id.tvStatus);
        tvSpend = findViewById(R.id.tvSpend);
        chipMode = findViewById(R.id.chipMode);
        chipModel = findViewById(R.id.chipModel);
        btnSend = findViewById(R.id.btnSend);
        input = findViewById(R.id.input);
        permSlot = findViewById(R.id.permSlot);

        // P8: project context from the deck (name shown in the subtitle;
        // path is a safety net — switch sandbox if Home somehow didn't,
        // but never during an in-flight restart Home already triggered).
        Intent in = getIntent();
        if (in != null) {
            projectName = in.getStringExtra("project");
            String path = in.getStringExtra("path");
            if (path != null && Projects.validDir(path)
                    && !ServerService.pendingRestart()
                    && ServerService.needsSwitch(new File(path))) {
                ServerService.switchTo(this, new File(path));
            }
        }

        findViewById(R.id.btnPalette).setOnClickListener(v -> palette());
        // P10: visible back affordance — chat → project deck, easier navigation
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            Theme.pop(v);
            finish();
            overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right);
        });
        btnSessions = findViewById(R.id.btnSessions);
        if (btnSessions != null) btnSessions.setOnClickListener(v -> sessionsSheet());
        emptyHero = findViewById(R.id.emptyHero);
        suggestBox = findViewById(R.id.suggestBox);
        chipMode.setOnClickListener(v -> toggleMode());
        chipModel.setOnClickListener(v -> modelSheet());
        btnSend.setOnClickListener(v -> { if (busy) abortRun(); else send(); });
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            pinnedBottom = atBottom();
            syncPill();
        });

        buildVeil();
        buildTyping();
        buildPill();
        buildSuggestions();

        refreshChips();
        refreshServerUi();
        renderAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ServerService.subscribe(this);
        ServerService.subscribeEvents(this);
        int st = ServerService.getState();
        if ((st == ServerService.ST_IDLE || st == ServerService.ST_STOPPED
                || st == ServerService.ST_EXITED) && !ServerService.pendingRestart()) {
            if (Binaries.binaryReady(this)) {
                try {
                    startForegroundService(new Intent(this, ServerService.class));
                } catch (Exception ignored) {}
            }
        }
        if (sessionId != null && rows.isEmpty()) loadSession(sessionId);
        checkPermissionQueue();
        refreshServerUi();
    }

    @Override
    protected void onPause() {
        ServerService.unsubscribe(this);
        ServerService.unsubscribeEvents(this);
        // P15: drop pending paint work with the subscription — the flush
        // would fire into a detached view tree on return (harmless but wasteful).
        ui.removeCallbacks(flushPaints);
        paintScheduled = false;
        dirtyRows.clear();
        super.onPause();
    }

    private boolean atBottom() {
        int off = scroll.getScrollY() + scroll.getHeight() - list.getHeight();
        return off > -80;
    }

    private void autoscroll() {
        if (pinnedBottom) scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ------------------------------------------------- P9 stream smoother

    /**
     * Token-by-token feel: SSE deltas land in Row.text (the target) and a
     * 24 ms ticker paints a few more chars into the row's TextView with a
     * caret — plain text while streaming (cheap), full markdown ONCE when
     * the part catches up. Adaptive step = remaining/8 + 3 chars, so short
     * replies land instantly and long ones glide.
     */
    private void startSmoother() {
        if (smootherRunning) return;
        smootherRunning = true;
        smoother.postDelayed(tick, 24);
    }

    // --------------------------------------------- P15 paint coalescing

    /**
     * The "not snappy" killer, caught red-handed: one busy agent turn
     * fires dozens of SSE merges per second, and each merge called
     * touchView → removeViewAt+addView → a FULL LinearLayout relayout of
     * every row — including the huge tool blocks. Long commands made the
     * chat visibly fight the user.
     *
     * Fix: merge-path repaints are COALESCED. requestPaint() marks the row
     * dirty and schedules ONE flush 80 ms out; the flush paints each dirty
     * row once. Bursts of N events now cost one relayout, not N. Direct
     * touchView stays for what the user does (send, expand/collapse, sys/err
     * rows) — those must feel instant, and they are rare.
     */
    private static final long PAINT_THROTTLE_MS = 80;
    private final java.util.LinkedHashSet<Row> dirtyRows = new java.util.LinkedHashSet<>();
    private boolean paintScheduled;

    private final Runnable flushPaints = this::flushPaintsNow;

    private void requestPaint(Row r) {
        dirtyRows.add(r);
        if (!paintScheduled) {
            paintScheduled = true;
            ui.postDelayed(flushPaints, PAINT_THROTTLE_MS);
        }
    }

    private void flushPaintsNow() {
        paintScheduled = false;
        if (dirtyRows.isEmpty()) return;
        java.util.ArrayList<Row> batch = new java.util.ArrayList<>(dirtyRows);
        dirtyRows.clear();
        for (Row r : batch) {
            Integer i = r.key == null ? null : idxByKey.get(r.key);
            if (i != null && i < rows.size() && rows.get(i) == r) touchView(r);
        }
        autoscroll();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean more = false;
            synchronized (lock) {
                for (int i = rows.size() - 1; i >= 0; i--) {
                    Row r = rows.get(i);
                    int len = r.text.length();
                    if (r.shown > len) r.shown = len;
                    if (r.kind != K_ASSISTANT || r.shown >= len) continue;
                    if (i != rows.size() - 1) {
                        r.shown = len;          // a newer row appeared: snap
                    } else {
                        int step = 3 + (len - r.shown) / 8;
                        r.shown = Math.min(len, r.shown + step);
                    }
                    paintStreaming(r);
                    if (r.shown < len) more = true;
                }
            }
            if (pinnedBottom) scroll.fullScroll(ScrollView.FOCUS_DOWN);
            if (more) {
                smoother.postDelayed(this, 24);
            } else {
                smootherRunning = false;
            }
        }
    };

    /** In-place text paint while streaming (no view rebuilds). */
    private void paintStreaming(Row r) {
        TextView body = bodyByKey.get(r.key);
        View root = viewByKey.get(r.key);
        if (body == null || root == null) { touchView(r); return; }
        int len = r.text.length();
        int upto = Math.min(r.shown, len);
        String s = r.text.substring(0, upto);
        body.setText(s.length() == 0 ? "…" : s + "▍");
        if (r.shown >= len) {
            // caught up → finalize with markdown (single rebuild)
            touchView(r);
        }
    }

    private boolean isStreamingTail(Row r) {
        if (!Theme.motionOn(this)) return false;
        synchronized (lock) {
            boolean last = !rows.isEmpty() && rows.get(rows.size() - 1) == r;
            return last && r.kind == K_ASSISTANT && r.shown < r.text.length()
                    && r.text.length() <= 40000;
        }
    }

    // --------------------------------------------------- P9 empty state

    private void buildSuggestions() {
        if (suggestBox == null) return;
        String[][] ideas = {
                {"Explain this codebase", "walk me through how this project is structured"},
                {"Fix a bug", "find a bug in this project and fix it"},
                {"Add a feature", "suggest and build one useful feature for this project"},
                {"Write tests", "add tests for the most important code here"}};
        for (String[] idea : ideas) {
            TextView c = new TextView(this);
            c.setText(idea[0]);
            c.setTextSize(13);
            c.setTypeface(Typeface.DEFAULT_BOLD);
            c.setTextColor(getColor(R.color.accent_light));
            c.setBackgroundResource(R.drawable.bg_suggest);
            int p = dp(14);
            c.setPadding(p, dp(9), p, dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            c.setLayoutParams(lp);
            Theme.press(c);
            c.setOnClickListener(v -> {
                input.setText(idea[1]);
                input.setSelection(idea[1].length());
                input.requestFocus();
            });
            suggestBox.addView(c);
        }
    }

    private void syncEmpty() {
        if (emptyHero == null) return;
        boolean empty;
        synchronized (lock) { empty = rows.isEmpty(); }
        int vis = empty ? View.VISIBLE : View.GONE;
        if (emptyHero.getVisibility() != vis) emptyHero.setVisibility(vis);
    }

    // -------------------------------------------------------- P8 ui extras

    /** Full-screen "starting sandbox" veil — shown only while unhealthy. */
    private void buildVeil() {
        FrameLayout content = (FrameLayout) ((ViewGroup)
                getWindow().getDecorView().findViewById(android.R.id.content));
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(0xE60A0C12);
        fl.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Theme.panel(this));
        int p = Theme.dp(this, 26);
        card.setPadding(p, p, p, p);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);

        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(18);
        dot.setTextColor(Theme.WARN);
        dot.setGravity(Gravity.CENTER);
        card.addView(dot);
        if (Theme.motionOn(this)) Theme.pulse(dot);

        TextView t1 = new TextView(this);
        t1.setText("starting sandbox");
        t1.setTextSize(16);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(Theme.TXT);
        t1.setGravity(Gravity.CENTER);
        t1.setPadding(0, Theme.dp(this, 10), 0, 0);
        card.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(projectName == null ? "" : projectName);
        t2.setTextSize(12);
        t2.setTextColor(Theme.ACCENT_LT);
        t2.setGravity(Gravity.CENTER);
        t2.setPadding(0, Theme.dp(this, 4), 0, 0);
        card.addView(t2);

        TextView t3 = new TextView(this);
        t3.setText("cold start ≈ 5 s · sessions and tools are rooted at\nyour project folder");
        t3.setTextSize(11);
        t3.setTextColor(Theme.TXT_DIM);
        t3.setGravity(Gravity.CENTER);
        t3.setPadding(0, Theme.dp(this, 8), 0, 0);
        card.addView(t3);

        fl.addView(card, clp);
        content.addView(fl, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        veil = fl;
        veil.setVisibility(View.GONE);
    }

    private void syncVeil(int st) {
        if (veil == null) return;
        boolean show = st != ServerService.ST_HEALTHY;
        int vis = show ? View.VISIBLE : View.GONE;
        if (veil.getVisibility() != vis) {
            veil.setVisibility(vis);
            if (show && Theme.motionOn(this)) Theme.appear(veil);
        }
    }

    /** Three-dot typing indicator between transcript and composer. */
    private void buildTyping() {
        typing = new LinearLayout(this);
        typing.setOrientation(LinearLayout.HORIZONTAL);
        typing.setGravity(Gravity.CENTER_VERTICAL);
        typing.setPadding(Theme.dp(this, 18), Theme.dp(this, 4), 0, Theme.dp(this, 2));
        for (int i = 0; i < 3; i++) {
            TextView d = new TextView(this);
            d.setText("●");
            d.setTextSize(9);
            d.setTextColor(Theme.ACCENT_LT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Theme.dp(this, 4);
            d.setLayoutParams(lp);
            typing.addView(d);
        }
        TextView t = new TextView(this);
        t.setText("thinking…");
        t.setTextSize(12);
        t.setTextColor(Theme.TXT_DIM);
        typing.addView(t);
        typing.setVisibility(View.GONE);

        ViewGroup parent = (ViewGroup) scroll.getParent();
        parent.addView(typing, Math.max(0, parent.indexOfChild(permSlot)));
    }

    private void syncTyping() {
        if (typing == null) return;
        int vis = busy ? View.VISIBLE : View.GONE;
        if (typing.getVisibility() != vis) {
            typing.setVisibility(vis);
            if (vis == View.VISIBLE) {
                if (Theme.motionOn(this)) {
                    typingAnims.clear();
                    long d = 0;
                    for (int i = 0; i < 3; i++) {
                        View dot = typing.getChildAt(i);
                        dot.setAlpha(1f);
                        dot.setTranslationY(0f);
                        // P12: wave — the dots bob in sequence instead of
                        // blinking in place, reads as "breathing", not
                        // "loading bar"
                        ObjectAnimator bob = ObjectAnimator.ofFloat(dot,
                                View.TRANSLATION_Y, 0f, -Theme.dp(this, 5), 0f);
                        bob.setDuration(560);
                        bob.setStartDelay(d);
                        bob.setRepeatCount(ObjectAnimator.INFINITE);
                        bob.setRepeatMode(ObjectAnimator.RESTART);
                        bob.setInterpolator(Theme.DECEL);
                        bob.start();
                        typingAnims.add(bob);
                        d += 150;
                    }
                }
            } else {
                for (ObjectAnimator a : typingAnims) a.cancel();
                typingAnims.clear();
                for (int i = 0; i < 3; i++) {
                    typing.getChildAt(i).setAlpha(1f);
                    typing.getChildAt(i).setTranslationY(0f);
                }
            }
        }
    }

    /** "↓ latest" pill above the composer when scrolled up. */
    private void buildPill() {
        FrameLayout content = (FrameLayout) ((ViewGroup)
                getWindow().getDecorView().findViewById(android.R.id.content));
        scrollPill = new TextView(this);
        scrollPill.setText("↓  latest");
        scrollPill.setTextSize(12);
        scrollPill.setTextColor(Theme.ACCENT_LT);
        scrollPill.setBackground(Theme.ripple(this, Theme.panel(this)));
        scrollPill.setPadding(Theme.dp(this, 16), Theme.dp(this, 7), Theme.dp(this, 16), Theme.dp(this, 7));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        flp.bottomMargin = Theme.dp(this, 96);
        content.addView(scrollPill, flp);
        scrollPill.setVisibility(View.GONE);
        scrollPill.setOnClickListener(v -> {
            pinnedBottom = true;
            scroll.fullScroll(ScrollView.FOCUS_DOWN);
            syncPill();
        });
    }

    private void syncPill() {
        if (scrollPill == null) return;
        boolean want = !pinnedBottom;
        if (want == pillShown) return;
        pillShown = want;
        if (Theme.motionOn(this)) {
            scrollPill.animate().alpha(want ? 1f : 0f).setDuration(140)
                    .setInterpolator(Theme.DECEL)
                    .withStartAction(() -> { if (want) scrollPill.setVisibility(View.VISIBLE); })
                    .withEndAction(() -> { if (!want) scrollPill.setVisibility(View.GONE); })
                    .start();
        } else {
            scrollPill.setVisibility(want ? View.VISIBLE : View.GONE);
        }
    }

    // ----------------------------------------------------------- composer

    private void toggleMode() {
        agent = "build".equals(agent) ? "plan" : "build";
        refreshChips();
        Theme.pop(chipMode);
        Toast.makeText(this, "agent: " + agent, Toast.LENGTH_SHORT).show();
    }

    private void refreshChips() {
        boolean plan = "plan".equals(agent);
        chipMode.setText(plan ? "Plan" : "Build");
        chipMode.setTextColor(getColor(plan ? R.color.accent_light : R.color.ok));
        String[] sel = Models.selected(this);
        chipModel.setText(sel == null ? "model ▾" : sel[1]);
    }

    private void setBusy(boolean b) {
        busy = b;
        ui.post(() -> {
            if (b) {
                btnSend.setText("■");
                btnSend.setTextSize(16);
                btnSend.setBackgroundResource(R.drawable.bg_stop);
                btnSend.setTextColor(getColor(R.color.err));
                tvStatus.setVisibility(View.VISIBLE);
                tvStatus.setText("working — tap ■ to stop");
            } else {
                btnSend.setText("↑");
                btnSend.setTextSize(22);
                btnSend.setBackgroundResource(R.drawable.bg_send);
                btnSend.setTextColor(getColor(R.color.on_accent));
                tvStatus.setVisibility(View.GONE);
            }
            syncTyping();
        });
    }

    private void send() {
        final String q = input.getText().toString().trim();
        if (q.isEmpty() || busy) return;
        input.setText("");
        Theme.pop(btnSend); // P8 micro-anim: the send button springs
        lastUserText = q;
        runHadOutput = false;
        ex.execute(() -> {
            try {
                String sid = ensureSession();
                if (sid == null) {
                    sys("server not healthy yet — try again in a moment (⌘ → Restart server if it persists)");
                    return;
                }
                validateSelectedModel();          // P11: self-heal stale picks
                lastPartTs = System.currentTimeMillis();
                setBusy(true);
                List<String> bodies = buildBodies(q);
                Api.Resp r = null;
                boolean modelDropped = false;
                for (int i = 0; i < bodies.size(); i++) {
                    r = Api.post("/session/" + sid + "/message", bodies.get(i), 300_000);
                    if (r.ok()) {
                        // P11: variant indices 0–1 still carry the model
                        // (ma, m); later ones silently lost the pick.
                        if (i >= 2 && Models.selected(this) != null) modelDropped = true;
                        break;
                    }
                    String failTxt = r.body == null ? "" : r.body;
                    if (isModelNotFound(failTxt)) break;   // handled below
                    if (i < bodies.size() - 1
                            && (r.status == 400 || r.status == 422 || r.status == 500)) {
                        sys("server rejected the request shape (HTTP " + r.status
                                + ") — retrying in a plainer form…");
                        continue;
                    }
                    break;
                }
                if (r == null || !r.ok()) {
                    int st = r == null ? 0 : r.status;
                    String detail = r == null ? "" : Json.findErrorText(Json.parse(r.body), 0);
                    String raw = r == null ? "" : r.body;
                    if (isModelNotFound(raw + " " + detail) && !modelFixRetried) {
                        // P11 self-heal: the saved pick is gone from the
                        // server's catalog (the free-model lineup rotates).
                        modelFixRetried = true;
                        Models.clear(this);
                        ui.post(this::refreshChips);
                        sys("⚠ the saved model was rejected by the server "
                                + "(the free-model lineup rotates) — cleared it; "
                                + "retrying with the server default…");
                        sendText(sid, q);
                        return;
                    }
                    err("send failed · HTTP " + st, detail, raw);
                    setBusy(false);
                    return;
                }
                if (modelDropped)
                    sys("note: the server ignored the picked model for this "
                            + "message — it answered with its default model");
                reconcile(Json.parse(r.body));
                ui.removeCallbacks(watchdog);
                ui.postDelayed(watchdog, 2000);
            } catch (Exception e) {
                sys("send failed: " + e);
                setBusy(false);
            }
        });
    }

    /** Fire-and-track the same text again (self-heal / stream-flake retry). */
    private void sendText(final String sid, final String q) {
        ex.execute(() -> {
            try {
                lastPartTs = System.currentTimeMillis();
                setBusy(true);
                List<String> bodies = buildBodies(q);
                Api.Resp r = null;
                for (String body : bodies) {
                    r = Api.post("/session/" + sid + "/message", body, 300_000);
                    if (r.ok()) break;
                }
                if (r == null || !r.ok()) {
                    err("retry failed · HTTP " + (r == null ? 0 : r.status),
                            r == null ? "" : Json.findErrorText(Json.parse(r.body), 0),
                            r == null ? "" : r.body);
                    setBusy(false);
                    return;
                }
                reconcile(Json.parse(r.body));
                ui.removeCallbacks(watchdog);
                ui.postDelayed(watchdog, 2000);
            } catch (Exception e) {
                sys("retry failed: " + e);
                setBusy(false);
            }
        });
    }

    /** Server phrasing for a vanished model (verified P11 LIVE against
     *  v1.18.25: ProviderModelNotFoundError → "Model not found:
     *  provider/id. Did you mean: …"). Fires at RUN time (HTTP 200!), so
     *  both the POST body and streamed errors must match it. */
    private static boolean isModelNotFound(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.US);
        return l.contains("model not found") || l.contains("unknown model")
                || l.contains("providedmodelnotfound");
    }

    /** True when the error is a transient provider/stream failure that a
     *  single retry can survive (zen streams 504-idle on mobile networks —
     *  reproduced live against v1.18.25). */
    private static boolean isStreamFlake(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.US);
        return l.contains("upstream idle timeout")
                || l.contains("streaming response failed")
                || l.contains("cannot connect")
                || l.contains("connection reset")
                || l.contains("econnreset");
    }

    /** P11: if the saved model is not in the server's live catalog, drop it
     *  BEFORE the request instead of failing the send. Empty fetch (server
     *  hiccup) never clears anything. */
    private void validateSelectedModel() {
        String[] sel = Models.selected(this);
        if (sel == null) return;
        List<Models.Prov> provs = Models.lastFetch();
        if (provs == null || provs.isEmpty()) provs = Models.fetch(this);
        if (provs.isEmpty()) return;                     // unknown state → keep
        if (!Models.available(provs, sel[0], sel[1])) {
            Models.clear(this);
            ui.post(this::refreshChips);
            sys("⚠ saved model " + sel[0] + "/" + sel[1]
                    + " is no longer offered by the server — cleared (⌘ → Model to pick another; the free list rotates)");
        }
    }

    /**
     * Candidates in decreasing explicitness, P11 ORDER FIX: model variants
     * first (ma → m), THEN agent-less/bare. The old order (ma → a → m →
     * bare) silently DROPPED the user's model on any 4xx — the chat kept
     * working but the picked model was ignored without a word.
     */
    private List<String> buildBodies(String q) {
        LinkedHashMap<String, String> variants = new LinkedHashMap<>();
        String text = "{\"type\":\"text\",\"text\":" + Json.quote(q) + "}";
        String[] sel = Models.selected(this);
        String model = sel == null ? null
                : "\"model\":{\"providerID\":" + Json.quote(sel[0])
                + ",\"modelID\":" + Json.quote(sel[1]) + "}";
        String ag = "\"agent\":" + Json.quote(agent);
        String parts = "\"parts\":[" + text + "]";
        if (model != null) variants.put("ma", "{" + model + "," + ag + "," + parts + "}");
        if (model != null) variants.put("m", "{" + model + "," + parts + "}");
        variants.put("a", "{" + ag + "," + parts + "}");
        variants.put("bare", "{" + parts + "}");
        return new ArrayList<>(variants.values());
    }

    private void abortRun() {
        final String sid = sessionId;
        sys("■ stop requested");
        ex.execute(() -> {
            try {
                if (sid != null) {
                    Api.Resp r = Api.call("POST", "/session/" + sid + "/abort", null, 10_000);
                    if (!r.ok()) sys("abort returned HTTP " + r.status);
                }
            } catch (Exception e) {
                sys("abort failed: " + e);
            }
            setBusy(false);
        });
    }

    private String ensureSession() {
        if (sessionId != null) return sessionId;
        if (!ServerService.healthy()) return null;
        try {
            Api.Resp r = Api.post("/session", "{}", 15_000);
            if (!r.ok()) return null;
            Map<String, Object> o = Json.obj(Json.parse(r.body));
            if (o == null) return null;
            Map<String, Object> info = Json.map(o, "info");
            String id = info != null ? Json.str(info, "id") : null;
            if (id == null) id = Json.str(o, "id");
            if (id == null) return null;
            sessionId = id;
            String t = info != null ? Json.str(info, "title") : null;
            sessionTitle = (t == null || t.isEmpty()) ? "New chat" : t;
            ui.post(() -> tvTitle.setText(sessionTitle));
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------- service event feed

    /** ServerService rebroadcasts every parsed /event frame here. */
    @Override
    public void onEvent(Map<String, Object> ev) {
        try {
            String type = Json.str(ev, "type");
            Map<String, Object> props = Json.map(ev, "properties");
            if (props == null) props = new HashMap<>();
            if ("message.part.updated".equals(type)) {
                Map<String, Object> part = Json.map(props, "part");
                if (part != null) {
                    String pt = Json.str(part, "type");
                    if (busy && ("text".equals(pt) || "reasoning".equals(pt)))
                        runHadOutput = true;   // P11: the run produced real output
                    applyPart(part, null);
                }
            } else if ("message.updated".equals(type)) {
                applyMessageInfo(Json.map(props, "info"));
            } else if ("session.updated".equals(type)) {
                Map<String, Object> info = Json.map(props, "info");
                if (info != null && sessionId != null
                        && sessionId.equals(Json.str(info, "id"))) {
                    String t = Json.str(info, "title");
                    if (t != null && !t.isEmpty()) {
                        sessionTitle = t;
                        ui.post(() -> tvTitle.setText(t));
                    }
                }
            } else if ("session.idle".equals(type)) {
                String sid = Json.str(props, "sessionID");
                if (sid == null || sid.equals(sessionId)) setBusy(false);
            } else if ("session.error".equals(type)) {
                Map<String, Object> e = Json.map(props, "error");
                String m = e != null ? Json.str(e, "message") : null;
                if (m == null) m = Json.findErrorText(props, 0);
                String raw = String.valueOf(props);
                if (isModelNotFound(raw + " " + m)) {
                    // P11 self-heal: run-time Model not found (the POST itself
                    // returned 200) — drop the stale pick so the NEXT send
                    // uses the server default instead of failing forever.
                    Models.clear(this);
                    ui.post(this::refreshChips);
                    err("model no longer available", "the picked model was "
                            + "removed from the server's catalog — selection "
                            + "cleared, send again (⌘ → Model to choose another)", raw);
                } else if (busy && !runHadOutput && !flakeRetried
                        && lastUserText != null && isStreamFlake(raw + " " + m)) {
                    // P11: zen streams die with 504 idle-timeout on mobile
                    // networks; retry ONCE when nothing was rendered yet.
                    flakeRetried = true;
                    sys("⚠ the model stream dropped (" + nz(m, "network")
                            + ") — retrying once…");
                    final String sid = sessionId;
                    if (sid != null) sendText(sid, lastUserText);
                    else setBusy(false);
                } else {
                    err("session error", m, raw);
                    setBusy(false);
                }
            } else if ("permission.asked".equals(type)
                    || "permission.updated".equals(type)
                    || "permission.v2.asked".equals(type)
                    || "permission.v2.updated".equals(type)) {
                ui.post(this::checkPermissionQueue);
            }
        } catch (Exception e) {
            // never let a malformed frame kill the screen
        }
    }

    /** ServerService state changes → header subtitle. */
    @Override
    public void on(int newState, String detail) {
        ui.post(() -> refreshServerUi());
        if (newState == ServerService.ST_HEALTHY) {
            ui.post(this::checkPermissionQueue);
            ui.post(this::showEnvWelcome);          // P15: one-shot env report
        }
    }

    /** P15 — the agent's own suggestion: "Show environment info when agent
     *  starts, so it knows what it's working with." One welcome row per
     *  chat lifetime (⌘ → Sandbox environment re-runs it on demand). */
    private boolean envShown;

    private void showEnvWelcome() {
        if (envShown || isFinishing() || isDestroyed()) return;
        envShown = true;
        ex.execute(() -> {
            final String rep = Debian.envReport(this);
            ui.post(() -> sys(rep));
        });
    }

    private void refreshServerUi() {
        int st = ServerService.getState();
        String s;
        switch (st) {
            case ServerService.ST_HEALTHY: s = "sandbox ready"; break;
            case ServerService.ST_STARTING: s = "starting sandbox…"; break;
            case ServerService.ST_EXITED: s = "server exited — ⌘ → Restart server"; break;
            case ServerService.ST_STOPPED: s = "server stopped — ⌘ → Restart server"; break;
            default: s = "server idle";
        }
        if (projectName != null && !projectName.isEmpty()) s = projectName + " · " + s;
        // P14: the spend line MOVED OUT of the subtitle — it was appended to
        // a maxLines=1 ellipsized TextView, so the ⇅ tok AND the $ number
        // (last thing in the string) were the first to be cut off. It now
        // lives in its own header pill that always shows in full.
        String spend = spendLine();
        if (tvSpend != null) {
            if (spend.isEmpty()) {
                tvSpend.setVisibility(View.GONE);
            } else {
                tvSpend.setVisibility(View.VISIBLE);
                // spendLine starts with " · " for its subtitle role — strip
                // it for the pill and compact the text ("⇅ 12.3k · $0.0041")
                String pill = spend.startsWith(" · ") ? spend.substring(3) : spend;
                tvSpend.setText(pill.replace(" tok", ""));
            }
        }
        if (!AuthStore.hasAnyKey(this) && st == ServerService.ST_HEALTHY) {
            s += " · no API key yet — ⌘ → API keys";
        }
        tvSub.setText(s);
        syncVeil(st);
    }

    // ------------------------------------------------------------ history

    private void loadSession(String id) {
        sessionId = id;
        modelFixRetried = false;      // P11: fresh chat → fresh self-heal budget
        flakeRetried = false;
        runHadOutput = false;
        lastUserText = null;
        ui.post(() -> {
            synchronized (lock) {
                rows.clear(); idxByKey.clear(); typeCount.clear(); msgs.clear();
                dirtyRows.clear(); paintScheduled = false;   // P15: no ghost paints
                viewByKey.clear(); bodyByKey.clear(); metaByKey.clear();
            }
            list.removeAllViews();
            pinnedBottom = true;
            syncEmpty();
        });
        if (id == null) {
            sessionTitle = "New chat";
            ui.post(() -> { tvTitle.setText(sessionTitle); refreshChips(); });
            return;
        }
        ex.execute(() -> {
            try {
                Api.Resp sl = Api.get("/session");
                if (sl.ok()) {
                    List<Object> sarr = Json.arr(Json.parse(sl.body));
                    if (sarr != null) for (Object o : sarr) {
                        Map<String, Object> m = Json.obj(o);
                        if (m != null && id.equals(Json.str(m, "id"))) {
                            String t = Json.str(m, "title");
                            if (t != null && !t.isEmpty()) {
                                sessionTitle = t;
                                ui.post(() -> tvTitle.setText(t));
                            }
                            break;
                        }
                    }
                }
                Api.Resp r = Api.get("/session/" + id + "/message");
                if (!r.ok()) { sys("history unavailable · HTTP " + r.status); return; }
                List<Object> arr = Json.arr(Json.parse(r.body));
                if (arr == null) return;
                int from = Math.max(0, arr.size() - 80);
                for (int i = from; i < arr.size(); i++) {
                    Map<String, Object> item = Json.obj(arr.get(i));
                    if (item == null) continue;
                    Map<String, Object> info = Json.map(item, "info");
                    if (info == null) info = item;
                    if (Boolean.TRUE.equals(info.get("synthetic"))) continue;
                    String role = Json.str(info, "role");
                    applyMessageInfo(info);
                    List<Object> parts = Json.list(item, "parts");
                    if (parts == null) parts = Json.list(info, "parts");
                    if (parts != null) for (Object p : parts) {
                        Map<String, Object> pm = Json.obj(p);
                        if (pm != null) applyPart(pm, role);
                    }
                }
            } catch (Exception e) {
                sys("history failed: " + e);
            }
        });
    }

    /** POST /session/{id}/message response → same pipeline as SSE. */
    private void reconcile(Object parsed) {
        try {
            Map<String, Object> o = Json.obj(parsed);
            if (o != null) {
                Map<String, Object> info = Json.map(o, "info");
                if (info == null && Json.str(o, "role") != null) info = o;
                if (info != null) {
                    applyMessageInfo(info);
                    List<Object> parts = Json.list(o, "parts");
                    String role = Json.str(info, "role");
                    if (parts != null) for (Object p : parts) {
                        Map<String, Object> pm = Json.obj(p);
                        if (pm != null) applyPart(pm, role);
                    }
                    return;
                }
            }
            List<Object> arr = Json.arr(parsed);
            if (arr == null) return;
            for (Object it : arr) {
                Map<String, Object> m = Json.obj(it);
                if (m == null) continue;
                Map<String, Object> inf = Json.map(m, "info");
                if (inf == null) inf = m;
                applyMessageInfo(inf);
                List<Object> ps = Json.list(m, "parts");
                String role = Json.str(inf, "role");
                if (ps != null) for (Object p : ps) {
                    Map<String, Object> pm = Json.obj(p);
                    if (pm != null) applyPart(pm, role);
                }
            }
        } catch (Exception e) {
            // response shape drift must never break the send path
        }
    }

    // ------------------------------------------------------- part routing

    private String roleOf(String mid) {
        MsgInfo mi = msgs.get(mid);
        return mi != null && mi.role != null ? mi.role : "assistant";
    }

    private void applyPart(Map<String, Object> part, String roleHint) {
        try {
            String type = Json.str(part, "type");
            if (type == null) return;
            String mid = Json.str(part, "messageID");
            if (mid == null) mid = "m" + Integer.toHexString(System.identityHashCode(part));
            String pid = Json.str(part, "id");
            int n;
            synchronized (lock) { n = mergeCount(mid + "|" + type); }
            final String key = (pid != null && !pid.isEmpty())
                    ? mid + "|" + pid : mid + "|" + type + "#" + n;
            final String role = roleHint != null ? roleHint : roleOf(mid);
            lastPartTs = System.currentTimeMillis();

            switch (type) {
                case "text": {
                    final String t = nz(Json.str(part, "text"), "");
                    if ("user".equals(role)) upsertUser(key, t);
                    else upsertText(key, mid, t);
                    break;
                }
                case "reasoning": {
                    if ("user".equals(role)) break;
                    upsertReason(key, mid, nz(Json.str(part, "text"), ""));
                    break;
                }
                case "tool": upsertTool(toolRow(key, part)); break;
                case "patch": upsertTool(patchRow(key, part)); break;
                default: break; // step-start/-finish, agent, … hidden like the TUI
            }
        } catch (Exception e) {
            // a malformed part must never take the chat down
        }
    }

    private int mergeCount(String k) {
        Integer c = typeCount.get(k);
        n2 = c == null ? 1 : c + 1;
        typeCount.put(k, n2);
        return n2;
    }
    private int n2; // scratch for mergeCount (main-thread only)

    // ---------------------------------------------------------- row model

    private final Object lock = new Object();

    private Row rowByKey(String key) {
        Integer i = idxByKey.get(key);
        return i == null ? null : rows.get(i);
    }

    private void upsertUser(String key, String text) {
        ui.post(() -> {
            synchronized (lock) {
                Row r = rowByKey(key);
                boolean changed;
                if (r == null) {
                    r = new Row();
                    r.kind = K_USER; r.key = key; r.ts = System.currentTimeMillis();
                    r.text.append(text);
                    rows.add(r); idxByKey.put(key, rows.size() - 1);
                    changed = true;
                } else {
                    changed = !text.contentEquals(r.text);
                    if (changed) { r.text.setLength(0); r.text.append(text); }
                }
                if (changed) touchView(r);
            }
            autoscroll();
        });
    }

    private void upsertText(String key, String mid, String text) {
        ui.post(() -> {
            synchronized (lock) {
                Row r = rowByKey(key);
                if (r == null) {
                    r = new Row();
                    r.kind = K_ASSISTANT; r.key = key; r.ts = System.currentTimeMillis();
                    r.text.append(mergeText("", text));
                    MsgInfo mi = msgs.get(mid);
                    r.meta = mi == null ? null : mi.meta;
                    rows.add(r); idxByKey.put(key, rows.size() - 1);
                    requestPaint(r);
                } else {
                    String merged = mergeText(r.text.toString(), text);
                    boolean changed = !merged.contentEquals(r.text);
                    if (changed) { r.text.setLength(0); r.text.append(merged); }
                    MsgInfo mi = msgs.get(mid);
                    String meta = mi == null ? null : mi.meta;
                    boolean metaChanged = meta != null && !meta.equals(r.meta);
                    if (metaChanged) r.meta = meta;
                    if (changed || metaChanged) requestPaint(r);
                }
            }
            autoscroll();
        });
    }

    private void upsertReason(String key, String mid, String text) {
        ui.post(() -> {
            synchronized (lock) {
                Row r = rowByKey(key);
                if (r == null) {
                    r = new Row();
                    r.kind = K_REASON; r.key = key; r.ts = System.currentTimeMillis();
                    r.text.append(mergeText("", text));
                    rows.add(r); idxByKey.put(key, rows.size() - 1);
                    requestPaint(r);
                } else {
                    String merged = mergeText(r.text.toString(), text);
                    if (!merged.contentEquals(r.text)) {
                        r.text.setLength(0); r.text.append(merged);
                        requestPaint(r);
                    }
                }
            }
            autoscroll();
        });
    }

    private void upsertTool(Row incoming) {
        ui.post(() -> {
            synchronized (lock) {
                Row r = rowByKey(incoming.key);
                boolean changed;
                if (r == null) {
                    r = incoming;
                    rows.add(r); idxByKey.put(r.key, rows.size() - 1);
                    changed = true;
                } else {
                    changed = !r.status.equals(incoming.status)
                            || r.input.length() != incoming.input.length()
                            || r.output.length() != incoming.output.length()
                            || !r.title.equals(incoming.title);
                    r.tool = incoming.tool; r.status = incoming.status;
                    r.title = incoming.title;
                    r.input.setLength(0); r.input.append(incoming.input);
                    r.output.setLength(0); r.output.append(incoming.output);
                }
                if ("error".equals(r.status)) r.open = true; // never hide failures
                if (changed) requestPaint(r);
            }
            autoscroll();
        });
    }

    private Row toolRow(String key, Map<String, Object> part) {
        Row r = new Row();
        r.kind = K_TOOL; r.key = key; r.ts = System.currentTimeMillis();
        r.tool = nz(Json.str(part, "tool"), "call");
        Map<String, Object> state = Json.map(part, "state");
        if (state == null) state = part;
        r.status = nz(Json.str(state, "status"), "");
        r.title = nz(Json.str(state, "title"), "");
        Map<String, Object> in = Json.map(state, "input");
        if (in != null && !in.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Object> e : in.entrySet()) {
                String v = String.valueOf(e.getValue());
                if (v.length() > 2000) v = v.substring(0, 2000) + "…";
                b.append(e.getKey()).append(": ").append(v).append('\n');
            }
            r.input.append(b.toString().trim());
        }
        String out = Json.str(state, "output");
        if (out != null && !out.isEmpty()) r.output.append(out);
        Map<String, Object> err = Json.map(state, "error");
        if (err != null) {
            String em = Json.str(err, "message");
            if (em == null) em = Json.findErrorText(err, 0);
            if (em != null && !em.isEmpty()) {
                if (r.output.length() > 0) r.output.append('\n');
                r.output.append("✕ ").append(em);
            }
        }
        return r;
    }

    private Row patchRow(String key, Map<String, Object> part) {
        Row r = new Row();
        r.kind = K_TOOL; r.key = key; r.ts = System.currentTimeMillis();
        r.tool = "patch"; r.status = "completed";
        List<Object> files = Json.list(part, "files");
        int n = files == null ? 0 : files.size();
        r.title = n + " file" + (n == 1 ? "" : "s") + " changed";
        if (files != null) for (Object f : files) r.output.append(f).append('\n');
        return r;
    }

    /** SSE sends full part state; adopt the growth, ignore truncations. */
    private static String mergeText(String cur, String next) {
        if (cur == null || cur.isEmpty()) return next;
        if (next == null) return cur;
        if (next.equals(cur)) return cur;
        if (next.startsWith(cur)) return next;   // grew
        if (cur.startsWith(next)) return cur;    // truncated echo
        return next;                              // changed → replace
    }

    private void applyMessageInfo(Map<String, Object> info) {
        if (info == null) return;
        String mid = Json.str(info, "id");
        if (mid == null) return;
        String role = Json.str(info, "role");
        synchronized (lock) {
            MsgInfo mi = msgs.get(mid);
            if (mi == null) { mi = new MsgInfo(); msgs.put(mid, mi); }
            if (role != null) mi.role = role;
        }
        Map<String, Object> tk = Json.map(info, "tokens");
        long total = 0;
        if (tk != null) {
            total += num(tk.get("input")) + num(tk.get("output"))
                    + num(tk.get("reasoning"));
            Map<String, Object> cache = Json.map(tk, "cache");
            if (cache != null) total += num(cache.get("read")) + num(cache.get("write"));
        }
        Object costO = info.get("cost");
        double cost = (costO instanceof Number) ? ((Number) costO).doubleValue() : 0;
        String meta = null;
        if (total > 0 || cost > 0) {
            StringBuilder b = new StringBuilder("⇅ ");
            b.append(total >= 1000
                    ? String.format(Locale.US, "%.1fk", total / 1000.0)
                    : String.valueOf(total)).append(" tok");
            if (cost > 0) b.append(String.format(Locale.US, " · $%.4f", cost));
            meta = b.toString();
        }
        // P12: session-wide spend line — “how much money it used” for the
        // WHOLE conversation, not just one message (per-message cost stays
        // on the row). The server sends cumulative per-message values, so
        // store (not accumulate) and re-sum.
        final double fCost = cost;
        final long fTok = total;
        final boolean hasSpend = total > 0 || cost > 0;
        Map<String, Object> e = Json.map(info, "error");
        final String fErrName = e != null
                ? nz(Json.str(e, "name"), "error") : null;
        final String fErrMsg = e != null
                ? nz(Json.str(e, "message"), Json.findErrorText(e, 0)) : null;
        // P11: message-level Model-not-found also self-heals (this is the
        // path ProviderModelNotFoundError usually arrives through). Clearing
        // is idempotent, so repeats are harmless.
        if (fErrMsg != null && isModelNotFound(fErrMsg)) {
            Models.clear(this);
            ui.post(this::refreshChips);
        }

        final String fMid = mid;
        final String fMeta = meta;
        if (meta != null || e != null) ui.post(() -> {
            boolean showError;
            synchronized (lock) {
                MsgInfo mi = msgs.get(fMid);
                if (mi != null && fMeta != null) mi.meta = fMeta;
                if (mi != null && hasSpend) { mi.cost = fCost; mi.tok = fTok; }
                showError = mi != null && !mi.errorShown && fErrName != null;
                if (showError) mi.errorShown = true;
                if (fMeta != null) {
                    for (int i = rows.size() - 1; i >= 0; i--) {
                        Row r = rows.get(i);
                        if (r.kind == K_ASSISTANT && r.key != null
                                && r.key.startsWith(fMid + "|")) {
                            if (!fMeta.equals(r.meta)) { r.meta = fMeta; requestPaint(r); }
                            break;
                        }
                    }
                }
            }
            if (hasSpend) ui.post(this::refreshServerUi);
            if (showError) {
                err("✕ " + fErrName, fErrMsg == null ? "" : fErrMsg, String.valueOf(info));
                setBusy(false);
            }
        });
    }

    /** P12: ⇅ tokens + $ cost summed over the session's messages. */
    private String spendLine() {
        long tok = 0;
        double cost = 0;
        synchronized (lock) {
            for (MsgInfo mi : msgs.values()) { tok += mi.tok; cost += mi.cost; }
        }
        if (tok <= 0 && cost <= 0) return "";
        StringBuilder b = new StringBuilder(" · ⇅ ");
        b.append(tok >= 1000
                ? String.format(Locale.US, "%.1fk", tok / 1000.0)
                : String.valueOf(tok)).append(" tok");
        if (cost > 0) b.append(String.format(Locale.US, " · $%.4f", cost));
        return b.toString();
    }

    private void sys(String s) {
        Row r = new Row();
        r.kind = K_SYS; r.key = "sys-" + System.nanoTime(); r.ts = System.currentTimeMillis();
        r.text.append(s);
        ui.post(() -> {
            synchronized (lock) {
                rows.add(r); idxByKey.put(r.key, rows.size() - 1);
                touchView(r);
            }
            autoscroll();
        });
    }

    private void err(String title, String detail, String raw) {
        Row r = new Row();
        r.kind = K_ERR; r.key = "err-" + System.nanoTime(); r.ts = System.currentTimeMillis();
        r.text.append(title);
        if (detail != null && !detail.isEmpty()) r.output.append(detail);
        if (raw != null && !raw.isEmpty()) {
            if (r.output.length() > 0) r.output.append("\n----\n");
            String rawT = raw.length() > 3000 ? raw.substring(0, 3000) + "…" : raw;
            r.output.append(rawT);
        }
        r.open = true;
        ui.post(() -> {
            synchronized (lock) {
                rows.add(r); idxByKey.put(r.key, rows.size() - 1);
                touchView(r);
            }
            autoscroll();
        });
    }

    // ------------------------------------------------------------ views

    private boolean needFullRender;
    /** P12: key of the row whose open-state the user just toggled — the
     *  rebuild gets an "unfold" animation instead of a flat swap. */
    private String lastToggled;

    /** Rebuild the view for one row (or append it) — main thread only.
     *  P9: text rows update their cached TextView in place; only the
     *  streaming tail animates char-by-char via the smoother. */
    private void touchView(Row r) {
        if (needFullRender) { renderAll(); return; }
        Integer i = r.key == null ? null : idxByKey.get(r.key);
        if (i == null || i >= rows.size() || rows.get(i) != r) { renderAll(); return; }
        int idx = i;
        int len = r.text.length();
        if (r.shown > len) r.shown = len;

        if (r.kind == K_ASSISTANT && r.shown < len && isStreamingTail(r)) {
            // hand the row to the smoother: first paint shows the tail view
            View nv = buildRowView(r);
            if (idx < list.getChildCount()) {
                list.removeViewAt(idx);
                list.addView(nv, idx);
            } else if (idx == list.getChildCount()) {
                list.addView(nv);
                trimViews();
            } else {
                renderAll();
                return;
            }
            startSmoother();
            autoscroll();
            return;
        }

        // not streaming: everything painted now
        r.shown = len;
        View nv = buildRowView(r);
        boolean toggled = r.key != null && r.key.equals(lastToggled);
        if (idx < list.getChildCount()) {
            list.removeViewAt(idx);
            list.addView(nv, idx);   // streaming update — instant, no animation
            if (toggled && (r.kind == K_TOOL || r.kind == K_REASON)) {
                lastToggled = null;
                Theme.unfold(nv);    // P12: expand/collapse feels physical
            }
        } else if (idx == list.getChildCount()) {
            list.addView(nv);
            if (Theme.motionOn(this)) {
                if (r.kind == K_USER) Theme.springIn(nv);  // P12: bubbly pop
                else Theme.appear(nv);
            }
            trimViews();
        } else {
            renderAll();
        }
        syncEmpty();
    }

    private void trimViews() {
        if (rows.size() <= 450) return;
        synchronized (lock) {
            int cut = rows.size() - 350;
            idxByKey.clear();
            rows.subList(0, cut).clear();
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.key != null) idxByKey.put(r.key, i);
            }
            needFullRender = true;
            viewByKey.clear(); bodyByKey.clear(); metaByKey.clear();
        }
        renderAll();
    }

    private void renderAll() {
        List<Row> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(rows);
            needFullRender = false;
            viewByKey.clear(); bodyByKey.clear(); metaByKey.clear();
            for (Row r : snapshot) r.shown = r.text.length(); // history: no caret
        }
        list.removeAllViews();
        for (Row r : snapshot) {
            try {
                list.addView(buildRowView(r));
            } catch (Exception e) {
                TextView tv = mono(new TextView(this), 12);
                tv.setText("· (unrenderable message part) ·");
                list.addView(tv);
            }
        }
        syncEmpty();
        autoscroll();
    }

    private void setAllOpen(boolean open) {
        synchronized (lock) {
            for (Row r : rows) {
                if (r.kind == K_TOOL || r.kind == K_REASON) r.open = open;
            }
        }
        renderAll();
    }

    private TextView text(int sizeSp, int colorRes, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextSize(sizeSp);
        tv.setTextColor(getColor(colorRes));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView mono(TextView tv, int sizeSp) {
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(sizeSp);
        return tv;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        int p = dp(10);
        c.setPadding(p, p, p, p);
        return c;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private View buildRowView(Row r) {
        switch (r.kind) {
            case K_USER: {
                LinearLayout wrap = new LinearLayout(this);
                wrap.setOrientation(LinearLayout.HORIZONTAL);
                TextView tv = text(15, R.color.user_text, false);
                tv.setBackgroundResource(R.drawable.bg_bubble_user);
                int p = dp(13);
                tv.setPadding(p, dp(9), p, dp(9));
                tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78));
                tv.setText(r.text.toString());
                tv.setTextIsSelectable(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.END;
                lp.topMargin = dp(8);
                tv.setLayoutParams(lp);
                Theme.press(tv);
                tv.setOnLongClickListener(v -> {
                    copyText(r.text.toString(), "message");
                    return true;
                });
                wrap.addView(tv);
                return wrap;
            }
            case K_ASSISTANT: {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(2), dp(8), 0, dp(2));
                boolean streaming = r.shown < r.text.length();
                TextView body = text(15, R.color.text_primary, false);
                body.setTextIsSelectable(true);
                body.setLineSpacing(dp(1), 1f);
                if (streaming) {
                    // plain tail with caret — cheap, re-painted by the smoother
                    int upto = Math.min(r.shown, r.text.length());
                    String s = r.text.substring(0, upto);
                    body.setText(s.length() == 0 ? "…" : s + "▍");
                } else {
                    CharSequence md;
                    try { md = Markdown.render(r.text.toString()); }
                    catch (Exception e) { md = r.text.toString(); }
                    body.setText(md.length() == 0 ? "…" : md);
                    body.setOnLongClickListener(v -> {
                        copyText(r.text.toString(), "response");
                        return true;
                    });
                }
                box.addView(body);
                if (r.meta != null && !r.meta.isEmpty()) {
                    TextView meta = text(11, R.color.text_secondary, false);
                    meta.setText(r.meta);
                    meta.setPadding(0, dp(3), 0, dp(6));
                    box.addView(meta);
                }
                String key = r.key == null ? "" : r.key;
                viewByKey.put(key, box);
                bodyByKey.put(key, body);
                TextView m = r.meta == null ? null
                        : (TextView) box.getChildAt(box.getChildCount() - 1);
                if (m != null && m != body) metaByKey.put(key, m);
                return box;
            }
            case K_REASON: {
                // P10: "voice of mind" — violet-tinted panel, ✦ header,
                // italic body when open. Distinct from tool cards at a glance.
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setBackgroundResource(R.drawable.bg_thought_card);
                int cp = dp(13);
                c.setPadding(cp, dp(10), cp, dp(12));

                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                TextView spark = text(13, R.color.accent_light, false);
                spark.setText("✦");
                spark.setPadding(0, 0, dp(7), 0);
                head.addView(spark);
                TextView h = text(11, R.color.accent_light, true);
                h.setText(r.text.length() == 0 ? "THINKING…" : "THINKING");
                h.setLetterSpacing(0.14f);
                head.addView(h, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView chev = text(11, R.color.text_secondary, false);
                chev.setText(r.open ? "▾" : "▸");
                head.addView(chev);
                c.addView(head);
                if (r.open) {
                    TextView body = text(13, R.color.text_secondary, false);
                    body.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                    body.setTextIsSelectable(true);
                    body.setLineSpacing(dp(2), 1f);
                    String t = r.text.toString();
                    if (t.length() > 20000) t = t.substring(0, 20000) + "…";
                    body.setText(t);
                    body.setPadding(dp(2), dp(8), 0, 0);
                    c.addView(body);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                c.setLayoutParams(lp);
                head.setOnClickListener(v -> { r.open = !r.open; lastToggled = r.key; touchView(r); });
                return c;
            }
            case K_TOOL: {
                // P10: icon-disc tool card — colored glyph disc, name +
                // status line, rounded code blocks for input/output.
                boolean failed = "error".equals(r.status);
                boolean running = "running".equals(r.status) || "pending".equals(r.status);
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setBackgroundResource(failed
                        ? R.drawable.bg_err_card : R.drawable.bg_tool_card);
                int cp = dp(11);
                c.setPadding(cp, cp, cp, cp);

                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);

                TextView disc = text(12, R.color.on_accent, true);
                disc.setTextColor(0xFFFFFFFF);   // literal — not a resource id
                disc.setText(toolGlyph(r.tool));
                disc.setGravity(Gravity.CENTER);
                disc.setBackground(Theme.circle(toolTint(r.tool, failed)));
                int ds = dp(28);
                LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(ds, ds);
                dlp2.rightMargin = dp(10);
                disc.setLayoutParams(dlp2);
                head.addView(disc);

                LinearLayout mid = new LinearLayout(this);
                mid.setOrientation(LinearLayout.VERTICAL);
                TextView name = text(13, R.color.text_primary, true);
                name.setText(toolLabel(r.tool));
                mid.addView(name);
                TextView st = text(11, failed ? R.color.err
                        : running ? R.color.warn : R.color.text_secondary, false);
                String word = failed ? "error"
                        : "completed".equals(r.status) ? "done"
                        : (r.status == null || r.status.isEmpty()) ? ""
                        : r.status + "…";
                st.setText((r.title == null || r.title.isEmpty() ? "" : r.title)
                        + (word.isEmpty() ? "" : (r.title == null || r.title.isEmpty() ? "" : "  ·  ") + word));
                st.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.60));
                st.setSingleLine(true);
                st.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                mid.addView(st);
                head.addView(mid, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                TextView chev = text(12, R.color.text_secondary, false);
                chev.setText(r.open ? "▾" : "▸");
                chev.setPadding(dp(6), 0, 0, 0);
                head.addView(chev);
                c.addView(head);

                if (r.open) {
                    if (r.input.length() > 0) {
                        c.addView(label("input"));
                        c.addView(codeBlock(r.input.toString(), 12000));
                    }
                    if (r.output.length() > 0) {
                        c.addView(label("output"));
                        c.addView(codeBlock(r.output.toString(), 12000));
                    }
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                c.setLayoutParams(lp);
                Theme.press(c);
                head.setOnClickListener(v -> { r.open = !r.open; lastToggled = r.key; touchView(r); });
                c.setOnClickListener(v -> { r.open = !r.open; lastToggled = r.key; touchView(r); });
                return c;
            }
            case K_ERR: {
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setBackgroundResource(R.drawable.bg_err_card);
                int p = dp(12);
                c.setPadding(p, dp(10), p, dp(10));
                TextView t1 = text(13, R.color.err, true);
                t1.setText("✕  " + r.text);
                c.addView(t1);
                if (r.output.length() > 0) {
                    TextView t2 = text(12, R.color.text_secondary, false);
                    t2.setText("tap for details");
                    t2.setPadding(0, dp(3), 0, 0);
                    c.addView(t2);
                }
                c.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle(r.text.toString())
                        .setMessage(r.output.toString())
                        .setPositiveButton("Copy", (d, w) -> {
                            copyText(r.output.toString(), "error");
                        })
                        .setNegativeButton("Close", null)
                        .show());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                c.setLayoutParams(lp);
                return c;
            }
            default: {
                // K_SYS — centered dim pill
                TextView tv = text(11, R.color.text_secondary, false);
                tv.setText(r.text.toString());
                tv.setGravity(Gravity.CENTER_HORIZONTAL);
                tv.setBackgroundResource(R.drawable.bg_sys_pill);
                int p = dp(12);
                tv.setPadding(p, dp(6), p, dp(6));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                lp.bottomMargin = dp(2);
                tv.setLayoutParams(lp);
                return tv;
            }
        }
    }

    private TextView label(String s) {
        TextView tv = text(10, R.color.text_secondary, false);
        tv.setText(s.toUpperCase(Locale.US));
        tv.setLetterSpacing(0.1f);
        tv.setPadding(0, dp(8), 0, dp(2));
        return tv;
    }

    /** P9: friendly tool names on the cards. */
    private static String toolLabel(String tool) {
        if (tool == null || tool.isEmpty()) return "tool";
        switch (tool) {
            case "bash": return "shell";
            case "read": return "read file";
            case "write": return "write file";
            case "edit": return "edit file";
            case "patch": return "patch";
            case "glob": return "find files";
            case "grep": return "search";
            case "list": return "list files";
            case "webfetch": return "web fetch";
            case "todowrite": return "plan";
            case "task": return "sub-agent";
            default: return tool.toLowerCase(Locale.US);
        }
    }

    /** P10: single glyph for a tool's icon disc (safe, common symbols). */
    private static String toolGlyph(String tool) {
        if (tool == null || tool.isEmpty()) return "⚙";
        switch (tool) {
            case "bash": return "$";
            case "read":
            case "list": return "≡";
            case "write":
            case "edit":
            case "patch": return "✎";
            case "glob":
            case "grep": return "∗";
            case "webfetch": return "→";
            case "todowrite": return "✓";
            case "task": return "◆";
            default: return "⚙";
        }
    }

    /** P10: icon-disc tint per tool — instant "what ran" recognition. */
    private static int toolTint(String tool, boolean failed) {
        if (failed) return 0xFFB34848;
        if (tool == null) return 0xFF5A6478;
        switch (tool) {
            case "bash": return 0xFF5B6CFF;          // indigo
            case "read": return 0xFF0EA5E9;          // sky
            case "list": return 0xFF38BDF8;          // light sky
            case "write":
            case "edit": return 0xFF10B981;          // emerald
            case "patch": return 0xFF8B5CF6;         // violet
            case "glob":
            case "grep": return 0xFFF59E0B;          // amber
            case "webfetch": return 0xFF22D3EE;      // cyan
            case "todowrite": return 0xFFEC4899;     // pink
            case "task": return 0xFF6366F1;          // indigo 2
            default: return 0xFF5A6478;
        }
    }

    /** P10: rounded, hairline code block used for tool input/output. */
    private TextView codeBlock(String content, int maxLen) {
        TextView tv = mono(text(12, R.color.text_primary, false), 12);
        String t = content;
        if (t.length() > maxLen) t = t.substring(0, maxLen) + "…\n(+" + (content.length() - maxLen) + " more chars — long-press to copy all)";
        tv.setText(t);
        // P14: NOT textIsSelectable — selectable spans make TextViews measure
        // and lay out an order of magnitude slower, and a 12k-char block
        // rebuilt on every streaming tick was the "chat bugs out when long
        // commands fill it" report. Long-press copies the full text instead.
        tv.setTextIsSelectable(false);
        tv.setLongClickable(true);
        tv.setOnLongClickListener(v -> {
            copyText(content, "code");
            return true;
        });
        tv.setBackgroundResource(R.drawable.bg_code);
        int p = dp(10);
        tv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void copyText(String s, String label) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, s));
        Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------- permissions

    // P14: unattended mode — ids already auto-answered this session, so a
    // failed reply falls back to the card instead of looping forever.
    private final java.util.Set<String> autoReplied = new java.util.HashSet<>();

    private boolean autoAllowOn() {
        return getSharedPreferences("oc", MODE_PRIVATE)
                .getBoolean("auto_allow", false);
    }

    private void setAutoAllow(boolean on) {
        getSharedPreferences("oc", MODE_PRIVATE).edit()
                .putBoolean("auto_allow", on).apply();
        sys(on ? "⏵ unattended mode ON — tool approvals auto-allowed"
              : "⏵ unattended mode OFF — approvals ask again");
        if (!on) autoReplied.clear();
        checkPermissionQueue();
    }

    private void checkPermissionQueue() {
        Map<String, Object> perm = ServerService.peekPermission();
        if (perm == null) {
            permSlot.setVisibility(View.GONE);
            permSlot.removeAllViews();
            return;
        }
        // P14: AUTO-ALLOW (unattended mode). When the toggle is on, every
        // incoming approval request is answered "always" immediately — the
        // agent runs hands-free ("leave the agent unatended so it doesnt
        // need permision gor every single comand"). The perm slot shows a
        // slim status pill (tap = turn OFF) instead of the blocking card.
        // If the reply fails, the id drops out of autoReplied and the normal
        // card returns on the next tick — the agent can never hang silently.
        if (autoAllowOn()) {
            final String id = Json.str(perm, "id");
            String action = nz(Json.str(perm, "permission"),
                    nz(Json.str(perm, "type"), "tool"));
            if (id != null && !autoReplied.contains(id)) {
                if (autoReplied.size() > 400) autoReplied.clear();
                autoReplied.add(id);
                sys("⏵ auto-allowed " + action);
                answerPermission(id, "always");
            }
            permSlot.setVisibility(View.VISIBLE);
            permSlot.removeAllViews();
            TextView pill = text(12, R.color.ok, true);
            pill.setText("⏵ unattended — auto-allowing " + action
                    + " · tap to turn off");
            pill.setBackgroundResource(R.drawable.bg_sys_pill);
            int pp = dp(12);
            pill.setPadding(pp, dp(7), pp, dp(7));
            pill.setOnClickListener(v -> setAutoAllow(false));
            permSlot.addView(pill);
            return;
        }
        permSlot.setVisibility(View.VISIBLE);
        permSlot.removeAllViews();
        permSlot.addView(buildPermCard(perm));
    }

    private View buildPermCard(Map<String, Object> perm) {
        final String id = Json.str(perm, "id");
        String action = nz(Json.str(perm, "permission"),
                nz(Json.str(perm, "type"), "tool"));
        StringBuilder detail = new StringBuilder();
        Map<String, Object> md = Json.map(perm, "metadata");
        if (md != null) {
            for (String k : new String[]{"title", "description", "path", "command"}) {
                String v = Json.str(md, k);
                if (v != null && !v.isEmpty()) detail.append(v).append('\n');
            }
            Object inp = md.get("input");
            if (inp != null && !"null".equals(String.valueOf(inp))
                    && String.valueOf(inp).trim().length() > 0) {
                detail.append(inp);
            }
        }
        List<Object> pats = Json.list(perm, "patterns");
        if (pats != null && !pats.isEmpty()) detail.append("patterns: ").append(pats);
        if (detail.length() == 0) detail.append("(no details provided)");

        // P10: agent-approval sheet — accent card, big buttons, impossible
        // to miss and impossible to mis-tap.
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_perm_card);
        int cp = dp(14);
        c.setPadding(cp, cp, cp, cp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(13, R.color.warn, false);
        badge.setText("⚠");
        badge.setPadding(0, 0, dp(8), 0);
        head.addView(badge);
        TextView t = text(14, R.color.text_primary, true);
        t.setText("Allow " + action + "?");
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(head);

        TextView d = mono(text(12, R.color.text_secondary, false), 12);
        String dt = detail.toString();
        if (dt.length() > 1500) dt = dt.substring(0, 1500) + "…";
        d.setText(dt);
        d.setBackgroundResource(R.drawable.bg_code);
        int dpc = dp(10);
        d.setPadding(dpc, dpc, dpc, dpc);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(10);
        c.addView(d, dlp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);

        TextView deny = permButton("Deny", "reject", R.drawable.bg_btn_deny, id, R.color.err);
        btns.addView(deny);
        TextView always = permButton("Always allow", "always", R.drawable.bg_btn_outline, id, R.color.accent_light);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        btns.addView(always, alp);
        TextView allow = permButton("Allow", "once", R.drawable.bg_btn_allow, id, R.color.on_accent);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(8);
        btns.addView(allow, vlp);
        c.addView(btns);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    /** P10: one solid, tappable permission button (min touch target 44dp). */
    private TextView permButton(String label, String response, int bgRes, String id, int colorRes) {
        TextView b = text(13, colorRes, true);
        b.setText(label);
        b.setBackgroundResource(bgRes);
        b.setGravity(Gravity.CENTER);
        int p = dp(16);
        b.setPadding(p, dp(11), p, dp(11));
        b.setOnClickListener(v -> {
            Theme.pop(b);
            if (id != null) answerPermission(id, response);
            else {
                Toast.makeText(this, "missing request id — reopening…",
                        Toast.LENGTH_SHORT).show();
                checkPermissionQueue();
            }
        });
        return b;
    }

    private void answerPermission(String id, String response) {
        final String sid = sessionId;
        // P10: dedicated pool — a message POST (or anything else) must never
        // delay a permission reply. Verified against the shipped v1.18.25
        // binary: POST /permission/{requestID}/reply  body {reply, message?}
        // with reply ∈ {once, always, reject}; fallbacks cover older builds.
        permEx.execute(() -> {
            String errS = null;
            boolean ok = false;
            try {
                // v1.18.x verified: the reply body key is "reply"
                Api.Resp r = Api.post("/permission/" + id + "/reply",
                        "{\"reply\":" + Json.quote(response) + "}", 15_000);
                ok = r.ok();
                if (!ok && sid != null) {
                    // v2 surface (shipped binary also serves this)
                    r = Api.post("/api/session/" + sid + "/permission/" + id + "/reply",
                            "{\"reply\":" + Json.quote(response) + "}", 15_000);
                    ok = r.ok();
                }
                if (!ok && sid != null) {
                    // legacy respond shape, oldest builds
                    r = Api.post("/session/" + sid + "/permissions/" + id,
                            "{\"response\":" + Json.quote(response) + "}", 15_000);
                    ok = r.ok();
                }
                if (!ok) errS = "HTTP " + (r == null ? "?" : r.status);
            } catch (Exception e) {
                errS = String.valueOf(e);
            }
            final String f = errS;
            final boolean done = ok;
            ServerService.noteAnswered(id);
            ui.post(() -> {
                if (done) {
                    Toast.makeText(this,
                            "always".equals(response) ? "always allowed"
                                    : "reject".equals(response) ? "denied" : "allowed",
                            Toast.LENGTH_SHORT).show();
                } else {
                    sys("permission reply failed · " + f);
                    Toast.makeText(this, "reply failed — try again", Toast.LENGTH_SHORT).show();
                }
                checkPermissionQueue();
            });
        });
    }

    // ----------------------------------------------------------- palette

    private void palette() {
        final String[] cmds = {
                "New chat", "Sessions…", "Model…", "Toggle Build / Plan",
                autoAllowOn() ? "Turn OFF unattended (auto-allow)"
                              : "Turn ON unattended (auto-allow)",
                "Project files →", "Sandbox environment",
                "Projects →", "Settings",
                "API keys…", "Server logs & shell…", "Restart server",
                "Expand all cards", "Collapse all cards",
                "Copy last response", "Export chat to Downloads"};
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("⌘ commands");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        wrap.setPadding(p, dp(8), p, dp(8));
        final EditText filter = new EditText(this);
        filter.setHint("filter…");
        filter.setTextSize(14);
        filter.setSingleLine(true);
        wrap.addView(filter);
        final ListView lv = new ListView(this);
        final List<String> visible = new ArrayList<>();
        final android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, visible);
        lv.setAdapter(ad);
        wrap.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        b.setView(wrap);
        final AlertDialog dlg = b.create();
        Runnable refill = () -> {
            String q = filter.getText().toString().toLowerCase(Locale.US);
            visible.clear();
            for (String c : cmds) if (c.toLowerCase(Locale.US).contains(q)) visible.add(c);
            ad.notifyDataSetChanged();
        };
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c2, int d) {}
            public void onTextChanged(CharSequence s, int a, int c2, int d) {}
            public void afterTextChanged(Editable s) { refill.run(); }
        });
        refill.run();
        lv.setOnItemClickListener((parent, v, pos, id2) -> {
            dlg.dismiss();
            runCommand(visible.get(pos));
        });
        dlg.show();
    }

    private void runCommand(String cmd) {
        switch (cmd) {
            case "New chat": loadSession(null); break;
            case "Sessions…": sessionsSheet(); break;
            case "Model…": modelSheet(); break;
            case "Project files →":
                startActivity(new Intent(this, FilesActivity.class)); break;
            case "Sandbox environment":
                ex.execute(() -> {
                    final String rep = Debian.envReport(this);
                    ui.post(() -> sys(rep));
                });
                break;
            case "Toggle Build / Plan": toggleMode(); break;
            case "Turn ON unattended (auto-allow)":
            case "Turn OFF unattended (auto-allow)":
                setAutoAllow(!autoAllowOn()); break;
            case "Projects →":
                startActivity(new Intent(this, HomeActivity.class)); break;
            case "Settings":
                startActivity(new Intent(this, SettingsActivity.class)); break;
            case "API keys…":
                startActivity(new Intent(this, KeysActivity.class)); break;
            case "Server logs & shell…":
                startActivity(new Intent(this, DiagnosticsActivity.class)); break;
            case "Restart server":
                ServerService.restart(this);
                Toast.makeText(this, "restarting server…", Toast.LENGTH_SHORT).show();
                break;
            case "Expand all cards": setAllOpen(true); break;
            case "Collapse all cards": setAllOpen(false); break;
            case "Copy last response": copyLast(); break;
            case "Export chat to Downloads": exportChat(); break;
            default: break;
        }
    }

    // ---------------------------------------------------------- sessions

    private static final class SessRow {
        String id, title; double updated;
    }

    private void sessionsSheet() {
        ex.execute(() -> {
            final List<SessRow> listS = new ArrayList<>();
            try {
                Api.Resp r = Api.get("/session");
                if (r.ok()) {
                    List<Object> arr = Json.arr(Json.parse(r.body));
                    if (arr != null) for (Object o : arr) {
                        Map<String, Object> m = Json.obj(o);
                        if (m == null) continue;
                        SessRow s = new SessRow();
                        s.id = Json.str(m, "id");
                        s.title = nz(Json.str(m, "title"), "untitled");
                        Map<String, Object> time = Json.map(m, "time");
                        s.updated = time != null ? num(time.get("updated")) : 0;
                        if (s.id != null) listS.add(s);
                    }
                }
            } catch (Exception ignored) {}
            listS.sort((a, b) -> Double.compare(b.updated, a.updated));
            ui.post(() -> showSessions(listS));
        });
    }

    private void showSessions(List<SessRow> listS) {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Sessions");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        ListView lv = new ListView(this);
        List<Object[]> items = new ArrayList<>(); // [titleLine, timeLine, SessRow|null]
        items.add(new Object[]{"＋  New chat", "start over", null});
        for (SessRow s : listS) items.add(new Object[]{s.title, relTime(s.updated), s});
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int i) { return items.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup parent) {
                LinearLayout box = new LinearLayout(ChatActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(14);
                box.setPadding(pad, dp(9), pad, dp(9));
                Object[] it = items.get(i);
                TextView t1 = text(14, i == 0 ? R.color.accent_light : R.color.text_primary, i == 0);
                t1.setText(String.valueOf(it[0]));
                t1.setSingleLine(true);
                t1.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                TextView t2 = text(11, R.color.text_secondary, false);
                t2.setText(String.valueOf(it[1]));
                box.addView(t1);
                box.addView(t2);
                return box;
            }
        });
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        b.setView(root);
        final AlertDialog dlg = b.create();
        lv.setOnItemClickListener((parent, v, pos, id3) -> {
            dlg.dismiss();
            Object[] it = items.get(pos);
            if (it[2] == null) loadSession(null);
            else loadSession(((SessRow) it[2]).id);
        });
        lv.setOnItemLongClickListener((parent, v, pos, id4) -> {
            Object[] it = items.get(pos);
            if (!(it[2] instanceof SessRow)) return true;
            SessRow s = (SessRow) it[2];
            new AlertDialog.Builder(this)
                    .setTitle(s.title)
                    .setItems(new String[]{"Open", "Delete"}, (d, w) -> {
                        if (w == 0) { dlg.dismiss(); loadSession(s.id); }
                        else confirmDelete(s);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        dlg.show();
    }

    private void confirmDelete(SessRow s) {
        new AlertDialog.Builder(this)
                .setTitle("Delete session?")
                .setMessage(s.title)
                .setPositiveButton("Delete", (d, w) -> ex.execute(() -> {
                    try {
                        Api.Resp r = Api.call("DELETE", "/session/" + s.id, null, 10_000);
                        if (!r.ok()) sys("delete failed · HTTP " + r.status);
                        else if (s.id.equals(sessionId)) loadSession(null);
                    } catch (Exception e) {
                        sys("delete failed: " + e);
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ models

    private void modelSheet() {
        // P13: NO server gate anymore — the sheet opens regardless and shows
        // what it sees ("server offline · bundled catalog") instead of
        // dead-ending on a toast. The P11-era gate made the picker look
        // broken whenever the server was still booting.
        ex.execute(() -> {
            List<Models.Prov> provs = Models.fetch(this);
            ui.post(() -> showModels(provs));
        });
    }

    /**
     * P15 — the model sheet, rebuilt TWICE over:
     *
     * BEHAVIOR = the FIRST P12 again (the user: "the working model picker
     * is in the first p12 that was uploaded to github"). P14's rework
     * dropped Mdl.live, so every discovery-catalog row SAVED silently and
     * the server then answered "Model not found" — the picker "looked
     * broken" a third time. Restored from the P12a source, byte-for-byte
     * in spirit:
     *   • live models (the running server serves them NOW) first, bright
     *   • discovery rows dim + "· catalog" tag
     *   • tapping a discovery row REFUSES with a plain-language toast
     *   • validateSelectedModel() self-heals stale picks again (live gate)
     * plus P13/P14 keepers: source line, ⟨free⟩ badges, $/Mtok, 88% sheet,
     * row recycling — and one NEW rule: a provider header tap never steals
     * the whole sheet anymore (P14 dismissed to Keys; felt like a bug).
     */
    private void showModels(List<Models.Prov> provs) {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Model · all providers");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(8), p, 0);

        // P15 header: live counts + data source, then the search well.
        TextView srcLine = text(11, R.color.text_secondary, false);
        int liveN = 0, freeN = 0;
        for (Models.Prov pr : provs)
            for (Models.Mdl m : pr.models) {
                if (m.live) liveN++;
                if (m.free) freeN++;
            }
        srcLine.setText(liveN + " runnable now · " + countModels(provs)
                + " in catalog · " + freeN + " free · " + Models.lastSource);
        srcLine.setPadding(dp(4), 0, dp(4), dp(6));
        root.addView(srcLine);

        final EditText search = new EditText(this);
        search.setHint("search " + countModels(provs) + " models…");
        search.setTextSize(14);
        search.setSingleLine(true);
        root.addView(search);

        final ListView lv = new ListView(this);
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = text(11, R.color.text_secondary, false);
        hint.setText("bright = runs now · dim = catalog (needs its key) · "
                + "tap a dim row to see why · ⌘ → API keys to add one");
        hint.setPadding(dp(4), dp(8), dp(4), dp(10));
        root.addView(hint);
        b.setView(root);
        final AlertDialog dlg = b.create();
        dlg.show();
        android.view.Window w = dlg.getWindow();
        if (w != null) w.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.88));

        final List<Object[]> items = new ArrayList<>(); // [kind, Prov, Mdl-or-tag]
        Runnable refill = () -> {
            String q = search.getText().toString().toLowerCase(Locale.US).trim();
            items.clear();
            String[] cur = Models.selected(this);
            // P12a restored: within a provider, live models first.
            java.util.Comparator<Models.Mdl> liveFirst = (a, b2) -> {
                if (a.live != b2.live) return a.live ? -1 : 1;
                return a.name.compareToIgnoreCase(b2.name);
            };
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
                shown.sort(liveFirst);
                items.add(new Object[]{"h", pr, null});
                int cap = Math.min(shown.size(), 400);
                for (int i = 0; i < cap; i++) items.add(new Object[]{"m", pr, shown.get(i)});
                if (shown.size() > cap) items.add(new Object[]{"t", pr,
                        "… " + (shown.size() - cap) + " more (refine search)"});
            }
            lv.setAdapter(new BaseAdapter() {
                public int getCount() { return items.size(); }
                public Object getItem(int i) { return items.get(i); }
                public long getItemId(int i) { return i; }
                public View getView(int i, View cv, ViewGroup parent) {
                    Object[] it = items.get(i);
                    LinearLayout box = (cv instanceof LinearLayout)
                            ? (LinearLayout) cv : new LinearLayout(ChatActivity.this);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.removeAllViews();
                    int pad = dp(14);
                    box.setPadding(pad, dp(8), pad, dp(8));
                    if ("h".equals(it[0])) {
                        // P15 provider header: pill tag state, mono id chip.
                        Models.Prov pr = (Models.Prov) it[1];
                        TextView t = text(13, pr.usable ? R.color.ok
                                : pr.configured ? R.color.accent_light
                                : R.color.text_secondary, true);
                        String mark;
                        if (pr.usable && "opencode".equals(pr.id))
                            mark = "  ·  free · no key needed";   // P11 verified
                        else if (pr.usable) mark = "  ·  ready";
                        else if (pr.configured) mark = "  ·  key saved · restarting picks it up";
                        else mark = "  ·  discovery — add API key first";
                        t.setText(pr.name + mark);
                        t.setSingleLine(true);
                        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        box.addView(t);
                    } else if ("m".equals(it[0])) {
                        Models.Mdl m = (Models.Mdl) it[2];
                        Models.Prov pr = (Models.Prov) it[1];
                        boolean isCur = cur != null && cur[0].equals(pr.id)
                                && cur[1].equals(m.id);
                        // P12a rendering: live rows bright, catalog rows dim
                        // and tagged — the state that told the user, at a
                        // glance, exactly what would work.
                        int col = isCur ? R.color.ok
                                : m.live ? R.color.text_primary : R.color.text_secondary;
                        TextView t1 = text(14, col, isCur || m.live);
                        t1.setText((isCur ? "✓ " : "") + m.name
                                + (m.live ? "" : "   ·  catalog")
                                + (m.free ? "   ⟨free⟩" : ""));
                        t1.setSingleLine(true);
                        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        TextView t2 = text(11, R.color.text_secondary, false);
                        t2.setText(pr.id + "/" + m.id
                                + (!m.free && m.costIn > 0
                                        ? String.format(Locale.US,
                                          "   · $%g in / $%g out per Mtok",
                                          m.costIn, m.costOut) : ""));
                        t2.setSingleLine(true);
                        t2.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                        box.addView(t1);
                        box.addView(t2);
                    } else {
                        TextView t = text(12, R.color.text_secondary, false);
                        t.setText(String.valueOf(it[2]));
                        box.addView(t);
                    }
                    return box;
                }
            });
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c2, int d) {}
            public void onTextChanged(CharSequence s, int a, int c2, int d) {}
            public void afterTextChanged(Editable s) { refill.run(); }
        });
        refill.run();
        lv.setOnItemClickListener((parent, v, pos, id4) -> {
            Object[] it = items.get(pos);
            if ("h".equals(it[0])) {
                // P15: headers never steal the sheet. Unconfigured provider →
                // point at the keys screen in place (P14 closed the whole
                // picker here, which read as "tapping the picker breaks it").
                Models.Prov pr = (Models.Prov) it[1];
                if (!pr.usable && !pr.configured)
                    Toast.makeText(this, pr.name + " needs its API key — "
                            + "⌘ → API keys, paste, then reopen this sheet",
                            Toast.LENGTH_SHORT).show();
                return;
            }
            if (!"m".equals(it[0])) return;
            Models.Prov pr = (Models.Prov) it[1];
            Models.Mdl m = (Models.Mdl) it[2];
            // P12a restored: discovery-catalog entries are NOT selectable —
            // the server would answer "Model not found". Say why instead.
            if (!m.live) {
                Toast.makeText(this, pr.usable
                        ? m.name + " is in the discovery catalog but not offered by "
                          + "your server right now (free list rotates) — pick a "
                          + "non-tagged model"
                        : "no key for " + pr.name + " yet — ⌘ → API keys first",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Models.save(this, pr.id, m.id);
            // P11: chat picks are PER-CHAT only — writing them into
            // opencode.json made every future session (and every fallback
            // body) hostage to a model the catalog can rotate away.
            // Server-wide default stays in Settings → Default model.
            refreshChips();
            Toast.makeText(this, (!pr.configured && !"opencode".equals(pr.id)
                    ? "no key yet for " + pr.name + " — ⌘ → API keys · " : "")
                    + "model → " + pr.id + "/" + m.id
                    + (m.free ? " (free)" : ""),
                    Toast.LENGTH_LONG).show();
            dlg.dismiss();
        });
    }

    private int countModels(List<Models.Prov> provs) {
        int n = 0;
        for (Models.Prov p : provs) n += p.models.size();
        return n;
    }

    // ------------------------------------------------------------- misc

    private void copyLast() {
        String last = null;
        synchronized (lock) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                Row r = rows.get(i);
                if (r.kind == K_ASSISTANT && r.text.length() > 0) {
                    last = r.text.toString();
                    break;
                }
            }
        }
        if (last == null) {
            Toast.makeText(this, "nothing to copy yet", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("response", last));
        Toast.makeText(this, "copied " + last.length() + " chars", Toast.LENGTH_SHORT).show();
    }

    private static final int REQ_EXPORT = 41;
    private boolean exportPending;

    private void exportChat() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            exportPending = true;
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_EXPORT);
            return;
        }
        doExport();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_EXPORT) {
            if (exportPending) { exportPending = false; doExport(); }
        }
    }

    private void doExport() {
        ex.execute(() -> {
            StringBuilder sb = new StringBuilder();
            synchronized (lock) {
                for (Row r : rows) {
                    switch (r.kind) {
                        case K_USER:
                            sb.append("## you\n").append(r.text).append("\n\n");
                            break;
                        case K_ASSISTANT:
                            sb.append("## agent").append(
                                    r.meta == null ? "" : "  (" + r.meta + ")")
                                    .append('\n').append(r.text).append("\n\n");
                            break;
                        case K_REASON:
                            sb.append("> thinking: ").append(r.text).append("\n\n");
                            break;
                        case K_TOOL:
                            sb.append("> tool ").append(r.tool).append(" [")
                                    .append(r.status).append("] ")
                                    .append(r.title).append('\n');
                            if (r.output.length() > 0)
                                sb.append("```\n").append(r.output).append("\n```\n");
                            sb.append('\n');
                            break;
                        case K_ERR:
                            sb.append("!! ").append(r.text).append(' ')
                                    .append(r.output).append("\n\n");
                            break;
                        default:
                            sb.append("-- ").append(r.text).append("\n\n");
                    }
                }
            }
            String out;
            String where;
            try {
                File dir = android.os.Environment
                        .getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                        .format(new Date());
                File f = new File(dir, "opencode-chat-" + ts + ".txt");
                try (OutputStream o = new FileOutputStream(f)) {
                    o.write(sb.toString().getBytes("UTF-8"));
                }
                out = "saved " + f.getName();
                where = f.getAbsolutePath();
            } catch (Exception e) {
                try {
                    File f = new File(getExternalFilesDir(null),
                            "opencode-chat-" + System.currentTimeMillis() + ".txt");
                    try (OutputStream o = new FileOutputStream(f)) {
                        o.write(sb.toString().getBytes("UTF-8"));
                    }
                    out = "saved " + f.getAbsolutePath();
                    where = out;
                } catch (Exception e2) {
                    out = "export failed: " + e2;
                    where = null;
                }
            }
            final String msg = out;
            final String fWhere = where;
            ui.post(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                if (fWhere != null) sys("chat exported → " + fWhere);
            });
        });
    }

    // ---------------------------------------------------------- helpers

    private static double num(Object o) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : 0;
    }

    private static String nz(String s, String dflt) {
        return (s == null || s.isEmpty()) ? dflt : s;
    }

    private static String relTime(double sec) {
        if (sec <= 0) return "";
        long ms = (long) (sec * 1000.0);
        long diff = System.currentTimeMillis() - ms;
        if (diff < 0) diff = 0;
        long m = diff / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        return (h / 24) + " d ago";
    }
}
