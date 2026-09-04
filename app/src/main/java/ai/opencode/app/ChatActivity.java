package ai.opencode.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.ImageView;
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
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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
        implements ServerService.Evt, RunHub.Ui {

    // row kinds — owned by RunHub since P25; re-exported for the render switch
    static final int K_USER = RunHub.K_USER, K_ASSISTANT = RunHub.K_ASSISTANT,
            K_REASON = RunHub.K_REASON, K_TOOL = RunHub.K_TOOL, K_SYS = RunHub.K_SYS,
            K_ERR = RunHub.K_ERR;
    // P17 row kinds
    static final int K_LIVE = RunHub.K_LIVE, K_IMAGE = RunHub.K_IMAGE;
    /** The one live-edit card's stable row key. */
    private static final String LIVE_KEY = RunHub.LIVE_KEY;
    /** SAF image-picker request code. */
    private static final int REQ_IMAGE = 4210;

    // P8: which project sandbox this chat is attached to (from the deck)
    private String projectName;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /**
     * P10 — was a single-thread executor, which was a deadlock in disguise:
     * POST /session/{id}/message held its thread until the agent run
     * finished, so a permission reply queued behind it and never ran.
     * P25: the message POST itself moved to RunHub's pool — this pool now
     * serves UI-adjacent async work only (sheets, exports, decodes).
     */
    private final ExecutorService ex = Executors.newCachedThreadPool();

    // ---- P25: the view binds the hub's transcript — single source ------
    // `rows` is a REFERENCE to the hub's live list for the displayed
    // session; it is re-fetched whenever the hub swaps sessions
    // (hubReset / onResume). Locking uses the hub's lock so cross-thread
    // readers (export, copy) stay coherent with the model.
    private static final Object lock = RunHub.lock();
    private List<RunHub.Row> rows = RunHub.rows();
    private boolean pinnedBottom = true;

    private RunHub.Row rowByKey(String key) {
        Integer i = key == null ? null : RunHub.idx().get(key);
        List<RunHub.Row> rs = RunHub.rows();
        return (i == null || i >= rs.size()) ? null : rs.get(i);
    }

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
    private View veilDot;                   // P17: direct handle for the pulse
    private ObjectAnimator veilPulse;       // P17: CANCELED when the veil hides —
    //                                          the P16 code pulsed the veil dot
    //                                          with an INFINITE animator while
    //                                          the veil sat GONE 24/7: real idle
    //                                          burn on the main screen.
    private LinearLayout typing;
    private TextView scrollPill;
    private boolean pillShown;
    private final List<ObjectAnimator> typingAnims = new ArrayList<>();

    // ---- P17/P25: live edit feed — STATE lives in RunHub, the VIEW here
    // renders it. The DirWatcher runs while a run is active (hub-owned),
    // so the shower keeps recording with the chat closed. The view keeps
    // only its own bits: the pulse animator and the tree's dir states.
    private ObjectAnimator livePulseAnim;
    /** P25 tree: user-decided dir expand/collapse (null = auto: follow
     *  the newest event's branch). Bounded; cleared on session resets. */
    private final java.util.Map<String, Boolean> dirState = new java.util.HashMap<>();

    // ---- P17: vision ---------------------------------------------------
    private TextView btnVision;

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
        applyWideLayout();                   // P16 DeX: centered column on wide windows

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
        btnVision = findViewById(R.id.btnVision);
        if (btnVision != null) btnVision.setOnClickListener(v -> pickImage());
        chipMode.setOnClickListener(v -> toggleMode());
        chipModel.setOnClickListener(v -> modelSheet());
        if (tvSpend != null) tvSpend.setOnClickListener(v -> spendPopover());   // P18
        btnSend.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (RunHub.busy()) {
                RunHub.abort();              // P25: the ONLY abort path
            } else {
                String q = input.getText().toString().trim();
                if (q.isEmpty()) return;
                input.setText("");
                Theme.pop(btnSend);          // P8 micro-anim: the send button springs
                RunHub.send(q);              // hub-owned: the run outlives this screen
            }
        });
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
        int st = ServerService.getState();
        if ((st == ServerService.ST_IDLE || st == ServerService.ST_STOPPED
                || st == ServerService.ST_EXITED) && !ServerService.pendingRestart()) {
            if (Binaries.binaryReady(this)) {
                try {
                    startForegroundService(new Intent(this, ServerService.class));
                } catch (Exception ignored) {}
            }
        }
        // P25: bind to the live run state. The hub consumed every event
        // while this screen was away; re-pull the session from the server
        // API as the belt to that brace (never re-POSTs, never restarts
        // a healthy stream), restore the interrupted-run note, render.
        RunHub.bindUi(this);
        rows = RunHub.rows();
        String interrupted = RunHub.consumeInterruptedNote();
        boolean emptyStart = RunHub.rows().isEmpty();
        if (RunHub.sessionId() != null && emptyStart) {
            // P25 process-kill recovery (or first open after boot): the
            // run-state file restored the session — pull its transcript.
            RunHub.loadSession(RunHub.sessionId());
        } else if (RunHub.sessionId() != null) {
            RunHub.reconcileOnBind();            // P20 replay, now hub-owned
        }
        // the note lands AFTER the (re)load — the transcript swap must
        // never wipe it
        if (interrupted != null) RunHub.sys(interrupted);
        checkPermissionQueue();
        refreshServerUi();
        refreshChips();
        // P16: returning from API keys with the model sheet open → refresh
        // the rows IN PLACE, so the provider whose key was just added goes
        // bright/ready without closing and reopening the picker.
        if (keysFromSheet) {
            keysFromSheet = false;
            if (modelDlg != null && modelDlg.isShowing() && !isFinishing()
                    && !isDestroyed()) {
                ex.execute(() -> {
                    Throwable t = Resilience.guard(() -> {
                        List<Models.Prov> fresh = Models.fetch(ChatActivity.this);
                        ui.post(() -> {
                            if (modelDlg == null || !modelDlg.isShowing()) return;
                            sheetProvs = fresh;
                            if (sheetRefill != null) sheetRefill.run();
                            Toast.makeText(ChatActivity.this,
                                    "providers refreshed — your key is in",
                                    Toast.LENGTH_SHORT).show();
                        });
                    });
                    if (t != null) Trail.record(ChatActivity.this, "provider refresh", t);
                });
            }
        }
    }

    /** P16 DeX: hardware-keyboard shortcuts (desktop-opencode muscle
     *  memory): Ctrl+M models · Ctrl+K palette · Ctrl+J sessions. */
    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (event != null && event.isCtrlPressed() && !event.isShiftPressed()
                && !event.isAltPressed()) {
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_M: modelSheet(); return true;
                case android.view.KeyEvent.KEYCODE_K: palette(); return true;
                case android.view.KeyEvent.KEYCODE_J: sessionsSheet(); return true;
                default: break;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onPause() {
        RunHub.unbindUi(this);
        // P15: drop pending paint work with the unbind — the flush would
        // fire into a detached view tree on return (harmless but wasteful).
        ui.removeCallbacks(flushPaints);
        paintScheduled = false;
        dirtyRows.clear();
        if (livePulseAnim != null) { livePulseAnim.cancel(); livePulseAnim = null; }
        // P25: that is ALL. The run, the SSE feed, the transcript, the
        // edit watcher — all live in RunHub now. Leaving to the deck keeps
        // the run streaming; nothing here can abort it (only ■ can).
        super.onPause();
    }

    // ------------------------------------------------ P25: RunHub.Ui
    // The view contract: the hub mutates its model (any thread → main),
 // the view paints. Every callback arrives on the main thread.

    @Override public void hubRow(String key) {
        RunHub.Row r = key == null ? null : rowByKey(key);
        if (r != null) requestPaint(r);
    }

    @Override public void hubBusy(boolean b) {
        applyBusyUi(b);
    }

    @Override public void hubSpend() {
        ui.post(this::refreshServerUi);
    }

    @Override public void hubTitle() {
        ui.post(() -> {
            String t = RunHub.sessionTitle();
            if (t != null && !t.isEmpty()) tvTitle.setText(t);
            refreshChips();
        });
    }

    @Override public void hubPerms() {
        ui.post(this::checkPermissionQueue);
    }

    @Override public void hubLive() {
        RunHub.Row r = rowByKey(LIVE_KEY);
        if (r != null) requestPaint(r);
    }

    /** The transcript was replaced (session switch / model trim): rebuild
     *  everything once. Also re-syncs the `rows` reference. */
    @Override public void hubReset() {
        ui.post(() -> {
            rows = RunHub.rows();
            dirtyRows.clear();
            paintScheduled = false;
            ui.removeCallbacks(flushPaints);
            dirState.clear();
            viewByKey.clear();
            bodyByKey.clear();
            metaByKey.clear();
            pinnedBottom = true;
            renderAll();
            syncEmpty();
        });
    }

    /** P17: SAF image pick for the vision flow (no permission needed). */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            confirmImage(data.getData());
        }
    }

    // --------------------------------------------------- P16 DeX / wide

    /** Desktop (DeX) comfort: on wide windows the transcript, permission
     *  slot and composer collapse to a centered ~720 dp column — the
     *  desktop-opencode silhouette instead of a stretched phone app.
     *  Re-applied on every window resize (configChanges handled). */
    private void applyWideLayout() {
        android.content.res.Configuration cfg = getResources().getConfiguration();
        int wdp = cfg.screenWidthDp;
        if (wdp <= 0) wdp = getResources().getDisplayMetrics().widthPixels;
        boolean wide = wdp >= 600;
        int inset = wide ? Theme.dp(this,
                Math.min(200, Math.max(14, (wdp - 720) / 2 + 14))) : 0;
        if (list != null) list.setPadding(inset > 0 ? inset : dp(14),
                dp(14), inset > 0 ? inset : dp(14), dp(4));
        View composer = findViewById(R.id.composerBar);
        if (composer != null) composer.setPadding(inset, dp(8), inset, dp(10));
        if (permSlot != null) permSlot.setPadding(inset > 0 ? inset : dp(10),
                0, inset > 0 ? inset : dp(10), 0);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newCfg) {
        super.onConfigurationChanged(newCfg);
        ui.post(this::applyWideLayout);   // DeX window resizes land here
    }

    private boolean atBottom() {
        int off = scroll.getScrollY() + scroll.getHeight() - list.getHeight();
        return off > -80;
    }

    private void autoscroll() {
        if (pinnedBottom) scroll.post(this::scrollToEnd);
    }

    /** P21: focus-free scroll to the end of the list. ScrollView's
     *  fullScroll() runs the focus search and can MOVE focus into the
     *  message list — selectable rows are focusable — so mid-run the
     *  keyboard kept detaching from the chat box (P20 field report:
     *  "keeps making the keyboard unfocus from the chatbox"). Identical
     *  scroll position, zero focus side effects. */
    private void scrollToEnd() {
        View c = scroll.getChildAt(0);
        int bottom = c == null ? scroll.getHeight() : c.getHeight();
        scroll.scrollTo(0, Math.max(0, bottom - scroll.getHeight()));
    }

    // ------------------------------------------------- P9 stream smoother

    /**
     * Token-by-token feel: SSE deltas land in RunHub.Row.text (the target) and a
     * 24 ms ticker paints a few more chars into the row's TextView with a
     * caret — plain text while streaming (cheap), full markdown ONCE when
     * the part catches up. Adaptive step = remaining/8 + 3 chars, so short
     * replies land instantly and long ones glide.
     *
     * P20: the ticker now also drives THINKING rows — the assistant text
     * already glided, but reasoning rows painted in raw SSE bursts; the
     * collapsed card grows a live one-line ticker of the freshest
     * thinking, and an open card streams its body with the caret.
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
    private final java.util.LinkedHashSet<RunHub.Row> dirtyRows = new java.util.LinkedHashSet<>();
    private boolean paintScheduled;

    private final Runnable flushPaints = this::flushPaintsNow;

    void requestPaint(RunHub.Row r) {           // package-private: P24 test seam
        dirtyRows.add(r);
        if (!paintScheduled) {
            paintScheduled = true;
            ui.postDelayed(flushPaints, PAINT_THROTTLE_MS);
        }
    }

    /** P24: paint exactly one row — the fault-isolation seam. Package-
     *  private so the Robolectric suite can poison it and prove the
     *  transcript survives a row that cannot paint. */
    void paintRowOnce(RunHub.Row r) { touchView(r); }

    /** P24 field lesson ("doesn't crash but it still won't work"): P23's
     *  single guard around the WHOLE batch meant ONE poisoned row aborted
     *  every row painted after it — and the feed re-dirties that row on
     *  every delta/file event, so every flush re-failed: a banner wall
     *  plus a frozen transcript, with the run itself still healthy. From
     *  P24 on each row fails ALONE: batch continues, repeat offenders are
     *  quarantined into a can't-fail fallback line. */
    void flushPaintsNow() {              // package-private: test seam
        guarded("paint flush", () -> {
            paintScheduled = false;
            if (dirtyRows.isEmpty()) return;
            java.util.ArrayList<RunHub.Row> batch = new java.util.ArrayList<>(dirtyRows);
            dirtyRows.clear();
            List<RunHub.Row> rs = RunHub.rows();
            java.util.Map<String, Integer> idx = RunHub.idx();
            for (RunHub.Row r : batch) {
                Integer i = r.key == null ? null : idx.get(r.key);
                if (i == null || i >= rs.size() || rs.get(i) != r) continue;
                if (r.key != null && quarantined.contains(r.key)) continue;
                final RunHub.Row fr = r;
                Throwable t = Resilience.guard(() -> paintRowOnce(fr));
                if (t == null) {
                    if (r.key != null) paintFails.remove(r.key);  // second chance restored
                } else {
                    noteRowPaintFail(fr, t);
                }
            }
            autoscroll();
        });
    }

    /** P24: one row's paint failed. Count it; after
     *  {@link Resilience#paintFailQuarantineAfter()} failures quarantine
     *  the row — content swapped for the bounded fallback line that
     *  cannot fail. The part degrades VISIBLY; the chat keeps flowing.
     *  Never throws (it runs inside containment contexts). */
    private void noteRowPaintFail(RunHub.Row r, Throwable t) {
        try {
            if (r.key == null) return;      // unkeyed rows: trail only
            String k = r.key;
            Trail.record(this, "paint row " + k, t);
            Integer c = paintFails.get(k);
            int n = c == null ? 1 : c + 1;
            paintFails.put(k, n);
            if (n < Resilience.paintFailQuarantineAfter()) return;
            paintFails.remove(k);
            quarantined.add(k);
            ui.post(() -> guarded("quarantine swap", () -> {
                RunHub.Row qr = rowByKey(k);
                if (qr == null) return;
                if (qr.kind == K_LIVE) return;   // the live card manages itself
                synchronized (lock) {
                    qr.kind = K_SYS;
                    qr.text.setLength(0);
                    qr.text.append(Resilience.quarantineLine());
                    qr.input.setLength(0); qr.output.setLength(0);
                    qr.open = false; qr.livePreview = false;
                    qr.shown = qr.text.length();
                }
                touchView(qr);
            }));
        } catch (Throwable ignored) {}
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            // P23: the ticker paints every streaming delta on the main
            // thread — a throw here used to kill the process mid-stream.
            Throwable t = Resilience.guard(() -> {
                boolean[] moreBox = {false};
                synchronized (lock) {
                    for (int i = rows.size() - 1; i >= 0; i--) {
                        RunHub.Row r = rows.get(i);
                        int len = r.text.length();
                        if (r.shown > len) r.shown = len;
                        if ((r.kind != K_ASSISTANT && r.kind != K_REASON)
                                || r.shown >= len) continue;
                        if (i != rows.size() - 1) {
                            boolean wasBehind = r.shown < len;
                            r.shown = len;      // a newer row appeared: snap
                            if (wasBehind) requestPaint(r);
                        } else {
                            int step = 3 + (len - r.shown) / 8;
                            r.shown = Math.min(len, r.shown + step);
                        }
                        // P24: a row that can't paint must not kill the
                        // whole ticker — quarantine it like a flush failure.
                        final RunHub.Row fr = r;
                        Throwable pt = Resilience.guard(() -> paintStreaming(fr));
                        if (pt != null) noteRowPaintFail(fr, pt);
                        if (r.shown < len) moreBox[0] = true;
                    }
                }
                if (pinnedBottom) scrollToEnd();
                if (moreBox[0]) smoother.postDelayed(this, 24);
                else smootherRunning = false;
            });
            if (t != null) {
                smootherRunning = false;
                contained("stream ticker", t);
            }
        }
    };

    /** In-place text paint while streaming (no view rebuilds). */
    private void paintStreaming(RunHub.Row r) {
        TextView body = bodyByKey.get(r.key);
        View root = viewByKey.get(r.key);
        if (body == null || root == null) { touchView(r); return; }
        int len = r.text.length();
        int upto = Math.min(r.shown, len);
        if (r.kind == K_REASON && !r.open) {
            // P20: collapsed thinking card → one live line of the FRESHEST
            // thinking under the header (a sliding window, token-by-token)
            String win = Resilience.thinkWindow(r.text.toString(), upto, 110);
            body.setText(win.length() == 0 ? "…" : win + "▍");
        } else {
            String s = r.text.substring(0, upto);
            body.setText(s.length() == 0 ? "…" : s + "▍");
        }
        if (r.shown >= len) {
            // caught up → finalize with markdown (single rebuild)
            touchView(r);
        }
    }

    private boolean isStreamingTail(RunHub.Row r) {
        if (!Theme.motionOn(this)) return false;
        synchronized (lock) {
            boolean last = !rows.isEmpty() && rows.get(rows.size() - 1) == r;
            boolean streamable = r.kind == K_ASSISTANT || r.kind == K_REASON;
            return last && streamable && r.shown < r.text.length()
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
            c.setPadding(p, dp(11), p, dp(11));   // P25: taller touch target
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
        veilDot = dot;
        // P17: pulse moved to syncVeil — starts when the veil shows,
        // cancels when it hides (the old unconditional start here pulsed
        // an INVISIBLE view 24/7 — the idle-CPU bug the user felt as heat).

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
        // P17: the pulse now starts/stops WITH the veil (syncVeil) — the old
        // code started an INFINITE animator here on a view that stays GONE
        // while the server is healthy: invisible 60 fps work, forever.
    }

    private void syncVeil(int st) {
        if (veil == null) return;
        boolean show = st != ServerService.ST_HEALTHY;
        int vis = show ? View.VISIBLE : View.GONE;
        if (veil.getVisibility() != vis) {
            veil.setVisibility(vis);
            if (show) {
                if (Theme.motionOn(this)) {
                    if (veilPulse != null) veilPulse.cancel();
                    if (veilDot != null) veilPulse = Theme.pulse(veilDot);
                }
                Theme.appear(veil);
            } else if (veilPulse != null) {
                veilPulse.cancel(); veilPulse = null;   // P17: stop the burn
            }
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
        int vis = RunHub.busy() ? View.VISIBLE : View.GONE;
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
            scroll.smoothScrollTo(0,
                    Math.max(0, list.getHeight() - scroll.getHeight()));
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
        RunHub.setAgent("build".equals(RunHub.agent()) ? "plan" : "build");
        refreshChips();
        Theme.pop(chipMode);
        Toast.makeText(this, "agent: " + RunHub.agent(), Toast.LENGTH_SHORT).show();
    }

    private void refreshChips() {
        boolean plan = "plan".equals(RunHub.agent());
        chipMode.setText(plan ? "Plan" : "Build");
        chipMode.setTextColor(getColor(plan ? R.color.accent_light : R.color.ok));
        String[] sel = Models.selected(this);
        chipModel.setText(sel == null ? "model ▾" : sel[1]);
    }

    /** P25: the BUSY UI only — button, status line, typing dots. The busy
     *  STATE lives in RunHub (it flips from SSE events, sends, settles —
     *  with or without this screen). Delivered on main by hubBusy. */
    private void applyBusyUi(boolean b) {
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
    }

    // ---- P23: blast-radius zero ----------------------------------------
    // The field device died on send with an unhandled Java exception while
    // every audited stage had catch(Exception): the hole was Errors (OOM,
    // linkage) + the thread boundaries between them. Contract from P23 on:
    // NOTHING on the send/chat/feed paths kills the process — a Throwable
    // is contained, recorded to the guard trail, and shown as one line.
    private final java.util.Map<String, Long> containedAt = new HashMap<>();
    private final java.util.Map<String, Integer> containedCount = new HashMap<>();
    // P24: per-row paint fault bookkeeping. paintFails counts attempts per
    // row key; quarantined keys are skipped by every flush forever (their
    // content was swapped for a can't-fail fallback line).
    private final java.util.Map<String, Integer> paintFails = new HashMap<>();
    private final java.util.Set<String> quarantined = new java.util.HashSet<>();

    /** Run {@code r} at Throwable breadth; contain + report on failure. */
    private void guarded(String what, Runnable r) {
        Throwable t = Resilience.guard(r);
        if (t != null) contained(what, t);
    }

    /** Record + surface a contained failure (throttled per what). P24:
     *  a REPEATED failure carries its one-line identity (class: message
     *  · top app frame) inside the note — a single screenshot from the
     *  field is now a diagnosis, no Diagnostics trip needed. */
    private void contained(String what, Throwable t) {
        Trail.record(this, what, t);
        long now = System.currentTimeMillis();
        Long last = containedAt.get(what);
        Integer prev = containedCount.get(what);
        int n = (prev == null || last == null || now - last > 60_000) ? 1 : prev + 1;
        containedCount.put(what, n);
        if (last != null && now - last < 2500) return;   // no note spam
        containedAt.put(what, now);
        StringBuilder b = new StringBuilder();
        b.append("\u26a0 contained an internal error in ").append(what)
         .append(" — the chat survived; \u2318 \u2192 Logs & shell has the trace");
        if (n >= 2) b.append('\n').append(Resilience.traceLine(t));
        RunHub.sys(b.toString());    // P25: a model row — survives screen changes
    }

    // ------------------------------------------------- service event feed

    /** ServerService state changes → header subtitle. */
    @Override
    public void on(int newState, String detail) {
        ui.post(() -> guarded("server ui", this::refreshServerUi));
        if (newState == ServerService.ST_HEALTHY) {
            ui.post(() -> guarded("permissions", this::checkPermissionQueue));
            ui.post(() -> guarded("env welcome", this::showEnvWelcome));          // P15: one-shot env report
            // P18: an AUTO-recovery happened while this chat was open —
            // say so, and make clear the chat survived (sessions are on
            // disk). This replaces the old cold-boot ritual entirely.
            final String note = ServerService.consumeRecoveryNote();
            if (note != null) ui.post(() -> sys("♻ " + note));
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
            Throwable t = Resilience.guard(() -> {
                final String rep = Debian.envReport(this);
                ui.post(() -> sys(rep));
            });
            if (t != null) Trail.record(this, "env report", t);
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
        // P14: the pill lives in its own header slot so nothing is cut off.
        // P25: the Σ pill now reads as CONTEXT DEPTH — current context vs
        // the model's window ("48k / 200k · 24%") — computed from the last
        // turn's token count; the session $ cost stays exactly as verified.
        String spend = RunHub.ctxPillLine();
        if (tvSpend != null) {
            if (spend.isEmpty()) {
                tvSpend.setVisibility(View.GONE);
            } else {
                tvSpend.setVisibility(View.VISIBLE);
                tvSpend.setText("Σ " + spend);
            }
        }
        if (!AuthStore.hasAnyKey(this) && st == ServerService.ST_HEALTHY) {
            s += " · no API key yet — ⌘ → API keys";
        }
        tvSub.setText(s);
        syncVeil(st);
    }

    // ================================================= P25: live edit tree
    // The view half of the edit shower. STATE (feed, selection, peek
    // cache, watcher) lives in RunHub; this renders a COMPACT LIVE TREE:
    // touched files grouped under their (only-touched) directories, dirs
    // collapsed unless they carry the freshest activity (or the user
    // opened them), a hard height cap so huge directories scroll INSIDE
    // the card instead of flooding the chat.

    /** Max height of the tree box — beyond this it scrolls in place. */
    private static final int TREE_MAX_DP = 200;
    /** Estimated per-row height used to pre-size the scroll cap. */
    private static final int TREE_ROW_DP = 27;

    /** The "edit shower" card — slim while idle, a brief shower while hot.
     *  P19: restyled onto the thought-card surface (the ✦ thinking card's
     *  family — a sibling, not a chat bubble), and when a run ends with
     *  ZERO edits the row collapses to nothing (no empty records).
     *  P25: the shower is a COMPACT LIVE TREE — touched files grouped
     *  under their directories, height-capped, scrolling inside the card. */
    private View buildLiveView() {
        boolean settled = !RunHub.busy();
        boolean empty = RunHub.liveCount() == 0;
        if (settled && empty) {
            View ghost = new View(this);
            ghost.setVisibility(View.GONE);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0);
            glp.topMargin = 0;
            ghost.setLayoutParams(glp);
            return ghost;
        }
        boolean hot = RunHub.liveHot();
        boolean expanded = RunHub.liveOpen != null ? RunHub.liveOpen : (hot && !settled);

        if (livePulseAnim != null) { livePulseAnim.cancel(); livePulseAnim = null; }

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_thought_card);
        int cp = dp(11);
        c.setPadding(cp, dp(8), cp, dp(9));

        // ---- header: ● LIVE · summary ………………………………………… ▸
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = text(9, R.color.text_primary, false);
        dot.setText("●");
        dot.setTextColor(settled ? 0xFF6E6E6E : 0xFF9DB1FF);   // subtle blue live
        head.addView(dot);
        if (!settled && expanded && Theme.motionOn(this)) {
            livePulseAnim = Theme.pulse(dot);
        }

        TextView tag = text(10, settled ? R.color.text_secondary : 0xFFB9C4FF, true);
        tag.setText(settled ? "  EDITS" : "  LIVE");
        tag.setLetterSpacing(0.14f);
        head.addView(tag);

        TextView sum = text(11, R.color.text_secondary, false);
        sum.setPadding(dp(8), 0, 0, 0);
        sum.setSingleLine(true);
        sum.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        sum.setText(RunHub.liveSummaryLine(settled));
        head.addView(sum, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView chev = text(11, R.color.text_secondary, false);
        chev.setText(expanded ? "▾" : "▸");
        chev.setPadding(dp(6), 0, 0, 0);
        head.addView(chev);
        c.addView(head);

        // ---- the live tree: dirs collapsed, touched paths expanded,
        //      hard height cap — huge directories scroll INSIDE the card
        if (expanded) {
            List<EditPulse.TNode> tree = RunHub.liveTree();
            String newest = RunHub.liveNewest();
            java.util.Set<String> autoOpen = autoOpenDirs(tree, newest);

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(2), dp(4), 0, 0);
            int[] stagger = {0};
            boolean motion = Theme.motionOn(this);
            renderTreeNodes(box, tree, 0, newest, autoOpen, motion, stagger);

            ScrollView sc = new ScrollView(this);
            sc.setVerticalScrollBarEnabled(false);
            sc.addView(box);
            // cap: rows beyond ~TREE_MAX_DP scroll inside the card
            int rows = countNodes(tree, autoOpen);
            int capRows = Math.max(3, TREE_MAX_DP / TREE_ROW_DP);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    rows > capRows ? dp(TREE_MAX_DP)
                            : ViewGroup.LayoutParams.WRAP_CONTENT);
            sc.setLayoutParams(tlp);
            c.addView(sc);

            // ---- the peek: the exact edited region, never the full file
            String sel = RunHub.liveSel();
            if (sel != null) {
                TextView pv = mono(text(10, R.color.text_primary, false), 10);
                pv.setBackgroundResource(R.drawable.bg_code);
                int pp = dp(9);
                pv.setPadding(pp, pp, pp, pp);
                pv.setSingleLine(false);
                String cached = RunHub.peekFor(sel);
                if (cached != null) {
                    pv.setText(cached);
                } else {
                    pv.setText("…");
                    RunHub.ensurePeek(sel);
                }
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.topMargin = dp(8);
                pv.setLayoutParams(plp);
                c.addView(pv);
            }
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        Theme.press(c);
        head.setOnClickListener(v -> {
            RunHub.liveOpen = expanded ? Boolean.FALSE : Boolean.TRUE;
            lastToggled = LIVE_KEY;
            RunHub.Row r = rowByKey(LIVE_KEY);
            if (r != null) touchView(r);
        });
        return c;
    }

    /** Dirs the user has NOT explicitly toggled follow the freshest
     *  activity: the newest event's ancestor chain is expanded, sibling
     *  dirs stay collapsed ("directories collapsed, only touched paths
     *  expanded"). Pure given the tree. */
    private java.util.Set<String> autoOpenDirs(List<EditPulse.TNode> tree, String newest) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (newest == null) return out;
        collectAutoOpen(tree, newest, out);
        return out;
    }

    private void collectAutoOpen(List<EditPulse.TNode> nodes, String newest,
                                 java.util.Set<String> out) {
        for (EditPulse.TNode n : nodes) {
            if (n.ev != null && newest.equals(n.ev.abs)) {
                out.addAll(EditPulse.ancestors(n.ev.rel));
            } else if (n.ev == null && !n.kids.isEmpty()) {
                collectAutoOpen(n.kids, newest, out);
            }
        }
    }

    /** A dir is open when the user toggled it, else auto (newest branch). */
    private boolean dirOpen(EditPulse.TNode d, java.util.Set<String> autoOpen) {
        Boolean st = dirState.get(d.dir);
        return st != null ? st : autoOpen.contains(d.dir);
    }

    private void renderTreeNodes(LinearLayout into, List<EditPulse.TNode> nodes,
                                 int depth, String newest,
                                 java.util.Set<String> autoOpen,
                                 boolean motion, int[] stagger) {
        for (EditPulse.TNode n : nodes) {
            if (n.ev != null) {
                View row = liveFileRow(n.ev, depth, newest);
                into.addView(row);
                if (!n.ev.seen && motion) {          // only NEW events animate
                    row.setAlpha(0f);                // repaints never replay
                    row.setTranslationY(dp(8));
                    row.animate().alpha(1f).translationY(0f)
                            .setStartDelay(stagger[0]).setDuration(170)
                            .setInterpolator(Theme.DECEL).start();
                    stagger[0] = Math.min(stagger[0] + 45, 270);
                }
                n.ev.seen = true;
            } else {
                into.addView(dirRowView(n, depth, autoOpen));
                if (dirOpen(n, autoOpen)) {
                    renderTreeNodes(into, n.kids, depth + 1, newest,
                            autoOpen, motion, stagger);
                }
            }
        }
    }

    private int countNodes(List<EditPulse.TNode> nodes, java.util.Set<String> autoOpen) {
        int n = 0;
        for (EditPulse.TNode t : nodes) {
            n++;
            if (t.ev == null && dirOpen(t, autoOpen)) n += countNodes(t.kids, autoOpen);
        }
        return n;
    }

    /** One directory row inside the tree — tap toggles expand/collapse. */
    private View dirRowView(final EditPulse.TNode d, int depth,
                            final java.util.Set<String> autoOpen) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int rp = dp(6 + depth * 12);
        row.setPadding(rp, dp(3), rp, dp(3));

        TextView chev = text(10, R.color.text_secondary, false);
        chev.setText(dirOpen(d, autoOpen) ? "▾" : "▸");
        chev.setPadding(0, 0, dp(5), 0);
        row.addView(chev);

        TextView name = mono(text(11, R.color.text_secondary, false), 11);
        String seg = d.dir;
        int sl = seg.lastIndexOf('/');
        if (sl >= 0) seg = seg.substring(sl + 1);
        name.setText(seg + "/");
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView n = text(10, R.color.text_secondary, false);
        n.setPadding(dp(6), 0, 0, 0);
        n.setText("· " + d.hits + (d.hits == 1 ? " edit" : " edits"));
        row.addView(n);

        Theme.press(row);
        row.setOnClickListener(v -> {
            dirState.put(d.dir, !dirOpen(d, autoOpen));
            capDirState();
            RunHub.Row r = rowByKey(LIVE_KEY);
            if (r != null) touchView(r);
        });
        return row;
    }

    /** dirState is view-local preference memory — bounded like everything. */
    private void capDirState() {
        while (dirState.size() > 64) {
            String first = dirState.keySet().iterator().next();
            dirState.remove(first);
        }
    }

    /** One file row inside the tree — tap to peek around the edit.
     *  P25: the NEWEST event's row is auto-highlighted; depth indents. */
    private View liveFileRow(final EditPulse.Ev e, int depth, String newest) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int rp = dp(6 + depth * 12);
        row.setPadding(rp, dp(3), rp, dp(3));
        boolean sel = e.abs != null && e.abs.equals(RunHub.liveSel());
        boolean isNewest = e.abs != null && e.abs.equals(newest);
        if (sel) row.setBackgroundResource(R.drawable.bg_code);
        else row.setBackground(null);

        TextView g = text(11, "del".equals(e.action) ? R.color.err
                : R.color.text_secondary, false);
        g.setText(EditPulse.glyph(e.action));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.rightMargin = dp(7);
        g.setLayoutParams(glp);
        row.addView(g);

        TextView p = mono(text(11, R.color.text_primary, false), 11);
        p.setText(e.rel + (e.hits > 1 ? "  ×" + e.hits : ""));
        p.setSingleLine(true);
        p.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(p, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView age = text(10, R.color.text_secondary, false);
        age.setPadding(dp(6), 0, 0, 0);
        age.setText(relTime((System.currentTimeMillis() - e.ts) / 1000.0));
        row.addView(age);

        if (isNewest) {
            TextView fresh = text(9, 0xFF9DB1FF, true);
            fresh.setPadding(dp(5), 0, 0, 0);
            fresh.setText("●");
            row.addView(fresh);
        }

        Theme.press(row);
        row.setOnClickListener(v -> {
            String next = (RunHub.liveSel() != null && RunHub.liveSel().equals(e.abs))
                    ? null : e.abs;
            RunHub.setLiveSel(next);
        });
        return row;
    }

    // ================================================= P17: vision / images

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(
                    Intent.createChooser(i, "Share a screenshot with the agent"),
                    REQ_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "no image picker available", Toast.LENGTH_SHORT).show();
        }
    }

    /** Downscale off-thread, then a tiny confirm sheet with a caption. */
    private void confirmImage(final Uri uri) {
        ex.execute(() -> {
            try {
                final File jpg = Vision.downscale(this, uri);
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    LinearLayout box = new LinearLayout(this);
                    box.setOrientation(LinearLayout.VERTICAL);
                    int p = dp(18);
                    box.setPadding(p, p, p, 0);

                    ImageView prev = new ImageView(this);
                    Bitmap bm = Vision.decodeBounded(jpg.getAbsolutePath(), 360);
                    prev.setImageBitmap(bm);
                    prev.setAdjustViewBounds(true);
                    prev.setMaxHeight(dp(240));
                    prev.setBackgroundResource(R.drawable.bg_code);
                    box.addView(prev);

                    final EditText cap = new EditText(this);
                    cap.setHint("tell the agent what to look at (optional)");
                    cap.setTextSize(14);
                    cap.setTextColor(getColor(R.color.text_primary));
                    cap.setHintTextColor(getColor(R.color.text_secondary));
                    cap.setBackgroundResource(R.drawable.bg_input);
                    cap.setSingleLine(false);
                    cap.setMaxLines(3);
                    box.addView(cap, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    ((LinearLayout.LayoutParams) cap.getLayoutParams()).topMargin = dp(12);

                    new AlertDialog.Builder(this)
                            .setTitle("Show the agent")
                            .setView(box)
                            .setPositiveButton("Send", (d, w) -> {
                                String c2 = cap.getText().toString().trim();
                                attachImage(jpg, c2);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                ui.post(() -> sys("could not read that image: " + e.getMessage()));
            }
        });
    }

    /** The full vision send — P25: the network half lives in RunHub now,
     *  so an in-flight screenshot send also outlives this screen. */
    private void attachImage(final File jpg, final String caption) {
        RunHub.sendImage(jpg, caption);
    }
    /** The image bubble — rounded frame, caption, tap for the big view. */
    private View buildImageView(RunHub.Row r) {
        boolean user = "user".equals(r.tool);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.gravity = user ? Gravity.END : Gravity.START;
        wlp.topMargin = dp(8);
        wrap.setLayoutParams(wlp);

        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setMaxWidth(dp(240));
        iv.setBackgroundResource(R.drawable.bg_code);
        int ip = dp(4);
        iv.setPadding(ip, ip, ip, ip);
        Bitmap bm;
        synchronized (RunHub.imageCache) { bm = RunHub.imageCache.get(r.key); }
        if (bm != null && !bm.isRecycled()) {
            iv.setImageBitmap(bm);
        } else {
            iv.setMinimumHeight(dp(80));
            iv.setScaleType(ImageView.ScaleType.CENTER);
            iv.setImageResource(android.R.drawable.ic_menu_report_image);
            if (r.title != null) {
                RunHub.retryDecode(r);
            }
        }
        final RunHub.Row fr = r;
        iv.setOnClickListener(v -> showImage(fr));
        wrap.addView(iv);

        if (r.text.length() > 0) {
            TextView cap = text(11, R.color.text_secondary, false);
            cap.setText(r.text.toString());
            cap.setPadding(dp(2), dp(4), dp(2), 0);
            cap.setMaxWidth(dp(240));
            wrap.addView(cap);
        }
        return wrap;
    }

    /** Full-screen viewer for a chat image. */
    private void showImage(RunHub.Row r) {
        Bitmap bm;
        synchronized (RunHub.imageCache) { bm = RunHub.imageCache.get(r.key); }
        if (bm == null || bm.isRecycled()) return;
        ImageView big = new ImageView(this);
        big.setImageBitmap(bm);
        big.setAdjustViewBounds(true);
        big.setPadding(0, dp(12), 0, 0);
        new AlertDialog.Builder(this)
                .setView(big)
                .setPositiveButton("Close", null)
                .show();
    }

    /** P18/P25: tap the Σ pill → what this number actually is. The pill
     *  is CONTEXT DEPTH now — how full the model's window is — computed
     *  from the last turn's token count; "$" stays the session's total
     *  billed cost. When the window is heavy the one-button fix is here:
     *  a fresh chat resets per-turn cost without touching history. */
    private void spendPopover() {
        long sumTok = RunHub.sessionTok();
        double sumCost = RunHub.sessionCost();
        long lastTok;
        synchronized (lock) { lastTok = RunHub.tx().lastAssistantTok; }
        if (sumTok <= 0 && sumCost <= 0) return;
        StringBuilder m = new StringBuilder();
        m.append("The meter is CONTEXT DEPTH: how much of the model's ")
         .append("window this conversation already fills. Every new turn ")
         .append("re-reads the whole chat, so depth is what each NEW turn ")
         .append("costs before the model writes a single word. \"$\" is ")
         .append("what the provider billed for the whole session so far.\n\n");
        long limit = Models.contextLimitFor(Models.lastFetch(),
                RunHub.selProviderPub(), RunHub.selModelPub());
        if (lastTok > 0) {
            m.append("Now: ~").append(Resilience.fmtTok(lastTok));
            if (limit > 0) m.append(" of ").append(Resilience.fmtTok(limit));
            m.append(" tokens in the window.\n\n");
        }
        String verdict = Resilience.contextVerdict(lastTok);
        boolean heavy = limit > 0 ? lastTok * 100 / limit >= 50 : lastTok >= 50_000;
        if (!verdict.isEmpty()) m.append(verdict).append("\n");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Σ " + (Resilience.contextMeter(lastTok, limit).isEmpty()
                        ? Resilience.fmtCost(sumCost)
                        : Resilience.contextMeter(lastTok, limit)
                          + (sumCost > 0 ? " · " + Resilience.fmtCost(sumCost) : "")))
                .setMessage(m)
                .setPositiveButton("Got it", null);
        if (heavy) {
            b.setNegativeButton("＋ Fresh chat", (d, w) -> {
                RunHub.loadSession(null);
                sys("＋ fresh chat — the context (and per-turn cost) just reset; "
                        + "the old chat is still in Sessions");
            });
        }
        b.show();
    }

    private void sys(String s) {
        RunHub.sys(s);     // P25: a model row — survives screen changes
    }

    private void err(String title, String detail, String raw) {
        RunHub.err(title, detail, raw);
    }

    // ------------------------------------------------------------ views

    private boolean needFullRender;
    /** P12: key of the row whose open-state the user just toggled — the
     *  rebuild gets an "unfold" animation instead of a flat swap. */
    private String lastToggled;

    /** Rebuild the view for one row (or append it) — main thread only.
     *  P9: text rows update their cached TextView in place; only the
     *  streaming tail animates char-by-char via the smoother. */
    private void touchView(RunHub.Row r) {
        // P21 keyboard guard: a row swap must never detach the IME from
        // the chat box — if the input held focus before the mutation and
        // lost it during, take it straight back.
        boolean hadFocus = input != null && input.hasFocus();
        touchViewInner(r);
        if (hadFocus && input != null && !input.hasFocus()
                && !isFinishing() && !isDestroyed()) {
            input.requestFocus();
        }
    }

    private void touchViewInner(RunHub.Row r) {
        if (needFullRender) { renderAll(); return; }
        List<RunHub.Row> rs = RunHub.rows();
        Integer i = r.key == null ? null : RunHub.idx().get(r.key);
        if (i == null || i >= rs.size() || rs.get(i) != r) { renderAll(); return; }
        int idx = i;
        int len = r.text.length();
        if (r.shown > len) r.shown = len;

        if ((r.kind == K_ASSISTANT || r.kind == K_REASON)
                && r.shown < len && isStreamingTail(r)) {
            // P20: collapsed thinking card whose live ticker is already on
            // screen — skip the rebuild, the ticker paints the delta.
            if (r.kind == K_REASON && !r.open && r.livePreview
                    && bodyByKey.containsKey(r.key)
                    && viewByKey.containsKey(r.key)) {
                startSmoother();
                autoscroll();
                return;
            }
            // hand the row to the smoother: first paint shows the tail view
            View nv = buildRowView(r);
            if (idx < list.getChildCount()) {
                list.removeViewAt(idx);
                list.addView(nv, idx);
            } else if (idx == list.getChildCount()) {
                list.addView(nv);
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
            if (toggled && (r.kind == K_TOOL || r.kind == K_REASON || r.kind == K_LIVE)) {
                lastToggled = null;
                Theme.unfold(nv);    // P12: expand/collapse feels physical
            }
        } else if (idx == list.getChildCount()) {
            list.addView(nv);
            if (Theme.motionOn(this)) {
                if (r.kind == K_USER) Theme.springIn(nv);  // P12: bubbly pop
                else Theme.appear(nv);
            }
        } else {
            renderAll();
        }
        syncEmpty();
    }

    /** P25: model trim moved to RunHub (rows stay capped even with no
     *  view bound). The view learns about trims through hubReset(). */

    private void renderAll() {
        boolean hadFocus = input != null && input.hasFocus();
        renderAllInner();
        if (hadFocus && input != null && !input.hasFocus()
                && !isFinishing() && !isDestroyed()) {
            input.requestFocus();
        }
    }

    private void renderAllInner() {
        List<RunHub.Row> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(rows);
            needFullRender = false;
            viewByKey.clear(); bodyByKey.clear(); metaByKey.clear();
            for (RunHub.Row r : snapshot) r.shown = r.text.length(); // history: no caret
        }
        list.removeAllViews();
        for (RunHub.Row r : snapshot) {
            // P24: Throwable breadth (was catch(Exception)) — an Error row
            // becomes the fallback line instead of leaving the list
            // HALF-BUILT (removeAllViews already ran): the exact "chat
            // survived but won't work" field state.
            final RunHub.Row fr = r;
            Throwable rt = Resilience.guard(() -> list.addView(buildRowView(fr)));
            if (rt == null) continue;
            Trail.record(this, "row render", rt);
            TextView tv = mono(new TextView(this), 12);
            tv.setText(Resilience.quarantineLine());
            list.addView(tv);
        }
        syncEmpty();
        autoscroll();
    }

    private void setAllOpen(boolean open) {
        synchronized (lock) {
            for (RunHub.Row r : rows) {
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

    private View buildRowView(RunHub.Row r) {
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
                // P20: while this row is the streaming tail the card is
                // ALIVE — collapsed it grows a one-line ticker of the
                // freshest thinking (token-by-token via the smoother);
                // open, the body itself streams with the caret.
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
                String key = r.key == null ? "" : r.key;
                boolean streaming = r.shown < r.text.length();
                r.livePreview = false;
                if (r.open) {
                    TextView body = text(13, R.color.text_secondary, false);
                    body.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                    body.setTextIsSelectable(true);
                    body.setLineSpacing(dp(2), 1f);
                    String t;
                    if (streaming) {
                        int upto = Math.min(r.shown, r.text.length());
                        t = r.text.substring(0, upto);
                    } else {
                        t = r.text.toString();
                        if (t.length() > 20000) t = t.substring(0, 20000) + "…";
                    }
                    body.setText(t.length() == 0 ? "…"
                            : streaming ? t + "▍" : t);
                    body.setPadding(dp(2), dp(8), 0, 0);
                    c.addView(body);
                    viewByKey.put(key, c);
                    bodyByKey.put(key, body);
                } else if (streaming) {
                    TextView live = text(12, R.color.text_secondary, false);
                    live.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                    live.setSingleLine(true);
                    live.setEllipsize(android.text.TextUtils.TruncateAt.START);
                    String win = Resilience.thinkWindow(r.text.toString(),
                            Math.min(r.shown, r.text.length()), 110);
                    live.setText(win.length() == 0 ? "…" : win + "▍");
                    live.setPadding(dp(2), dp(6), 0, 0);
                    live.setAlpha(0.85f);
                    c.addView(live);
                    viewByKey.put(key, c);
                    bodyByKey.put(key, live);
                    r.livePreview = true;
                } else {
                    viewByKey.remove(key);          // P20: never leave a ghost
                    bodyByKey.remove(key);          // registration behind
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
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
                lp.topMargin = dp(8);
                c.setLayoutParams(lp);
                Theme.press(c);
                head.setOnClickListener(v -> { r.open = !r.open; lastToggled = r.key; touchView(r); });
                c.setOnClickListener(v -> { r.open = !r.open; lastToggled = r.key; touchView(r); });
                return c;
            }
            case K_LIVE:
                return buildLiveView();
            case K_IMAGE:
                return buildImageView(r);
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
                lp.topMargin = dp(8);
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
        // P14/P25: AUTO-ALLOW (unattended mode) — answering moved to
        // RunHub, which reacts to permission EVENTS with no screen bound
        // (the agent can never stall while the user is elsewhere). This
        // path covers a permission already queued when the toggle flipped:
        // the hub dedupes via its own autoReplied + tombstones.
        if (autoAllowOn()) {
            final String id = Json.str(perm, "id");
            String action = nz(Json.str(perm, "permission"),
                    nz(Json.str(perm, "type"), "tool"));
            if (id != null && !autoReplied.contains(id)
                    && !RunHub.autoAlready(id)) {
                if (autoReplied.size() > 400) autoReplied.clear();
                autoReplied.add(id);
                RunHub.answerPermission(id, "always", null);
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
        // P25: one reply ladder, in RunHub. The view adds the human bits:
        // the toast and the immediate card refresh.
        RunHub.answerPermission(id, response, (ok, errS) -> {
            if (ok) {
                Toast.makeText(this,
                        "always".equals(response) ? "always allowed"
                                : "reject".equals(response) ? "denied" : "allowed",
                        Toast.LENGTH_SHORT).show();
            } else {
                sys("permission reply failed · " + errS);
                Toast.makeText(this, "reply failed — try again", Toast.LENGTH_SHORT).show();
            }
            checkPermissionQueue();
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
            case "New chat": RunHub.loadSession(null); break;
            case "Sessions…": sessionsSheet(); break;
            case "Model…": modelSheet(); break;
            case "Project files →":
                startActivity(new Intent(this, FilesActivity.class)); break;
            case "Sandbox environment":
                ex.execute(() -> {
                    Throwable t = Resilience.guard(() -> {
                        final String rep = Debian.envReport(this);
                        ui.post(() -> sys(rep));
                    });
                    if (t != null) Trail.record(this, "env report", t);
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
            ui.post(() -> guarded("sessions sheet", () -> showSessions(listS)));
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
            if (it[2] == null) RunHub.loadSession(null);
            else RunHub.loadSession(((SessRow) it[2]).id);
        });
        lv.setOnItemLongClickListener((parent, v, pos, id4) -> {
            Object[] it = items.get(pos);
            if (!(it[2] instanceof SessRow)) return true;
            SessRow s = (SessRow) it[2];
            new AlertDialog.Builder(this)
                    .setTitle(s.title)
                    .setItems(new String[]{"Open", "Delete"}, (d, w) -> {
                        if (w == 0) { dlg.dismiss(); RunHub.loadSession(s.id); }
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
                        else if (s.id.equals(RunHub.sessionId())) RunHub.loadSession(null);
                    } catch (Exception e) {
                        sys("delete failed: " + e);
                    }
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ models

    // P16: the sheet keeps LIVE references so a key added from inside it
    // (＋ key chip → KeysActivity → back) refreshes the rows in place —
    // no more close/reopen to see "OpenCode Go · ready".
    private android.app.AlertDialog modelDlg;
    private List<Models.Prov> sheetProvs;
    private Runnable sheetRefill;
    private boolean keysFromSheet;

    private void modelSheet() {
        // P13: NO server gate anymore — the sheet opens regardless and shows
        // what it sees ("server offline · bundled catalog") instead of
        // dead-ending on a toast. The P11-era gate made the picker look
        // broken whenever the server was still booting.
        ex.execute(() -> {
            Throwable t = Resilience.guard(() -> {
                List<Models.Prov> provs = Models.fetch(this);
                ui.post(() -> showModels(provs));
            });
            if (t != null) Trail.record(this, "model sheet", t);
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
        LinearLayout srcRow = new LinearLayout(this);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);
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
        srcRow.addView(srcLine, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // P16: manual re-fetch — after saving a key elsewhere (or the server
        // restart settling) this pulls fresh /config/providers into the OPEN
        // sheet instead of forcing a close/reopen cycle.
        TextView ref = text(13, R.color.accent_light, true);
        ref.setText("↻");
        int rp = dp(10);
        ref.setPadding(rp, dp(2), rp, dp(5));
        ref.setBackgroundResource(R.drawable.bg_chip);
        Theme.press(ref);
        ref.setOnClickListener(vv -> {
            Toast.makeText(this, "refreshing providers…", Toast.LENGTH_SHORT).show();
            ex.execute(() -> {
                List<Models.Prov> fresh = Models.fetch(ChatActivity.this);
                ui.post(() -> {
                    sheetProvs = fresh;
                    if (sheetRefill != null) sheetRefill.run();
                });
            });
        });
        srcRow.addView(ref, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(srcRow);

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
        if (w != null) {
            // P16 DeX: a full-bleed 88% sheet looks wrong on a desktop-sized
            // window — cap the width like the desktop opencode column.
            int wm = ViewGroup.LayoutParams.MATCH_PARENT;
            if (getResources().getDisplayMetrics().widthPixels >= Theme.dp(this, 720))
                wm = Theme.dp(this, 760);
            w.setLayout(wm,
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.88));
        }
        modelDlg = dlg;
        sheetProvs = provs;
        dlg.setOnDismissListener(d -> {
            if (modelDlg == dlg) {
                modelDlg = null;
                sheetRefill = null;
                sheetProvs = null;
            }
        });

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
            List<Models.Prov> ps = sheetProvs != null ? sheetProvs : provs;
            for (Models.Prov pr : ps) {
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
                        // P16 provider header: state mark + a direct "＋ key"
                        // action for providers missing theirs. The header tap
                        // itself STILL never steals the sheet (P15 rule) — the
                        // chip is the deliberate action. After Keys closes, the
                        // rows refresh in place (see onResume).
                        Models.Prov pr = (Models.Prov) it[1];
                        LinearLayout hrow = new LinearLayout(ChatActivity.this);
                        hrow.setOrientation(LinearLayout.HORIZONTAL);
                        hrow.setGravity(Gravity.CENTER_VERTICAL);
                        TextView t = text(13, pr.usable ? R.color.ok
                                : pr.configured ? R.color.accent_light
                                : R.color.text_secondary, true);
                        String mark;
                        if (pr.usable && "opencode".equals(pr.id))
                            mark = "  ·  free · no key needed";   // P11 verified
                        else if (pr.usable) mark = "  ·  ready";
                        else if (pr.configured) mark = "  ·  key saved · restarting picks it up";
                        else mark = "  ·  needs its own key";
                        t.setText(pr.name + mark);
                        t.setSingleLine(true);
                        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        hrow.addView(t, new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        if (!pr.usable && !pr.configured) {
                            TextView add = text(11, R.color.accent_light, true);
                            add.setTypeface(Typeface.MONOSPACE);
                            add.setText("＋ key");
                            int ap = dp(9);
                            add.setPadding(ap, dp(4), ap, dp(4));
                            add.setBackgroundResource(R.drawable.bg_chip);
                            Theme.press(add);
                            add.setOnClickListener(vv -> {
                                keysFromSheet = true;
                                startActivity(new Intent(ChatActivity.this,
                                        KeysActivity.class));
                            });
                            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
                            alp.leftMargin = dp(8);
                            hrow.addView(add, alp);
                        }
                        box.addView(hrow);
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
                    // P16: rows unfold once, on first bind — recycled rows stay
                    // still (no replay jitter while scrolling).
                    if (cv == null) Theme.enter(box, Math.min(i, 10) * 18L);
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
        sheetRefill = refill;
        lv.setOnItemClickListener((parent, v, pos, id4) -> {
            Object[] it = items.get(pos);
            if ("h".equals(it[0])) {
                // P15: headers never steal the sheet. Unconfigured provider →
                // point at the keys screen in place (P14 closed the whole
                // picker here, which read as "tapping the picker breaks it").
                Models.Prov pr = (Models.Prov) it[1];
                if (!pr.usable && !pr.configured)
                    Toast.makeText(this, pr.name + " needs its own key — tap "
                            + "＋ key beside its name (⌘ → API keys), paste, "
                            + "then tap ↻ up top",
                            Toast.LENGTH_SHORT).show();
                return;
            }
            if (!"m".equals(it[0])) return;
            Models.Prov pr = (Models.Prov) it[1];
            Models.Mdl m = (Models.Mdl) it[2];
            // P12a restored: discovery-catalog entries are NOT selectable —
            // the server would answer "Model not found". Say why instead.
            if (!m.live) {
                if (pr.usable) {
                    Toast.makeText(this, m.name + " is in the discovery catalog "
                            + "but not offered by your server right now (the free "
                            + "list rotates) — pick a non-tagged model",
                            Toast.LENGTH_LONG).show();
                } else {
                    // P16: say WHICH key — Zen and Go are separate, and that
                    // distinction is the whole P16 key fix.
                    Toast.makeText(this, "no key for " + pr.name + " yet — "
                            + (pr.id.startsWith("opencode")
                                ? "add the " + pr.name + " key in ⌘ → API keys "
                                  + "(separate from your other opencode key)"
                                : "⌘ → API keys first")
                            + ", then tap ↻ up top",
                            Toast.LENGTH_LONG).show();
                }
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
                RunHub.Row r = rows.get(i);
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
                for (RunHub.Row r : rows) {
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
