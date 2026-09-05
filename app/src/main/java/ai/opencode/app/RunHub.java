package ai.opencode.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P25 — the run engine. Everything that makes an agent turn REAL now
 * lives here, for the whole process lifetime, instead of inside the chat
 * screen:
 *
 *   • the transcript model (rows, per-session, upserted from SSE parts)
 *   • busy state + the quiet-end watchdog + the P19 self-heal re-arm
 *   • send orchestration (variant ladder, model validation, soft timeouts)
 *   • the P11/P16 self-heals (stale model, stream flake, key hints)
 *   • the live-edit shower state (DirWatcher + feed + peek) — event-driven
 *   • unattended auto-allow answering (permissions never stall the run)
 *   • run-state persistence → "run was interrupted" recovery after a
 *     process kill, with the session restored intact
 *
 * WHY: ServerService already owned the SSE transport, but it DROPPED
 * every parsed frame when no screen listened — so leaving the chat
 * orphaned the run's rendering state, and coming back needed a replay
 * to look right. The field request is blunt: "a running agent turn must
 * NEVER depend on the chat screen existing. Leaving to the deck keeps
 * the run streaming; coming back shows it exactly where it is."
 *
 * ChatActivity becomes a pure view: it binds a RunHub.Ui on resume,
 * unbinds on pause, and paints what the hub already knows. Re-entering
 * mid-run re-PULLS the session from the server API (never re-POSTs,
 * never restarts a healthy stream). The ONLY code that aborts a run is
 * the user's explicit stop button (RunHub.abort).
 *
 * Threading: model mutations happen on the MAIN looper (same discipline
 * the ChatActivity code always used — view-safe by construction, and the
 * paint pipeline reads the same objects). Network/file work runs on the
 * IO pool and hops to main to mutate. LOCK guards cross-thread reads
 * (spend sums, export). Event delivery from ServerService already lands
 * on main.
 */
public final class RunHub implements ServerService.EventListener {

    private RunHub() {}

    // ------------------------------------------------------------ row model

    // row kinds (ChatActivity re-exports these for its render switch)
    public static final int K_USER = 0, K_ASSISTANT = 1, K_REASON = 2,
            K_TOOL = 3, K_SYS = 4, K_ERR = 5, K_LIVE = 6, K_IMAGE = 7;

    /** The one live-edit card's stable row key. */
    public static final String LIVE_KEY = "live:edits";

    public static final class Row {
        public int kind;
        public String key;                 // stable identity for SSE upserts
        public final StringBuffer text = new StringBuffer();
        public int shown;                  // chars painted so far (view-side caret)
        public boolean livePreview;        // collapsed thinking card w/ live ticker
        public String tool, status, title, meta;
        public final StringBuffer input = new StringBuffer();
        public final StringBuffer output = new StringBuffer();
        public boolean open;               // collapsed by default for tool/reason
        public long ts;
    }

    public static final class MsgInfo {
        public String role;
        public String meta;                // "⇅ 12.3k tok · $0.0041"
        public double cost;                // session-total accumulation
        public long tok;
        public boolean errorShown;
    }

    /** One session's transcript. The hub keeps the DISPLAYED session plus
     *  a small LRU of others — so a run streaming into a background
     *  session keeps its rows and nothing leaks into the wrong chat. */
    public static final class Tx {
        public final List<Row> rows = new ArrayList<>();
        public final Map<String, Integer> idxByKey = new HashMap<>();
        /** Insertion-ordered so the long-run eviction (P26) can drop the
         *  OLDEST message bookkeeping first. */
        public final Map<String, MsgInfo> msgs = new LinkedHashMap<>();
        public final Map<String, Integer> typeCount = new HashMap<>();
        /** Keys of rows dropped by the 450-row trim (and purged empty
         *  thoughts) — the replay must never re-append ancient parts. */
        public final java.util.ArrayDeque<String> trimmedKeys = new java.util.ArrayDeque<>();
        public long lastAssistantTok;      // P25: context-depth meter input
        public int rowsAdded;              // monotonically bumps on add (view cue)
        // P26: RUNNING session sums. A year-long run must not re-iterate
        // an ever-growing msgs map on every pill paint — totals move by
        // delta on each message update and survive the eviction of old
        // MsgInfo entries.
        public double costSum;
        public long tokSum;
    }

    private static final int TRIMMED_KEY_CAP = 4096;
    // package-private: the JVM suite pins these walls
    static final int TRIM_OVER = 450, TRIM_KEEP = 350;
    static final int ARCHIVE_CAP = 4;
    /** P26 long-run caps: message bookkeeping per session, pid-less part
     *  counters, edit-focus snippets. A month/year run grows INTO these
     *  walls and stays there — bounded memory, evergreen behavior. */
    static final int MSG_CAP = 400;
    static final int TYPE_COUNT_CAP = 1024;
    static final int EDIT_FOCUS_CAP = 200;

    // ------------------------------------------------------------ state

    private static final Object LOCK = new Object();
    /** The displayed (or last-displayed) session's transcript. */
    private static Tx cur = new Tx();
    /** Other sessions' live transcripts (a run may stream into one). */
    private static final LinkedHashMap<String, Tx> archive = new LinkedHashMap<>();

    private static volatile String sessionId;
    private static volatile String sessionTitle;
    private static volatile boolean busy;
    /** The session whose run is streaming (send target). */
    private static volatile String runSessionId;
    private static volatile String agent = "build";   // Tab parity: build <-> plan
    private static volatile long lastPartTs;
    private static volatile String lastUserText;
    private static volatile boolean runHadOutput;
    private static volatile boolean modelFixRetried;
    private static volatile boolean flakeRetried;
    private static volatile boolean interruptedNotePending;
    /** P26: set when a bind-time session re-pull could not reach the
     *  server (boot race: the chat re-opened before the sandbox finished
     *  starting). The next ST_HEALTHY flip re-runs the pull — the screen
     *  that used to sit "loading" on an empty transcript now fills
     *  itself the moment the server answers. Event-driven, zero polling. */
    private static volatile boolean replayNeeded;
    /** P22 latch: ensureSession + model validation do network I/O before
     *  busy flips — a double-tap must not queue two identical runs. */
    private static final AtomicBoolean sending = new AtomicBoolean(false);

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    /** Permission replies — must NEVER wait on anything. */
    private static final ExecutorService PERM = Executors.newCachedThreadPool();
    private static volatile Context appCtx;
    private static boolean inited;

    // ---------------------------------------------------------- UI sink

    /** The view contract. ChatActivity binds one on resume, unbinds on
     *  pause. Every method is invoked on the MAIN thread. */
    public interface Ui {
        void hubRow(String key);       // a row was added or changed
        void hubBusy(boolean b);       // run state flipped
        void hubSpend();               // token/cost pill changed
        void hubTitle();               // session title changed
        void hubPerms();               // permission queue changed
        void hubLive();                // edit feed / peek changed
        void hubReset();               // transcript replaced → full re-render
    }

    private static final List<Ui> uis = new ArrayList<>();

    public static void bindUi(Ui u) {
        synchronized (uis) { if (!uis.contains(u)) uis.add(u); }
    }

    public static void unbindUi(Ui u) {
        synchronized (uis) { uis.remove(u); }
    }

    private static void fire(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else H.post(r);
    }

    private static void notifyRow(final String key) {
        fire(() -> {
            synchronized (uis) { for (Ui u : uis) u.hubRow(key); }
        });
    }

    private static void notifyReset() {
        fire(() -> {
            synchronized (uis) { for (Ui u : uis) u.hubReset(); }
        });
    }

    private static void notifyBusy() {
        final boolean b = busy;
        fire(() -> {
            synchronized (uis) { for (Ui u : uis) u.hubBusy(b); }
        });
    }

    private static void notifySpend() {
        fire(() -> {
            synchronized (uis) { for (Ui u : uis) u.hubSpend(); }
        });
    }

    private static void notifyTitle() {
        fire(() -> {
            synchronized (uis) { for (Ui u : uis) u.hubTitle(); }
        });
    }

    private static void notifyPerms() {
        fire(() -> {
            synchronized (uis) { for (Ui u : uis) u.hubPerms(); }
        });
    }

    private static void notifyLive() {
        fire(() -> {
            synchronized (uis) { for (Ui u : uis) u.hubLive(); }
        });
    }

    // ------------------------------------------------------------ init

    /** Idempotent. Called from App.onCreate (and defensively from
     *  ServerService.onCreate). Subscribes the hub to the SSE feed FOR
     *  THE PROCESS LIFETIME — the transport never drops frames again. */
    public static synchronized void init(Context c) {
        if (inited) return;
        inited = true;
        appCtx = c.getApplicationContext();
        ServerService.subscribeEvents(HUB);
        restoreRunState();
    }

    private static final RunHub HUB = new RunHub();

    // --------------------------------------------------- run-state file

    /** Persisted run state — the process-kill recovery anchor. Pure JSON
     *  via Json.quote; written atomically on every state transition. */
    public static final class RunState {
        public String sid, title, user;
        public boolean busy;
        public long ts;
    }

    static String rsToJson(RunState s) {
        if (s == null) return "{}";
        StringBuilder b = new StringBuilder("{");
        b.append("\"sid\":").append(s.sid == null ? "null" : Json.quote(s.sid));
        b.append(",\"title\":").append(s.title == null ? "null" : Json.quote(s.title));
        b.append(",\"user\":").append(s.user == null ? "null" : Json.quote(s.user));
        b.append(",\"busy\":").append(s.busy);
        b.append(",\"ts\":").append(s.ts);
        return b.append('}').toString();
    }

    static RunState rsFromJson(String j) {
        try {
            Map<String, Object> o = Json.obj(Json.parse(j));
            if (o == null) return null;
            RunState s = new RunState();
            s.sid = Json.str(o, "sid");
            s.title = Json.str(o, "title");
            s.user = Json.str(o, "user");
            Object b = o.get("busy");
            s.busy = Boolean.TRUE.equals(b) || "true".equals(String.valueOf(b));
            Object t = o.get("ts");
            s.ts = (t instanceof Number) ? ((Number) t).longValue() : 0;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static File runStateFile() {
        Context c = appCtx;
        return c == null ? null : new File(c.getFilesDir(), "run-state.json");
    }

    private static void saveRunState() {
        final RunState s = new RunState();
        s.sid = sessionId;
        s.title = sessionTitle;
        s.user = lastUserText;
        s.busy = busy;
        s.ts = System.currentTimeMillis();
        IO.execute(() -> {
            File f = runStateFile();
            if (f == null) return;
            try {
                File tmp = new File(f.getParentFile(), f.getName() + ".part");
                try (OutputStream o = new FileOutputStream(tmp)) {
                    o.write(rsToJson(s).getBytes("UTF-8"));
                }
                if (f.exists()) f.delete();
                tmp.renameTo(f);
            } catch (Exception ignored) {}
        });
    }

    /** Boot path: read the run-state file. A busy flag here means the
     *  PROCESS DIED MID-RUN (the file is cleared on every settle) — the
     *  run is gone (the server child died with us), but the session is
     *  on disk. Restore ids, NEVER restore busy (no wedged state), and
     *  leave an honest note for the next chat open. */
    private static void restoreRunState() {
        File f = runStateFile();
        if (f == null || !f.isFile()) return;
        RunState s;
        try (FileInputStream fin = new FileInputStream(f)) {
            s = rsFromJson(Api.readAll(fin));
        } catch (Exception e) {
            return;
        }
        if (s == null) return;
        if (s.sid != null && !s.sid.isEmpty()) {
            sessionId = s.sid;
            sessionTitle = (s.title == null || s.title.isEmpty())
                    ? "Recovered chat" : s.title;
        }
        if (s.busy) {
            interruptedNotePending = true;
            busy = false;                       // never boot into a wedged run
            runSessionId = null;
            lastUserText = null;
            saveRunState();                     // clear the busy flag NOW
        }
    }

    /** The one-shot interrupted-run note (null when none). The chat shows
     *  it on first bind after a process kill, then it is gone. */
    public static String consumeInterruptedNote() {
        if (!interruptedNotePending) return null;
        interruptedNotePending = false;
        return "⚠ the app was closed while the agent was working — that run "
                + "was interrupted. Your history is intact; resend your "
                + "message to continue.";
    }

    // ------------------------------------------------------- accessors

    public static final Object lock() { return LOCK; }
    public static List<Row> rows() { return cur.rows; }
    public static Map<String, Integer> idx() { return cur.idxByKey; }
    public static Tx tx() { return cur; }
    public static String sessionId() { return sessionId; }
    public static String sessionTitle() { return sessionTitle; }
    public static boolean busy() { return busy; }
    public static String agent() { return agent; }
    public static void setAgent(String a) {
        agent = "plan".equals(a) ? "plan" : "build";
    }

    /** Session-cumulative cost — the money meter (P14 semantics kept
     *  exactly; the user verified it and it must not drift). P26: O(1) —
     *  the total moves by delta on every message update instead of
     *  re-iterating an ever-growing map. */
    public static double sessionCost() {
        synchronized (LOCK) { return cur.costSum; }
    }

    /** Session-cumulative tokens (for the Σ popover's explanation). */
    public static long sessionTok() {
        synchronized (LOCK) { return cur.tokSum; }
    }

    /** P25: the pill line — CURRENT context depth vs the model's window,
     *  plus the (unchanged) session cost. "48k / 200k · 24% · $0.0041". */
    public static String ctxPillLine() {
        long last;
        synchronized (LOCK) { last = cur.lastAssistantTok; }
        String meter = Resilience.contextMeter(last, Models.contextLimitFor(
                Models.lastFetch(), selProvider(), selModel()));
        double cost = sessionCost();
        if (meter.isEmpty() && cost <= 0) return "";
        StringBuilder b = new StringBuilder(meter);
        if (cost > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(Resilience.fmtCost(cost));
        }
        return b.toString();
    }

    private static String selProvider() {
        String[] sel = Models.selected(appCtx);
        return sel == null ? null : sel[0];
    }

    private static String selModel() {
        String[] sel = Models.selected(appCtx);
        return sel == null ? null : sel[1];
    }

    /** View-facing selection halves (the spend popover reads the limit). */
    public static String selProviderPub() { return selProvider(); }
    public static String selModelPub() { return selModel(); }

    // ------------------------------------------------------------ notes

    /** A transcript note (the old sys()) — lives in the model, so it
     *  survives the chat closing mid-run. */
    public static void sys(String s) {
        final Row r = new Row();
        r.kind = K_SYS;
        r.key = "sys-" + System.nanoTime();
        r.ts = System.currentTimeMillis();
        r.text.append(s);
        onMainAdd(r);
    }

    /** A transcript error card (the old err()) — model-owned. */
    public static void err(String title, String detail, String raw) {
        final Row r = new Row();
        r.kind = K_ERR;
        r.key = "err-" + System.nanoTime();
        r.ts = System.currentTimeMillis();
        r.text.append(title);
        if (detail != null && !detail.isEmpty()) r.output.append(detail);
        if (raw != null && !raw.isEmpty()) {
            if (r.output.length() > 0) r.output.append("\n----\n");
            String rawT = raw.length() > 3000 ? raw.substring(0, 3000) + "…" : raw;
            r.output.append(rawT);
        }
        r.open = true;
        onMainAdd(r);
    }

    private static void onMainAdd(final Row r) {
        main(() -> {
            boolean trimmed;
            synchronized (LOCK) {
                cur.rows.add(r);
                cur.idxByKey.put(r.key, cur.rows.size() - 1);
                cur.rowsAdded++;
                trimmed = purgeTrimLocked(cur);
            }
            if (trimmed) notifyReset();
            else notifyRow(r.key);
        });
    }

    /** Run on the main looper (inline when already there). All model
     *  mutations go through this — the view reads the same thread. */
    private static void main(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else H.post(r);
    }

    /** Model-side trim (rows are capped even when NO view is bound —
     *  a long unattended run must not grow the heap forever). Returns
     *  true when the caller must trigger a full re-render. */
    private static boolean purgeTrimLocked(Tx t) {
        if (t.rows.size() <= TRIM_OVER) return false;
        int cut = t.rows.size() - TRIM_KEEP;
        t.idxByKey.clear();
        for (int i = 0; i < cut; i++) forgetKey(t, t.rows.get(i).key);
        t.rows.subList(0, cut).clear();
        for (int i = 0; i < t.rows.size(); i++) {
            Row r = t.rows.get(i);
            if (r.key != null) t.idxByKey.put(r.key, i);
        }
        return true;
    }

    private static void forgetKey(Tx t, String k) {
        if (k == null) return;
        t.trimmedKeys.addLast(k);
        while (t.trimmedKeys.size() > TRIMMED_KEY_CAP) t.trimmedKeys.removeFirst();
    }

    /** True when a part's row once existed but was trimmed away — its
     *  content is ancient history, NOT something a replay may resurrect. */
    private static boolean partWasTrimmed(Tx t, String key) {
        synchronized (LOCK) {
            return key != null && !t.idxByKey.containsKey(key)
                    && t.trimmedKeys.contains(key);
        }
    }

    // ------------------------------------------------------- event feed

    /** ServerService rebroadcasts every parsed /event frame here — now
     *  for the whole process lifetime, screen or no screen. */
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
                        runHadOutput = true;
                    applyPart(part, null);
                    // P19 self-heal: parts for the RUNNING session mean the
                    // run is alive even if we think otherwise. Re-arm busy.
                    String psid = Json.str(part, "sessionID");
                    if (!busy && runSessionId != null && runSessionId.equals(psid)) {
                        setBusy(true);
                        H.removeCallbacks(watchdog);
                        H.postDelayed(watchdog, 2000);
                        if (sessionId != null && !sessionId.equals(psid))
                            sys("a run is still streaming in another session — "
                                    + "■ stops it");
                    }
                }
            } else if ("message.updated".equals(type)) {
                Map<String, Object> minfo = Json.map(props, "info");
                if (minfo != null) {
                    applyMessageInfo(txnFor(Json.str(minfo, "sessionID")), minfo);
                }
            } else if ("session.updated".equals(type)) {
                Map<String, Object> info = Json.map(props, "info");
                if (info != null && sessionId != null
                        && sessionId.equals(Json.str(info, "id"))) {
                    String t = Json.str(info, "title");
                    if (t != null && !t.isEmpty()) {
                        sessionTitle = t;
                        notifyTitle();
                    }
                }
            } else if ("session.idle".equals(type)) {
                String sid = Json.str(props, "sessionID");
                // P25: only the RUNNING session's idle settles busy — a
                // displayed-but-idle session must never park another
                // session's live run.
                if (sid == null || sid.equals(runSessionId)) {
                    setBusy(false);
                    runSessionId = null;      // the run truly ended
                    saveRunState();
                }
            } else if ("session.error".equals(type)) {
                Map<String, Object> e = Json.map(props, "error");
                String m = e != null ? Json.str(e, "message") : null;
                if (m == null) m = Json.findErrorText(props, 0);
                String raw = String.valueOf(props);
                if (isModelNotFound(raw + " " + m)) {
                    // P11 self-heal: run-time Model not found (the POST itself
                    // returned 200) — drop the stale pick so the NEXT send
                    // uses the server default instead of failing forever.
                    Models.clear(appCtx);
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
                    final String sid = runSessionId;
                    if (sid != null) sendText(sid, lastUserText);
                    else setBusy(false);
                } else {
                    err("session error", m, raw);
                    // P16: 401/402/api-key failures get the one line the
                    // user actually needs — WHICH key, and that Zen ≠ Go.
                    if (isKeyError(raw + " " + m)) {
                        String[] sel = Models.selected(appCtx);
                        boolean go = sel != null && "opencode-go".equals(sel[0]);
                        sys(go
                                ? "⚠ key problem — this model needs the OpenCode GO "
                                  + "key (SEPARATE from the Zen key). ⌘ → API keys → OpenCode Go"
                                : "⚠ key problem — this provider needs its API key "
                                  + "(⌘ → API keys). OpenCode Zen and Go keys are separate");
                    }
                    setBusy(false);
                }
            } else if ("permission.asked".equals(type)
                    || "permission.updated".equals(type)
                    || "permission.v2.asked".equals(type)
                    || "permission.v2.updated".equals(type)) {
                // Unattended mode answers HERE — no screen required, the
                // agent can never stall on an approval while the user is
                // elsewhere (P14's flow only worked with the chat open).
                Map<String, Object> perm = ServerService.peekPermission();
                if (perm != null && autoAllowOn()) {
                    final String id = Json.str(perm, "id");
                    String action = nz(Json.str(perm, "permission"),
                            nz(Json.str(perm, "type"), "tool"));
                    if (id != null && !autoReplied.contains(id)) {
                        if (autoReplied.size() > 400) autoReplied.clear();
                        autoReplied.add(id);
                        sys("⏵ auto-allowed " + action);
                        answerPermission(id, "always", null);
                    }
                }
                notifyPerms();
            }
        } catch (Exception e) {
            // never let a malformed frame kill the hub
        } catch (Throwable e) {
            Trail.record(appCtx, "hub event", e);
        }
    }

    private static final java.util.Set<String> autoReplied = new java.util.HashSet<>();

    /** True when the hub already auto-answered this request id (dedupe
     *  between the hub's event path and the view's queue check). */
    public static boolean autoAlready(String id) {
        return id != null && autoReplied.contains(id);
    }

    private static boolean autoAllowOn() {
        Context c = appCtx;
        if (c == null) return false;
        try {
            return c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getBoolean("auto_allow", false);
        } catch (Exception e) {
            return false;
        }
    }

    // --------------------------------------------------- part routing

    private static String roleOf(Tx t, String mid) {
        MsgInfo mi = t.msgs.get(mid);
        return mi != null && mi.role != null ? mi.role : "assistant";
    }

    /** Apply one part. Called from main (SSE delivery) or hopped to main
     *  by the upserts when called from IO threads (replays). */
    public static void applyPart(Map<String, Object> part, String roleHint) {
        try {
            String type = Json.str(part, "type");
            if (type == null) return;
            String sid = Json.str(part, "sessionID");
            final Tx t = txnFor(sid);
            lastPartTs = System.currentTimeMillis();
            String mid = Json.str(part, "messageID");
            if (mid == null) mid = "m" + Integer.toHexString(System.identityHashCode(part));
            String pid = Json.str(part, "id");
            // P26: the counter only exists to disambiguate parts WITHOUT a
            // stable id. Counting every stable-keyed part too made the map
            // grow one entry per (message,type) pair — unbounded on a
            // year-long run. Now it stays near-empty on real traffic and
            // carries a hard cap for the pid-less flood case.
            int n = 0;
            if (pid == null || pid.isEmpty()) {
                synchronized (LOCK) { n = mergeCount(t, mid + "|" + type); }
            }
            final String key = (pid != null && !pid.isEmpty())
                    ? mid + "|" + pid : mid + "|" + type + "#" + n;
            final String role = roleHint != null ? roleHint : roleOf(t, mid);

            switch (type) {
                case "text": {
                    final String txt = nz(Json.str(part, "text"), "");
                    if ("user".equals(role)) upsertUser(t, key, txt);
                    else upsertText(t, key, mid, txt);
                    break;
                }
                case "reasoning": {
                    if ("user".equals(role)) break;
                    upsertReason(t, key, mid, nz(Json.str(part, "text"), ""));
                    break;
                }
                case "tool":
                    upsertTool(t, toolRow(key, part));
                    captureEditFocus(part);
                    break;
                case "patch":
                    upsertTool(t, patchRow(key, part));
                    break;
                case "file": {                       // image parts → bubbles
                    String mime = Json.str(part, "mime");
                    String url = Json.str(part, "url");
                    if (mime != null && mime.startsWith("image/")
                            && url != null && url.startsWith("data:")) {
                        upsertImage(t, key, url, null, "", role);
                    }
                    break;
                }
                default: break; // step-start/-finish, agent, … hidden like the TUI
            }
        } catch (Exception e) {
            // a malformed part must never take the hub down
        } catch (Throwable e) {
            Trail.record(appCtx, "hub part", e);
        }
    }

    /** Route a part/message to its session's transcript. The DISPLAYED
     *  session uses cur; anything else streams into (or creates) its
     *  archived Tx — visible the moment the user opens that session. */
    private static Tx txnFor(String sid) {
        if (sid == null || sid.equals(sessionId)) return cur;
        synchronized (LOCK) {
            Tx t = archive.get(sid);
            if (t == null) {
                t = new Tx();
                archive.put(sid, t);
            }
            evictArchive(sid);
            return t;
        }
    }

    /** Shrink the archive, never evicting the queried session or a
     *  session whose run is still streaming. */
    private static void evictArchive(String protect) {
        while (archive.size() > ARCHIVE_CAP) {
            String victim = null;
            for (String k : archive.keySet()) {
                if (!k.equals(protect) && !k.equals(runSessionId)) {
                    victim = k;
                    break;
                }
            }
            if (victim == null) break;      // everything left is protected
            archive.remove(victim);
        }
    }

    private static int mergeCount(Tx t, String k) {
        Integer c = t.typeCount.get(k);
        int n = c == null ? 1 : c + 1;
        if (t.typeCount.size() > TYPE_COUNT_CAP) t.typeCount.clear();   // P26 cap
        t.typeCount.put(k, n);
        return n;
    }

    // --------------------------------------------------------- upserts

    static void upsertUser(final Tx t, final String key, final String text) {
        main(() -> {
            synchronized (LOCK) {
                Row r = rowIn(t, key);
                if (r == null) {
                    r = new Row();
                    r.kind = K_USER;
                    r.key = key;
                    r.ts = System.currentTimeMillis();
                    r.text.append(text);
                    t.rows.add(r);
                    t.idxByKey.put(key, t.rows.size() - 1);
                    t.rowsAdded++;
                } else {
                    if (!text.contentEquals(r.text)) {
                        r.text.setLength(0);
                        r.text.append(text);
                    } else return;
                }
            }
            afterUpsert(t, key);
        });
    }

    static void upsertText(final Tx t, final String key, final String mid, final String text) {
        main(() -> {
            synchronized (LOCK) {
                Row r = rowIn(t, key);
                if (r == null) {
                    r = new Row();
                    r.kind = K_ASSISTANT;
                    r.key = key;
                    r.ts = System.currentTimeMillis();
                    r.text.append(mergeText("", text));
                    MsgInfo mi = t.msgs.get(mid);
                    r.meta = mi == null ? null : mi.meta;
                    t.rows.add(r);
                    t.idxByKey.put(key, t.rows.size() - 1);
                    t.rowsAdded++;
                } else {
                    String merged = mergeText(r.text.toString(), text);
                    boolean changed = !merged.contentEquals(r.text);
                    if (changed) {
                        r.text.setLength(0);
                        r.text.append(merged);
                    }
                    MsgInfo mi = t.msgs.get(mid);
                    String meta = mi == null ? null : mi.meta;
                    boolean metaChanged = meta != null && !meta.equals(r.meta);
                    if (metaChanged) r.meta = meta;
                    if (!changed && !metaChanged) return;
                }
            }
            afterUpsert(t, key);
        });
    }

    static void upsertReason(final Tx t, final String key, final String mid, final String text) {
        main(() -> {
            synchronized (LOCK) {
                Row r = rowIn(t, key);
                if (r == null) {
                    r = new Row();
                    r.kind = K_REASON;
                    r.key = key;
                    r.ts = System.currentTimeMillis();
                    r.text.append(mergeText("", text));
                    t.rows.add(r);
                    t.idxByKey.put(key, t.rows.size() - 1);
                    t.rowsAdded++;
                } else {
                    String merged = mergeText(r.text.toString(), text);
                    if (!merged.contentEquals(r.text)) {
                        r.text.setLength(0);
                        r.text.append(merged);
                    } else return;
                }
            }
            afterUpsert(t, key);
        });
    }

    static void upsertTool(final Tx t, final Row incoming) {
        final String key = incoming.key;
        main(() -> {
            synchronized (LOCK) {
                Row r = rowIn(t, key);
                boolean changed;
                if (r == null) {
                    r = incoming;
                    t.rows.add(r);
                    t.idxByKey.put(r.key, t.rows.size() - 1);
                    t.rowsAdded++;
                    changed = true;
                } else {
                    // P24: Objects.equals — a null status/title on either side
                    // must count as "changed", never NPE the add path.
                    changed = !java.util.Objects.equals(r.status, incoming.status)
                            || r.input.length() != incoming.input.length()
                            || r.output.length() != incoming.output.length()
                            || !java.util.Objects.equals(r.title, incoming.title);
                    r.tool = incoming.tool;
                    r.status = incoming.status;
                    r.title = incoming.title;
                    r.input.setLength(0);
                    r.input.append(incoming.input);
                    r.output.setLength(0);
                    r.output.append(incoming.output);
                }
                if ("error".equals(r.status)) r.open = true;  // never hide failures
                if (!changed) return;
            }
            afterUpsert(t, key);
        });
    }

    /** Post-mutation: trim when oversized, then repaint the one row. */
    private static void afterUpsert(Tx t, String key) {
        if (purgeTrimLocked(t)) {
            notifyReset();
            return;
        }
        notifyRow(key);
    }

    static Row rowIn(Tx t, String key) {
        Integer i = t.idxByKey.get(key);
        return (i == null || i >= t.rows.size()) ? null : t.rows.get(i);
    }

    /** SSE sends full part state; adopt the growth, ignore truncations. */
    static String mergeText(String curText, String next) {
        if (curText == null || curText.isEmpty()) return next;
        if (next == null) return curText;
        if (next.equals(curText)) return curText;
        if (next.startsWith(curText)) return next;   // grew
        if (curText.startsWith(next)) return curText; // truncated echo
        return next;                                  // changed → replace
    }

    static Row toolRow(String key, Map<String, Object> part) {
        Row r = new Row();
        r.kind = K_TOOL;
        r.key = key;
        r.ts = System.currentTimeMillis();
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
        Map<String, Object> errM = Json.map(state, "error");
        if (errM != null) {
            String em = Json.str(errM, "message");
            if (em == null) em = Json.findErrorText(errM, 0);
            if (em != null && !em.isEmpty()) {
                if (r.output.length() > 0) r.output.append('\n');
                r.output.append("✕ ").append(em);
            }
        }
        return r;
    }

    static Row patchRow(String key, Map<String, Object> part) {
        Row r = new Row();
        r.kind = K_TOOL;
        r.key = key;
        r.ts = System.currentTimeMillis();
        r.tool = "patch";
        r.status = "completed";
        List<Object> files = Json.list(part, "files");
        int n = files == null ? 0 : files.size();
        r.title = n + " file" + (n == 1 ? "" : "s") + " changed";
        if (files != null) for (Object f : files) r.output.append(f).append('\n');
        return r;
    }

    // ------------------------------------------------- edit-tool focus

    /** P17: remember what the edit/write tools just wrote so the live
     *  card's PEEK can center on the exact edited line. The tool input
     *  key names drifted across opencode versions, so every plausible
     *  field is tried — worst case the peek falls back to the tail. */
    private static void captureEditFocus(Map<String, Object> part) {
        try {
            String tool = Json.str(part, "tool");
            if (tool == null || editRoot == null) return;
            Map<String, Object> state = Json.map(part, "state");
            if (state == null) state = part;
            Map<String, Object> in = Json.map(state, "input");
            if (in == null) return;
            String path = firstStr(in, "path", "filePath", "file");
            if (path == null || path.isEmpty()) return;
            File f = new File(path);
            String abs = f.isAbsolute() ? f.getAbsolutePath()
                    : new File(editRoot, path).getAbsolutePath();
            String snippet = null;
            if ("edit".equals(tool)) snippet = firstStr(in, "newString", "new_string", "replacement");
            else if ("write".equals(tool)) snippet = firstStr(in, "content", "text");
            if (snippet == null || snippet.trim().isEmpty()) return;
            if (snippet.length() > 400) snippet = snippet.substring(0, 400);
            synchronized (editFocus) { editFocus.put(abs, snippet); }
        } catch (Exception ignored) {
            // a malformed tool part must never take the feed down
        }
    }

    private static String firstStr(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            String v = Json.str(m, k);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    // ------------------------------------------------- message info

    /** message.updated / history items → token+cost bookkeeping for one
     *  message, meta propagation onto its assistant row, error surfacing. */
    static void applyMessageInfo(final Tx t, Map<String, Object> info) {
        if (info == null) return;
        String mid = Json.str(info, "id");
        if (mid == null) return;
        String role = Json.str(info, "role");
        synchronized (LOCK) {
            MsgInfo mi = t.msgs.get(mid);
            if (mi == null) {
                mi = new MsgInfo();
                t.msgs.put(mid, mi);
            }
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
        // P12/P25: per-message cost/tok are STORED (not accumulated) — the
        // server sends cumulative per-message values. Session sums re-add.
        final double fCost = cost;
        final long fTok = total;
        final boolean hasSpend = total > 0 || cost > 0;
        if (hasSpend && "assistant".equals(role)) {
            synchronized (LOCK) { t.lastAssistantTok = fTok; }   // P25 depth input
        }
        Map<String, Object> e = Json.map(info, "error");
        final String fErrName = e != null ? nz(Json.str(e, "name"), "error") : null;
        final String fErrMsg = e != null
                ? nz(Json.str(e, "message"), Json.findErrorText(e, 0)) : null;
        // P11: message-level Model-not-found also self-heals. Clearing is
        // idempotent, so repeats are harmless.
        if (fErrMsg != null && isModelNotFound(fErrMsg)) {
            Models.clear(appCtx);
            notifySpend();
        }
        final String fMid = mid;
        final String fMeta = meta;
        if (meta != null || e != null) main(() -> {
            boolean showError;
            synchronized (LOCK) {
                MsgInfo mi = t.msgs.get(fMid);
                if (mi == null) { mi = new MsgInfo(); t.msgs.put(fMid, mi); }
                if (fMeta != null) mi.meta = fMeta;
                if (hasSpend) {
                    // P26: move the totals by DELTA — safe for re-reported
                    // (cumulative or corrected) values, and the eviction
                    // below can never lose a cent/token already counted.
                    t.costSum += fCost - mi.cost;
                    t.tokSum += fTok - mi.tok;
                    mi.cost = fCost;
                    mi.tok = fTok;
                    // refresh recency: the freshest messages survive the cap
                    t.msgs.remove(fMid);
                    t.msgs.put(fMid, mi);
                    while (t.msgs.size() > MSG_CAP) {
                        String eldest = t.msgs.keySet().iterator().next();
                        t.msgs.remove(eldest);
                    }
                }
                showError = mi != null && !mi.errorShown && fErrName != null;
                if (showError) mi.errorShown = true;
                if (fMeta != null) {
                    for (int i = t.rows.size() - 1; i >= 0; i--) {
                        Row r = t.rows.get(i);
                        if (r.kind == K_ASSISTANT && r.key != null
                                && r.key.startsWith(fMid + "|")) {
                            if (!fMeta.equals(r.meta)) {
                                r.meta = fMeta;
                                notifyRow(r.key);
                            }
                            break;
                        }
                    }
                }
            }
            if (hasSpend) notifySpend();
            if (showError) {
                err("✕ " + fErrName, fErrMsg == null ? "" : fErrMsg,
                        String.valueOf(info));
                setBusy(false);
            }
        });
    }

    // ------------------------------------------------------- send path

    /** The send button. Full orchestration, hub-owned: the POST holds an
     *  IO thread for the whole run, so the chat screen can close freely
     *  mid-turn. The run is never aborted from here — only the user's
     *  stop button (abort) does that. */
    public static void send(String q) {
        final String text = q == null ? "" : q.trim();
        if (text.isEmpty() || busy || !sending.compareAndSet(false, true)) return;
        lastUserText = text;
        runHadOutput = false;
        IO.execute(() -> {
            try {
                String sid = ensureSession();
                if (sid == null) {
                    sys("server not healthy yet — try again in a moment "
                            + "(⌘ → Restart server if it persists)");
                    return;
                }
                validateSelectedModel();          // P11: self-heal stale picks
                lastPartTs = System.currentTimeMillis();
                runSessionId = sid;               // THIS send owns busy now
                setBusy(true);
                List<String> bodies = buildBodies(text, Models.selected(appCtx), agent);
                Api.Resp r = null;
                boolean modelDropped = false;
                for (int i = 0; i < bodies.size(); i++) {
                    // P18: 900s read budget — a long tool loop on a slow
                    // free model can legitimately out-think any smaller one.
                    r = Api.post("/session/" + sid + "/message", bodies.get(i), 900_000);
                    if (r.ok()) {
                        // P11: variant indices 0–1 still carry the model
                        // (ma, m); later ones silently lost the pick.
                        if (i >= 2 && Models.selected(appCtx) != null) modelDropped = true;
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
                        // P11 self-heal: the saved model is gone from the
                        // server's catalog (the free-model lineup rotates).
                        modelFixRetried = true;
                        Models.clear(appCtx);
                        notifySpend();
                        sys("⚠ the saved model was rejected by the server "
                                + "(the free-model lineup rotates) — cleared it; "
                                + "retrying with the server default…");
                        sendText(sid, text);
                        return;
                    }
                    err("send failed · HTTP " + st, detail, raw);
                    setBusy(false);
                    return;
                }
                if (modelDropped)
                    sys("note: the server ignored the picked model for this "
                            + "message — it answered with its default model");
                reconcile(r.body);
                H.removeCallbacks(watchdog);
                H.postDelayed(watchdog, 2000);
            } catch (Throwable e) {
                // P18: the POST timed out but the RUN may still be alive
                // server-side — never kill it, never say "send failed".
                // P23: Throwable breadth — an Error here must not kill the app.
                if (Resilience.isSendTimeout(e)) {
                    sys("⏱ " + Resilience.prettyNetError(e)
                            + " — still watching the run; tap ■ to stop if nothing moves");
                    H.removeCallbacks(watchdog);
                    H.postDelayed(watchdog, 1200);
                } else if (Resilience.isBrokenPipe(e)) {
                    sys("⚠ " + Resilience.prettyNetError(e)
                            + " — the sandbox restarts itself; resend this message in a moment");
                    setBusy(false);
                } else {
                    Trail.record(appCtx, "hub send", e);
                    sys("send failed · " + Resilience.prettyNetError(e));
                    setBusy(false);
                }
            } finally {
                sending.set(false);
            }
        });
    }

    /** Fire-and-track the same text again (self-heal / stream-flake retry). */
    private static void sendText(final String sid, final String q) {
        IO.execute(() -> {
            try {
                lastPartTs = System.currentTimeMillis();
                setBusy(true);
                List<String> bodies = buildBodies(q, Models.selected(appCtx), agent);
                Api.Resp r = null;
                for (String body : bodies) {
                    r = Api.post("/session/" + sid + "/message", body, 900_000);
                    if (r.ok()) break;
                }
                if (r == null || !r.ok()) {
                    err("retry failed · HTTP " + (r == null ? 0 : r.status),
                            r == null ? "" : Json.findErrorText(Json.parse(r.body), 0),
                            r == null ? "" : r.body);
                    setBusy(false);
                    return;
                }
                reconcile(r.body);
                H.removeCallbacks(watchdog);
                H.postDelayed(watchdog, 2000);
            } catch (Throwable e) {
                if (Resilience.isSendTimeout(e)) {
                    sys("⏱ " + Resilience.prettyNetError(e)
                            + " — still watching the run; tap ■ to stop if nothing moves");
                    H.removeCallbacks(watchdog);
                    H.postDelayed(watchdog, 1200);
                } else {
                    Trail.record(appCtx, "hub send retry", e);
                    sys("retry failed · " + Resilience.prettyNetError(e));
                    setBusy(false);
                }
            }
        });
    }

    /** Screenshot send (the vision flow's network half). The confirm
     *  sheet stays in the view; the run is hub-owned like every other. */
    public static void sendImage(File jpg, String caption) {
        if (busy || !sending.compareAndSet(false, true)) {
            sys("wait for the current run to finish, then resend");
            return;
        }
        final String cap2 = (caption == null || caption.isEmpty())
                ? "what do you see here?" : caption;
        final String key = "img" + System.currentTimeMillis();
        upsertImage(cur, key, null, jpg.getAbsolutePath(), cap2, "user");
        IO.execute(() -> {
            try {
                String sid = ensureSession();
                if (sid == null) {
                    sys("server not healthy yet — try again in a moment");
                    return;
                }
                validateSelectedModel();
                byte[] bytes = java.nio.file.Files.readAllBytes(jpg.toPath());
                String dataUrl = Vision.dataUrl(bytes);
                lastUserText = cap2;
                runHadOutput = false;
                lastPartTs = System.currentTimeMillis();
                runSessionId = sid;               // THIS send owns busy now
                setBusy(true);

                // ---- path 1: the server's own file part (raw pixels)
                Api.Resp r = null;
                for (String body : buildImageBodies(cap2, dataUrl,
                        Models.selected(appCtx), agent)) {
                    r = Api.post("/session/" + sid + "/message", body, 300_000);
                    if (r.ok()) break;
                }
                if (r != null && r.ok()) {
                    sys("◉ screenshot attached — the agent sees the pixels");
                    reconcile(r.body);
                    H.removeCallbacks(watchdog);
                    H.postDelayed(watchdog, 2000);
                    return;
                }

                // ---- path 2: a FREE vision model describes it (keyless ok)
                sys("◉ asking a free vision model to look at it…");
                String bearer = Vision.zenKey(appCtx);
                IOException last = null;
                for (int i = 0; i < Vision.CANDIDATES.length; i++) {
                    String[] m = Vision.modelAt(i);
                    try {
                        String desc = Vision.describe(m[1], Vision.prompt(cap2),
                                bytes, bearer, 45_000);
                        sys("◉ " + m[1] + " saw the screenshot — feeding the agent");
                        sendText(sid, cap2 + "\n\n[screenshot shared by the user"
                                + " · vision via " + m[0] + "/" + m[1] + "]\n" + desc);
                        return;
                    } catch (IOException e2) {
                        last = e2;               // rotate to the next free model
                    }
                }
                err("vision failed", last == null ? "no model answered"
                        : last.getMessage(), last == null ? "" : String.valueOf(last));
                setBusy(false);
            } catch (Exception e) {
                sys("screenshot send failed: " + e);
                setBusy(false);
            } catch (Throwable e) {
                Trail.record(appCtx, "hub screenshot send", e);
                sys("screenshot send hit an internal error — contained");
                setBusy(false);
            } finally {
                sending.set(false);
            }
        });
    }

    /** Server phrasing for a vanished model (verified P11 LIVE against
     *  v1.18.25). Fires at RUN time (HTTP 200!), so both the POST body
     *  and streamed errors must match it. */
    static boolean isModelNotFound(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.US);
        // P25 fix (caught by the new suite): the exception class is
        // ProviderModelNotFoundError — "provider", not "provided". The
        // old token could never match; the server's "Model not found"
        // phrase was carrying this detection alone.
        return l.contains("model not found") || l.contains("unknown model")
                || l.contains("providermodelnotfound");
    }

    /** True when the error is a transient provider/stream failure that a
     *  single retry can survive (zen streams 504-idle on mobile networks). */
    static boolean isStreamFlake(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.US);
        return l.contains("upstream idle timeout")
                || l.contains("streaming response failed")
                || l.contains("cannot connect")
                || l.contains("connection reset")
                || l.contains("econnreset");
    }

    /** P16: auth-shaped failures — missing/wrong key or the plan wall. */
    static boolean isKeyError(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.US);
        return l.contains("401") || l.contains("unauthorized")
                || l.contains("api key") || l.contains("apikey")
                || l.contains("402") || l.contains("payment required")
                || l.contains("no auth credentials")
                || l.contains("credit balance");
    }

    /** P11: if the saved model is not in the server's live catalog, drop it
     *  BEFORE the request instead of failing the send. Empty fetch (server
     *  hiccup) never clears anything. Never blocks on the network — the
     *  in-memory catalog decides, a background refresh serves the NEXT send.
     *  P26: a FORCED pick (the user deliberately chose a discovery-catalog
     *  model, knowing the free list rotates) is kept — the run attempts it
     *  and the run-time model-not-found self-heal covers a real refusal. */
    private static void validateSelectedModel() {
        String[] sel = Models.selected(appCtx);
        if (sel == null) return;
        List<Models.Prov> provs = Models.lastFetch();
        if (provs == null || provs.isEmpty()) {
            IO.execute(() -> {
                Resilience.guard(() -> Models.fetch(appCtx));
                notifySpend();               // context limit may be known now
            });
            return;                          // unknown state → keep the pick
        }
        if (!keepPick(Models.forced(appCtx),
                Models.available(provs, sel[0], sel[1]))) {
            Models.clear(appCtx);
            notifySpend();
            sys("⚠ saved model " + sel[0] + "/" + sel[1]
                    + " is no longer offered by the server — cleared "
                    + "(⌘ → Model to pick another; the free list rotates)");
        }
    }

    /** Pure rule the JVM suite pins: keep the pick when the server serves
     *  it OR the user explicitly forced a catalog model (try-anyway). */
    static boolean keepPick(boolean forced, boolean available) {
        return forced || available;
    }

    /**
     * Candidates in decreasing explicitness, P11 ORDER FIX: model variants
     * first (ma → m), THEN agent-less/bare. PURE (selection + agent passed
     * in) so the JVM suite pins the ladder without a device.
     */
    static List<String> buildBodies(String q, String[] sel, String agentMode) {
        LinkedHashMap<String, String> variants = new LinkedHashMap<>();
        String text = "{\"type\":\"text\",\"text\":" + Json.quote(q) + "}";
        String model = sel == null ? null
                : "\"model\":{\"providerID\":" + Json.quote(sel[0])
                + ",\"modelID\":" + Json.quote(sel[1]) + "}";
        String ag = "\"agent\":" + Json.quote(agentMode);
        String parts = "\"parts\":[" + text + "]";
        if (model != null) variants.put("ma", "{" + model + "," + ag + "," + parts + "}");
        if (model != null) variants.put("m", "{" + model + "," + parts + "}");
        variants.put("a", "{" + ag + "," + parts + "}");
        variants.put("bare", "{" + parts + "}");
        return new ArrayList<>(variants.values());
    }

    /** buildBodies' sibling: text + image file part, same variant ladder. */
    static List<String> buildImageBodies(String caption, String dataUrl,
                                         String[] sel, String agentMode) {
        LinkedHashMap<String, String> variants = new LinkedHashMap<>();
        String text = "{\"type\":\"text\",\"text\":" + Json.quote(caption) + "}";
        String file = "{\"type\":\"file\",\"mime\":\"image/jpeg\",\"url\":"
                + Json.quote(dataUrl) + "}";
        String model = sel == null ? null
                : "\"model\":{\"providerID\":" + Json.quote(sel[0])
                + ",\"modelID\":" + Json.quote(sel[1]) + "}";
        String ag = "\"agent\":" + Json.quote(agentMode);
        String parts = "\"parts\":[" + text + "," + file + "]";
        if (model != null) variants.put("ma", "{" + model + "," + ag + "," + parts + "}");
        if (model != null) variants.put("m", "{" + model + "," + parts + "}");
        variants.put("a", "{" + ag + "," + parts + "}");
        variants.put("bare", "{" + parts + "}");
        return new ArrayList<>(variants.values());
    }

    private static String ensureSession() {
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
            notifyTitle();
            saveRunState();
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    /** THE only abort path: the user's explicit stop button. Everything
     *  else in the app watches runs; nothing else kills them. */
    public static void abort() {
        final String sid = runSessionId != null ? runSessionId : sessionId;
        sys("■ stop requested");
        IO.execute(() -> {
            try {
                if (sid != null) {
                    Api.Resp r = Api.call("POST", "/session/" + sid + "/abort",
                            null, 10_000);
                    if (!r.ok()) sys("abort returned HTTP " + r.status);
                }
            } catch (Exception e) {
                sys("abort failed: " + e);
            } catch (Throwable e) {
                Trail.record(appCtx, "hub abort", e);
            }
            runSessionId = null;
            setBusy(false);
            saveRunState();
        });
    }

    // ------------------------------------------------------- busy state

    private static void setBusy(boolean b) {
        boolean was = busy;
        busy = b;
        if (b) {
            // runSessionId is set by the SENDERS (send/sendImage) — never
            // here: the P19 re-arm flips busy for a run whose session may
            // not be the one on screen.
            if (!was) {
                startEditWatch();
            }
        } else if (was) {
            stopEditWatch();
            main(RunHub::settleBusyUi);
        }
        notifyBusy();
        if (was != b) saveRunState();
    }

    /** Settle-time cleanup. P26: the live tree NO LONGER lives in the
     *  transcript — the chat hosts it as a pinned footer while the run
     *  is active, and here (run over) it disappears entirely, exactly as
     *  the field asked. What remains: the edit/write tool cards in the
     *  transcript and the files themselves in the project file manager.
     *  Dead empty THINKING cards are purged as before (P20). */
    private static void settleBusyUi() {
        liveSelPath = null;
        liveOpen = null;
        liveWaitingPeek = false;
        purgeEmptyThoughts();
        notifySpend();
        notifyLive();
    }

    /** P19 quiet-end: the feed may die without session.idle/error; after
     *  10 silent minutes stop claiming the run is live. This NEVER aborts
     *  anything server-side, and P19's part re-arm can still flip busy
     *  back on (runSessionId is kept until a real end or a new send). */
    private static final Runnable watchdog = new Runnable() {
        @Override public void run() {
            Throwable t = Resilience.guard(() -> {
                if (busy && System.currentTimeMillis() - lastPartTs
                        > Resilience.quietEndMs()) {
                    setBusy(false);
                } else if (busy) {
                    H.postDelayed(this, 5000);
                }
            });
            if (t != null) Trail.record(appCtx, "hub watchdog", t);
        }
    };

    // ------------------------------------------------- session switching

    /** Open a session (null = new chat). Swaps transcripts (the old one
     *  is archived — a run streaming into it keeps updating its rows
     *  untouched), then replays the last 80 messages from the server's
     *  store. NEVER re-POSTs anything. */
    public static void loadSession(String id) {
        // P26: re-opening the session that is ALREADY displayed must never
        // wipe its transcript (the old swap-then-replay left an empty,
        // "loading" screen until the pull came back) — just re-sync it.
        if (id != null && id.equals(sessionId)) {
            reconcileOnBind();
            return;
        }
        modelFixRetried = false;      // fresh chat → fresh self-heal budget
        flakeRetried = false;
        runHadOutput = false;
        lastUserText = null;
        final String old = sessionId;
        sessionId = id;
        if (id == null) sessionTitle = "New chat";
        main(() -> {
            synchronized (LOCK) {
                if (old != null && !old.equals(id)) {
                    archive.put(old, cur);
                    evictArchive(id);
                }
                Tx fresh = id == null ? null : archive.remove(id);
                cur = fresh != null ? fresh : new Tx();
            }
            notifyReset();
            notifyTitle();
            notifySpend();
        });
        saveRunState();
        if (id == null) return;
        IO.execute(() -> {
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
                                notifyTitle();
                            }
                            break;
                        }
                    }
                }
                Api.Resp r = Api.get("/session/" + id + "/message");
                if (!r.ok()) {
                    replayNeeded = true;       // P26: healthy flip will retry
                    sys("history unavailable · HTTP " + r.status);
                    return;
                }
                List<Object> arr = Json.arr(Json.parse(r.body));
                if (arr == null) return;
                int from = Math.max(0, arr.size() - 80);
                for (int i = from; i < arr.size(); i++) {
                    if (!id.equals(sessionId)) return;  // switched mid-replay
                    Map<String, Object> item = Json.obj(arr.get(i));
                    if (item == null) continue;
                    Map<String, Object> info = Json.map(item, "info");
                    if (info == null) info = item;
                    if (Boolean.TRUE.equals(info.get("synthetic"))) continue;
                    String role = Json.str(info, "role");
                    applyMessageInfo(cur, info);
                    List<Object> parts = Json.list(item, "parts");
                    if (parts == null) parts = Json.list(info, "parts");
                    if (parts != null) for (Object p : parts) {
                        Map<String, Object> pm = Json.obj(p);
                        if (pm != null) applyPart(pm, role);
                    }
                }
            } catch (Exception e) {
                replayNeeded = true;
                sys("history failed: " + e);
            } catch (Throwable e) {
                replayNeeded = true;
                Trail.record(appCtx, "hub history", e);
            }
        });
    }

    /**
     * P20, now hub-owned: called every time the chat screen binds. The
     * hub consumed every SSE event while the screen was away, so the
     * transcript is normally already exact — this re-PULL is the belt to
     * those braces (and the required mid-run re-sync): the session's own
     * message store upserts over what we have. Known parts update in
     * place, missed parts append in order, trimmed rows never resurrect,
     * and a run that FINISHED while away settles with a one-line note.
     * Never re-POSTs, never restarts a healthy stream.
     *
     * P26: on resume this is now the ONLY entry — no loadSession, so a
     * re-open can never swap the live transcript for a fresh (empty) Tx
     * and re-render it from scratch. And a pull that fires while the
     * sandbox is still booting no longer dies silently: it plants
     * {@link #replayPending()}, and the next healthy flip re-runs it.
     */
    public static void reconcileOnBind() {
        final String id = sessionId;
        if (id == null) return;
        IO.execute(() -> {
            try {
                Api.Resp r = Api.get("/session/" + id + "/message");
                if (!r.ok()) {
                    replayNeeded = true;       // boot race / blip → retry on healthy
                    return;
                }
                List<Object> arr = Json.arr(Json.parse(r.body));
                if (arr == null || arr.isEmpty()) {
                    replayNeeded = true;
                    return;
                }
                replayNeeded = false;
                refreshTitleIfPlaceholder(id);
                // P21: the settle rule, extracted into Resilience so the
                // REAL server payloads replay through it in the JVM tests.
                final boolean lastAssistantDone = Resilience.lastAssistantDoneFrom(arr);
                int from = Math.max(0, arr.size() - 80);
                for (int i = from; i < arr.size(); i++) {
                    if (!id.equals(sessionId)) return;  // switched mid-replay
                    Map<String, Object> item = Json.obj(arr.get(i));
                    if (item == null) continue;
                    Map<String, Object> info = Json.map(item, "info");
                    if (info == null) info = item;
                    if (Boolean.TRUE.equals(info.get("synthetic"))) continue;
                    String role = Json.str(info, "role");
                    String mid0 = Json.str(info, "id");
                    boolean knownMsg = false;
                    if (mid0 != null) {
                        synchronized (LOCK) { knownMsg = cur.msgs.containsKey(mid0); }
                    }
                    applyMessageInfo(cur, info);
                    List<Object> parts = Json.list(item, "parts");
                    if (parts == null) parts = Json.list(info, "parts");
                    if (parts == null) continue;
                    for (Object p : parts) {
                        Map<String, Object> pm = Json.obj(p);
                        if (pm == null) continue;
                        // P21: real v1.18.25 fetches carry messageID on every
                        // part — if a future shape drops it, inject the parent
                        // message id so the replay key STILL matches the live key.
                        if (Json.str(pm, "messageID") == null && mid0 != null) {
                            pm.put("messageID", mid0);
                        }
                        String pid = Json.str(pm, "id");
                        if (Resilience.stablePartKey(pid)) {
                            String mid = Json.str(pm, "messageID");
                            if (mid == null) mid = mid0;
                            if (mid != null && partWasTrimmed(cur, mid + "|" + pid))
                                continue;               // ancient, already trimmed
                        } else if (knownMsg) {
                            continue;   // pid-less replay can't rebuild the live key
                        }
                        applyPart(pm, role);
                    }
                }
                final boolean settle = lastAssistantDone;
                if (settle) main(() -> {
                    // settle ONLY the displayed session's own finished run —
                    // never park busy while a DIFFERENT session's run streams
                    if (!busy || isFinishingGuard() || !id.equals(sessionId)) return;
                    if (runSessionId != null && !runSessionId.equals(id)) return;
                    setBusy(false);
                    sys("↩ back — the run finished while you were away; "
                            + "everything it said is right here");
                });
            } catch (Exception e) {
                replayNeeded = true;   // offline / still starting: retry on healthy
            } catch (Throwable e) {
                replayNeeded = true;
                Trail.record(appCtx, "hub replay", e);
            }
        });
    }

    /** True when the last bind-time re-pull could not reach the server
     *  and is waiting for a healthy flip to try again (P26, test seam). */
    public static boolean replayPending() { return replayNeeded; }

    // ------------------------------------------------ project switching

    /** The project root the current session belongs to. Each project has
     *  its OWN server (per-project sandbox) — sessions do NOT carry
     *  across servers, so the old global sessionId made every send after
     *  a deck switch POST into a session the new server has never heard
     *  of ("send failed · HTTP 404", forever). Now the switch is an
     *  event: the hub resets to a fresh chat; the old project's history
     *  is one ≡ session-sheet tap away when you switch back. */
    private static volatile String sessionRoot;

    /** ServerService calls this the moment a server comes up for a root
     *  (event-driven, no polling). Same root → no-op. */
    public static void onProjectRoot(String root) {
        if (root == null) return;
        final String prev = sessionRoot;
        sessionRoot = root;
        if (prev != null && !prev.equals(root)) resetForProjectSwitch();
    }

    private static void resetForProjectSwitch() {
        main(() -> {
            synchronized (LOCK) {
                archive.clear();
                cur = new Tx();
            }
            sessionId = null;
            sessionTitle = "New chat";
            runSessionId = null;
            lastUserText = null;
            modelFixRetried = false;
            flakeRetried = false;
            interruptedNotePending = false;
            replayNeeded = false;            // the old session is unreachable — stop retrying it
            liveSelPath = null;
            liveOpen = null;
            if (editWatcher != null) editWatcher.stop();
            busy = false;                    // the old server (and its run) died with the switch
            notifyReset();
            notifyTitle();
            notifyBusy();
            notifySpend();
            notifyLive();
            saveRunState();
        });
    }

    /** One /session lookup for the display title — only while the hub
     *  still carries a placeholder ("Recovered chat"/"New chat"). Keeps
     *  the cold-start resume path (reconcileOnBind, no loadSession) from
     *  showing a placeholder forever. */
    private static void refreshTitleIfPlaceholder(final String id) {
        String t = sessionTitle;
        if (t != null && !t.isEmpty() && !"New chat".equals(t)
                && !"Recovered chat".equals(t)) return;
        try {
            Api.Resp sl = Api.get("/session");
            if (!sl.ok()) return;
            List<Object> sarr = Json.arr(Json.parse(sl.body));
            if (sarr == null) return;
            for (Object o : sarr) {
                Map<String, Object> m = Json.obj(o);
                if (m != null && id.equals(Json.str(m, "id"))) {
                    String nt = Json.str(m, "title");
                    if (nt != null && !nt.isEmpty()) {
                        sessionTitle = nt;
                        notifyTitle();
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
            // a title is cosmetic — never let it break the replay
        }
    }

    /** The old activity checked isFinishing() before settling; the hub has
     *  no activity — the guard is whether a view is bound at all. */
    private static boolean isFinishingGuard() {
        synchronized (uis) { return uis.isEmpty(); }
    }

    /** POST /session/{id}/message response → same pipeline as SSE. */
    private static void reconcile(Object parsed) {
        try {
            Map<String, Object> o = Json.obj(parsed);
            if (o != null) {
                Map<String, Object> info = Json.map(o, "info");
                if (info == null && Json.str(o, "role") != null) info = o;
                if (info != null) {
                    final Tx t = txnFor(Json.str(info, "sessionID"));
                    applyMessageInfo(t, info);
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
                final Tx t = txnFor(Json.str(inf, "sessionID"));
                applyMessageInfo(t, inf);
                List<Object> ps = Json.list(m, "parts");
                String role = Json.str(inf, "role");
                if (ps != null) for (Object p : ps) {
                    Map<String, Object> pm = Json.obj(p);
                    if (pm != null) applyPart(pm, role);
                }
            }
        } catch (Exception e) {
            // response shape drift must never break the send path
        } catch (Throwable e) {
            Trail.record(appCtx, "hub reconcile", e);
        }
    }

    /** P20: a reasoning part can be born empty and STAY empty when the run
     *  dies before any thinking arrives — an unopenable "THINKING…" card.
     *  The TUI hides empty thoughts; so do we, at every settle point. */
    public static void purgeEmptyThoughts() {
        main(() -> {
            boolean removed = false;
            synchronized (LOCK) {
                for (int i = cur.rows.size() - 1; i >= 0; i--) {
                    Row r = cur.rows.get(i);
                    if (r.kind == K_REASON && r.text.length() == 0) {
                        forgetKey(cur, r.key);
                        cur.rows.remove(i);
                        removed = true;
                    }
                }
                if (removed) {
                    cur.idxByKey.clear();
                    for (int i = 0; i < cur.rows.size(); i++) {
                        Row r = cur.rows.get(i);
                        if (r.key != null) cur.idxByKey.put(r.key, i);
                    }
                }
            }
            if (removed) notifyReset();
        });
    }

    // ================================================= P25: live edit feed
    // Hub-owned now: the DirWatcher runs WHILE A RUN IS ACTIVE — not while
    // the chat is open. Leaving to the deck keeps the shower recording;
    // coming back shows every edit that landed. Event-driven only (P17
    // pattern kept): zero polling, zero steady-state CPU when idle.

    private static DirWatcher editWatcher;
    private static final Map<String, EditPulse.Ev> editFeed = new HashMap<>();
    /** edit-tool snippets keyed by abs path — the peek's line locator.
     *  P26: insertion-ordered with a hard cap — a run that touches ten
     *  thousand files evicts the OLDEST focus instead of growing forever. */
    private static final Map<String, String> editFocus =
            new LinkedHashMap<String, String>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > EDIT_FOCUS_CAP;
        }
    };
    private static final Map<String, String> peekCache = new HashMap<>();
    private static String editRoot;
    /** null = auto (hot → open); TRUE/FALSE = user override. */
    public static Boolean liveOpen;
    /** The peek target (tapped file row). */
    private static volatile String liveSelPath;
    private static volatile boolean liveWaitingPeek;
    private static final Runnable liveCollapse = RunHub::collapseLive;
    private static final Runnable peekReload = RunHub::reloadSelectedPeek;

    private static void startEditWatch() {
        File dir = ServerService.servingDir();
        if (dir == null || !dir.isDirectory()) return;
        if (editWatcher != null && editWatcher.isRunning()
                && dir.getAbsolutePath().equals(editRoot)) return;
        editRoot = dir.getAbsolutePath();
        synchronized (editFeed) { editFeed.clear(); }
        synchronized (editFocus) { editFocus.clear(); }
        synchronized (peekCache) { peekCache.clear(); }
        liveWaitingPeek = false;
        if (editWatcher == null) {
            editWatcher = new DirWatcher(Looper.getMainLooper(), RunHub::onFsChange);
        }
        editWatcher.start(dir);
    }

    private static void stopEditWatch() {
        if (editWatcher != null) editWatcher.stop();
    }

    /** DirWatcher callback — already on the main looper, already debounced. */
    private static void onFsChange(String path, String action) {
        if (!busy) return;                    // watcher is being stopped anyway
        Throwable t = Resilience.guard(() -> {
            long now = System.currentTimeMillis();
            synchronized (editFeed) {
                EditPulse.record(editFeed, editRoot, path, action, now);
            }
            synchronized (peekCache) { peekCache.remove(path); }   // stale peek out
            scheduleLivePeek(path);           // P25: the peek LIVE-UPDATES
            H.removeCallbacks(liveCollapse);
            H.postDelayed(liveCollapse, EditPulse.ACTIVE_MS + 500);
            notifyLive();
        });
        if (t != null) Trail.record(appCtx, "file watch", t);
    }

    /** P25 realtime peek: while a run is active and a file is selected,
     *  every fs event for THAT file re-reads it (coalesced 120 ms) — the
     *  peek tracks the edit like a tail, instead of a one-shot snapshot. */
    private static void scheduleLivePeek(String path) {
        if (!busy || path == null || !path.equals(liveSelPath)) return;
        H.removeCallbacks(peekReload);
        H.postDelayed(peekReload, 120);
    }

    private static void reloadSelectedPeek() {
        String p = liveSelPath;
        if (p == null || !busy) return;
        synchronized (peekCache) { peekCache.remove(p); }
        liveWaitingPeek = false;
        loadPeek(p);
    }

    private static void collapseLive() {
        notifyLive();
    }

    /** View tapped a file row (abs, or null to deselect). */
    public static void setLiveSel(String abs) {
        liveSelPath = abs;
        if (abs != null) {
            liveOpen = Boolean.TRUE;
            synchronized (peekCache) { peekCache.remove(abs); }
            liveWaitingPeek = false;
            loadPeek(abs);
        }
        notifyLive();
    }

    public static String liveSel() { return liveSelPath; }

    // P26: the K_LIVE row is GONE from the transcript model. It used to
    // be inserted at run start — and every tool/text row that streamed in
    // afterwards landed BELOW it, so autoscroll buried the tree above the
    // fold within seconds (the field saw "no files in chat"). The chat
    // now renders the tree from the FEED (liveTree/liveNewest/peek APIs
    // below) in a footer pinned above the composer: always visible while
    // the agent works, gone the moment it settles.

    /** Load the peek window off-thread — a bounded, line-numbered slice. */
    private static void loadPeek(final String abs) {
        if (abs == null) return;
        final String focus;
        synchronized (editFocus) { focus = editFocus.get(abs); }
        String action;
        synchronized (editFeed) {
            EditPulse.Ev ev = editFeed.get(abs);
            action = ev == null ? "mod" : ev.action;
        }
        final boolean deleted = "del".equals(action);
        IO.execute(() -> {
            String text;
            if (deleted) {
                text = "  (deleted)";
            } else {
                try {
                    File f = new File(abs);
                    if (f.length() > 2_000_000) {
                        text = "  (file too large to peek — open it in Files)";
                    } else {
                        try (FileInputStream fin = new FileInputStream(f)) {
                            String content = Api.readAll(fin);
                            text = EditPulse.peek(content, focus, EditPulse.PEEK_LINES);
                        }
                    }
                } catch (Exception e2) {
                    text = "  (can't peek: " + e2.getMessage() + ")";
                }
            }
            final String t = text;
            main(() -> {
                synchronized (peekCache) { peekCache.put(abs, t); }
                liveWaitingPeek = false;
                notifyLive();
            });
        });
    }

    // ---- view-facing snapshots (all main-thread reads, lock-scoped) ----

    public static List<EditPulse.TNode> liveTree() {
        synchronized (editFeed) {
            return EditPulse.tree(editFeed, EditPulse.MAX_PATHS);
        }
    }

    /** Newest event's abs path (for auto-highlight), or null. */
    public static String liveNewest() {
        synchronized (editFeed) {
            List<EditPulse.Ev> top = EditPulse.picks(editFeed, 1);
            return top.isEmpty() ? null : top.get(0).abs;
        }
    }

    public static String liveSummaryLine(boolean settled) {
        synchronized (editFeed) {
            if (editFeed.isEmpty()) return "watching project files…";
            List<EditPulse.Ev> top = EditPulse.picks(editFeed, 1);
            return EditPulse.summary(editFeed)
                    + (top.isEmpty() ? "" : "  ·  " + top.get(0).rel);
        }
    }

    public static boolean liveHot() {
        synchronized (editFeed) {
            return EditPulse.hot(editFeed, System.currentTimeMillis());
        }
    }

    /** How many distinct touched files the shower currently remembers. */
    public static int liveCount() {
        synchronized (editFeed) { return editFeed.size(); }
    }

    /** View asks for the selected file's peek; loads it when not cached
     *  and not already loading (the one-shot→live reload entry point). */
    public static void ensurePeek(String abs) {
        if (abs == null) return;
        boolean missing;
        synchronized (peekCache) { missing = peekCache.get(abs) == null; }
        if (missing && !liveWaitingPeek) {
            liveWaitingPeek = true;
            loadPeek(abs);
        }
    }

    public static String peekFor(String abs) {
        synchronized (peekCache) { return peekCache.get(abs); }
    }

    public static boolean peekWaiting() { return liveWaitingPeek; }

    public static String focusFor(String abs) {
        synchronized (editFocus) { return editFocus.get(abs); }
    }

    public static String actionFor(String abs) {
        synchronized (editFeed) {
            EditPulse.Ev ev = editFeed.get(abs);
            return ev == null ? "mod" : ev.action;
        }
    }

    // ================================================= P25: image decode
    // Moved from the view: runs land image parts while NO screen is bound,
    // so re-entering the chat shows them instantly (cache-file reuse from
    // P21 keeps the cost one-decode-per-image).

    /** decoded chat bubbles, keyed by row key (bounded, eldest evicted).
     *  Evicted bitmaps are NOT recycled — a visible row may still draw. */
    public static final LinkedHashMap<String, Bitmap> imageCache =
            new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
                    return size() > 6;
                }
            };
    /** rows whose image decode failed — never retried (no repaint loops). */
    private static final java.util.Set<String> failedImgs = new java.util.HashSet<>();

    static void upsertImage(final Tx t, final String key, final String dataUrl,
                            final String localPath, final String caption,
                            final String role) {
        main(() -> {
            Row r;
            boolean added;
            synchronized (LOCK) {
                r = rowIn(t, key);
                if (r == null) {
                    r = new Row();
                    r.kind = K_IMAGE;
                    r.key = key;
                    r.ts = System.currentTimeMillis();
                    r.tool = role;                        // alignment marker
                    r.text.append(caption == null ? "" : caption);
                    t.rows.add(r);
                    t.idxByKey.put(key, t.rows.size() - 1);
                    t.rowsAdded++;
                    added = true;
                } else {
                    added = false;
                    if (caption != null && !caption.isEmpty()
                            && !caption.contentEquals(r.text)) {
                        r.text.setLength(0);
                        r.text.append(caption);
                    }
                }
            }
            if (added) {
                if (dataUrl != null) decodeDataUrl(r, dataUrl);
                else if (localPath != null) {
                    r.title = localPath;
                    decodeLocalImage(r, localPath);
                }
            }
            notifyRow(key);
        });
    }

    /** data:<mime>;base64,<payload> → cache file → bubble bitmap. */
    private static void decodeDataUrl(final Row r, String dataUrl) {
        IO.execute(() -> {
            try {
                int comma = dataUrl.indexOf(',');
                String header = comma > 0 ? dataUrl.substring(5, comma) : "image/jpeg";
                String ext = header.contains("png") ? "png" : "jpg";
                Context c = appCtx;
                if (c == null) return;
                File dir = new File(c.getCacheDir(), "vision");
                if (!dir.isDirectory()) dir.mkdirs();
                File f = new File(dir, "msg-" + Integer.toHexString(r.key.hashCode())
                        + "." + ext);
                if (f.isFile() && f.length() > 0) {
                    // P21: a resume replay re-delivers the SAME data: URL —
                    // the cache file from the first decode is still good.
                    r.title = f.getAbsolutePath();
                    decodeLocalImage(r, f.getAbsolutePath());
                    notifyRow(r.key);
                    return;
                }
                String payload = comma > 0 ? dataUrl.substring(comma + 1) : "";
                if (payload.length() > 12_000_000) throw new java.io.IOException("image too large");
                byte[] bytes = java.util.Base64.getDecoder().decode(payload);
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    fo.write(bytes);
                }
                r.title = f.getAbsolutePath();
                decodeLocalImage(r, f.getAbsolutePath());
                notifyRow(r.key);
            } catch (Exception e) {
                sys("image part could not be decoded: " + e.getMessage());
            } catch (Throwable e) {
                Trail.record(appCtx, "image decode", e);
            }
        });
    }

    /** View-facing decode retry (buildImageView's missing-bitmap path). */
    public static void retryDecode(Row r) {
        if (r == null || r.title == null) return;
        synchronized (failedImgs) {
            if (failedImgs.contains(r.key)) return;
        }
        decodeLocalImage(r, r.title);
    }

    private static void decodeLocalImage(final Row r, final String path) {
        synchronized (failedImgs) {
            if (failedImgs.contains(r.key)) return;
        }
        synchronized (imageCache) {
            if (imageCache.get(r.key) != null) {
                notifyRow(r.key);
                return;
            }
        }
        IO.execute(() -> {
            Throwable t = Resilience.guard(() -> {
                Bitmap bm = Vision.decodeBounded(path, 1024);
                if (bm == null) {
                    synchronized (failedImgs) { failedImgs.add(r.key); }
                    sys("image could not be decoded");
                    return;
                }
                synchronized (imageCache) { imageCache.put(r.key, bm); }
                notifyRow(r.key);
            });
            if (t != null) Trail.record(appCtx, "image decode", t);
        });
    }

    // ------------------------------------------------------ permissions

    /** One reply ladder, verified against the shipped v1.18.25 binary:
     *  POST /permission/{requestID}/reply {reply} ∈ {once, always, reject};
     *  v2 + legacy fallbacks. The activity passes a callback for its toast;
     *  unattended auto-allow passes null (a transcript note suffices). */
    public static void answerPermission(final String id, final String response,
                                        final java.util.function.BiConsumer<Boolean, String> cb) {
        PERM.execute(() -> {
            String errS = null;
            boolean ok = false;
            try {
                Api.Resp r = Api.post("/permission/" + id + "/reply",
                        "{\"reply\":" + Json.quote(response) + "}", 15_000);
                ok = r.ok();
                if (!ok && sessionId != null) {
                    r = Api.post("/api/session/" + sessionId + "/permission/"
                                    + id + "/reply",
                            "{\"reply\":" + Json.quote(response) + "}", 15_000);
                    ok = r.ok();
                }
                if (!ok && sessionId != null) {
                    r = Api.post("/session/" + sessionId + "/permissions/" + id,
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
            main(() -> {
                if (cb != null) cb.accept(done, f);
                else if (!done) sys("permission reply failed · " + f);
                notifyPerms();
            });
        });
    }

    // ------------------------------------------------------------ helpers

    static double num(Object o) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : 0;
    }

    static String nz(String s, String dflt) {
        return (s == null || s.isEmpty()) ? dflt : s;
    }
}

