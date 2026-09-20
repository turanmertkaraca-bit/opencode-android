package ai.opencode.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


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
        /** P27: provider-reported CACHED input tokens for this message
         *  (prompt cache hits — billed at the discounted rate). Surfaced
         *  in the Σ popover so "are the money-saving features on?" has a
         *  visible answer: cached tokens ARE being counted as cached. */
        public long cacheRead;
        /** P39: compaction summary message (assistant, info.summary) — the
         *  amnesia detector never judges across one: compacting
         *  legitimately shrinks the payload. */
        public boolean summary;
        /** P40: the message's agent field — "compaction" marks the root a
         *  summarize run leaves behind. Sticky like summary, live SSE and
         *  replay alike: the replayed GET info carries agent where older
         *  builds carried only the live summary flag. */
        public String agent;
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
        /** P27: high-water of lastAssistantTok WITHIN the current run.
         *  Providers report per-message usage unevenly mid-run (cache-read
         *  accounting lands on some turns and not others), which made the
         *  Σ pill swing (field: "said 47% once and turned back into 8%").
         *  Context depth is physically monotone within one run — the pill
         *  reads the peak while busy, the exact value at rest. Reset at
         *  every run start; cleared on session resets. */
        public long depthPeak;
        public int rowsAdded;              // monotonically bumps on add (view cue)
        /** P37: the leaky-model note is once per session, never per part. */
        public boolean dsmlNoted;
        // P26: RUNNING session sums. A year-long run must not re-iterate
        // an ever-growing msgs map on every pill paint — totals move by
        // delta on each message update and survive the eviction of old
        // MsgInfo entries.
        public double costSum;
        public long tokSum;
        /** P42-check: the MsgInfo entries the MSG_CAP eviction dropped —
         *  their booked cost/tok already live inside costSum/tokSum. When
         *  a full-store pass recreates one, the delta must come from the
         *  evicted values, not from zero, or every bind re-books the
         *  evicted tail into the session Σ. */
        public final Map<String, double[]> evictedSums = new HashMap<>();
        /** P42-check: set when a compaction summary lands live; the FIRST
         *  token-bearing assistant update after it resets the depth inputs
         *  (the note itself often arrives tokenless — keying the reset to
         *  it wasted the one shot and kept the pre-compaction depth). */
        public boolean awaitDepthReset;
        /** P31: which session this transcript belongs to (null for a
         *  fresh chat). Lets message-level errors release the right run
         *  when several run in parallel. */
        public String sid;
        /** P43: last time THIS session produced an event (part delta,
         *  message update, idle) or carried a send — the cache-beat
         *  scheduler reads it as "the provider's cache was refreshed
         *  then". Per-session on purpose: another chat streaming does
         *  not keep THIS chat's cache warm. */
        public volatile long lastEventAt;
    }

    private static final int TRIMMED_KEY_CAP = 4096;
    // package-private: the JVM suite pins these walls
    // P43: 450/350 → 1200/900 — the field report was "the tools keep
    // going away": a session that ran for hours shed its older tool
    // cards at the 450-row wall while it streamed, and a reopened chat
    // painted only the last 80 stored messages. Rendering must keep up
    // with real sessions; memory stays bounded and the money ledger is
    // unaffected (accounting walks the full store regardless).
    static final int TRIM_OVER = 1200, TRIM_KEEP = 900;
    /** P43: how many stored messages a (re)open renders — was 80, which
     *  amputated hours of tool history the moment the user left and came
     *  back. The full store still reaches the model and the ledger. */
    static final int REPLAY_RENDER = 600;
    static final int ARCHIVE_CAP = 4;
    /** P26 long-run caps: message bookkeeping per session, pid-less part
     *  counters, edit-focus snippets. A month/year run grows INTO these
     *  walls and stays there — bounded memory, evergreen behavior. */
    /** P42: raised 400 → 2000 — this map is the MONEY accounting source
     *  (per-message cost/token); an evicted entry re-booking its cost
     *  from zero is exactly the drift the field reported. 2000 MsgInfo
     *  entries are a few hundred KB worst case; the RENDERING cap stays
     *  the 80-window in the replay loops. */
    static final int MSG_CAP = 2000;
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
    /**
     * P31 — PARALLEL RUNS. The busy flag is no longer one global gate:
     * {@code runs} holds every session with a live run (sid → last part
     * ts), so a script can stream in one chat while the user keeps
     * working in another. The boolean {@code busy} stays as the cheap
     * derived "any run at all" (wake lock, hibernate gate, notifications);
     * the per-session truth is {@link #busyFor(String)}.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> runs =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Derived any-run flag — maintained by claimRun/releaseRun. */
    private static volatile boolean busy;
    /** P31: sid of the last send per session (re-arm window anchor). */
    private static final java.util.Map<String, Long> sentAt =
            new java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Long> e) { return size() > 64; }
            };
    /** P31: sid → when that session's run last ended CLEANLY (idle). */
    private static final java.util.Map<String, Long> idledAt =
            new java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Long> e) { return size() > 64; }
            };
    private static volatile String agent = "build";   // Tab parity: build <-> plan
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
    /**
     * P30 — per-session "the model has been TOLD this reply style" state.
     * sessionId → the last style state announced into THAT session via a
     * <system-reminder> note (TerseMode.wrap). Null/absent = this session
     * never heard it (a fresh chat, or the app restarted — then the next
     * send with terse ON re-announces once, a few tokens of honesty).
     * LRU-capped at 64 sessions like every other per-session map here.
     */
    private static final java.util.Map<String, Boolean> styleTold =
            new java.util.LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Boolean> e) {
                    return size() > 64;
                }
            };
    /**
     * P35 — per-session render-note state: FALSE = the agent wrote an
     * .html page in THIS session and the next send must carry the
     * render-check note; TRUE = the note was delivered (marked on send
     * success only, exactly like styleMark). Absent = no HTML activity
     * in the session → no tokens spent. LRU-capped at 64 like styleTold.
     */
    private static final java.util.Map<String, Boolean> renderTold =
            new java.util.LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Boolean> e) {
                    return size() > 64;
                }
            };
    /**
     * P37 — per-session environment-note state: TRUE = the map (Debian
     * guest vs host tools + the GitHub-token answer) rode this session's
     * first message. Absent = the session never heard it → the next send
     * carries it once. LRU-capped at 64 like styleTold.
     */
    private static final java.util.Map<String, Boolean> envTold =
            new java.util.LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Boolean> e) {
                    return size() > 64;
                }
            };

    // ------------------------------------------------ P31: run claiming

    /**
     * Claim a run slot for sid (P31 parallel runs). The claim IS the
     * double-tap latch AND the per-session busy state: a second send to
     * the same session sees CLAIM_BUSY; a send to any session once three
     * run in parallel sees CLAIM_CAP. Atomic check-and-put. Returns a
     * RunBook.CLAIM_* code.
     */
    private static int claimRun(String sid) {
        if (sid == null || sid.isEmpty()) return RunBook.CLAIM_BUSY;
        int verdict;
        synchronized (runs) {
            List<String> snapshot = new ArrayList<>(runs.keySet());
            verdict = RunBook.claim(snapshot, sid, RunBook.MAX_PARALLEL);
            if (verdict == RunBook.CLAIM_OK)
                runs.put(sid, System.currentTimeMillis());
        }
        if (verdict != RunBook.CLAIM_OK) return verdict;
        synchronized (sentAt) { sentAt.put(sid, System.currentTimeMillis()); }
        synchronized (LOCK) {
            Tx t = txnFor(sid);
            t.depthPeak = t.lastAssistantTok;   // P27: peak is per-run
        }
        boolean was = busy;
        busy = true;
        if (!was) startEditWatch();             // the shared edit feed serves every run
        notifyBusy();
        saveRunState();
        return RunBook.CLAIM_OK;
    }

    /** Release sid's run slot. settle=true = the run ENDED cleanly (idle)
     *  and must never re-arm from stale parts; false = we merely stopped
     *  tracking it locally (abort, failed send, quiet-end watchdog). */
    private static void releaseRun(String sid, boolean settle) {
        if (sid == null) return;
        runs.remove(sid);
        if (settle) {
            synchronized (idledAt) { idledAt.put(sid, System.currentTimeMillis()); }
        }
        if (runs.isEmpty()) {
            busy = false;
            stopEditWatch();
            main(RunHub::settleBusyUi);
        }
        notifyBusy();
        saveRunState();
    }

    /** Touch the run's liveness timestamp (every streamed part). */
    private static void touchRun(String sid) {
        if (sid == null) return;
        Long prev = runs.get(sid);
        if (prev != null) runs.put(sid, System.currentTimeMillis());
        noteActivity(sid);                        // P43: cache-beat clock
    }

    // ------------------------------------------------ P43 cache beats

    /** Session → last activity ms (event or send). The beat decision
     *  reads "how long has THIS session been quiet". */
    private static final Map<String, Long> lastActivity = new HashMap<>();
    /** Session → beats fired in the current quiet stretch. */
    private static final Map<String, Integer> beatCount = new HashMap<>();
    /** Session → consecutive failed beats (drives the backoff). */
    private static final Map<String, Long> beatFails = new HashMap<>();
    /** Session → activity value the beat counter was armed on; when real
     *  activity lands past it, the stretch resets and the count zeroes. */
    private static final Map<String, Long> beatArmedAt = new HashMap<>();

    /** Record that {@code sid} just did something (any event, any send,
     *  any part delta) — the provider's prompt cache was refreshed now. */
    private static void noteActivity(String sid) {
        if (sid == null) return;
        synchronized (lastActivity) {
            lastActivity.put(sid, System.currentTimeMillis());
        }
        Tx t = txnFor(sid);
        if (t != null) t.lastEventAt = System.currentTimeMillis();
    }

    /** The Settings switch (default ON — the field asked for exactly
     *  this behavior; the switch is the off-ramp). */
    public static boolean beatsEnabled() {
        try {
            return appCtx.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getBoolean("cache_beat", true);
        } catch (Exception e) {
            return true;
        }
    }

    /** Called every ~20 s by the open chat screen. Fires a cache beat
     *  when the displayed session has been quiet past the idle window
     *  and the policy says a beat is worth it. Cheap when idle-early:
     *  one map read, no IO. */
    public static void cacheBeatTick() {
        try {
            final String sid = sessionId;
            if (sid == null || !beatsEnabled()) return;
            long now = System.currentTimeMillis();
            long act;
            synchronized (lastActivity) {
                Long v = lastActivity.get(sid);
                act = v == null ? now : v;
            }
            // stretch reset: real activity landed since the last beat →
            // this is a NEW quiet stretch, the per-stretch cap restarts.
            synchronized (beatArmedAt) {
                Long armed = beatArmedAt.get(sid);
                if (armed == null || act > armed) {
                    beatCount.put(sid, 0);
                    beatArmedAt.put(sid, act);
                }
            }
            int beats;
            synchronized (beatCount) { Integer b = beatCount.get(sid); beats = b == null ? 0 : b; }
            long fails;
            synchronized (beatFails) { Long f = beatFails.get(sid); fails = f == null ? 0 : f; }
            long ctx = ctxTokens();
            if (!CacheBeat.shouldFire(now, act, beats, fails, ctx,
                    busyFor(sid), true)) return;
            sendCacheBeat(sid);
        } catch (Throwable ignored) {}
    }

    /** One beat: a two-word reply that re-reads the whole prefix at the
     *  cached rate and restarts the TTL clock. Hub-owned like every
     *  send; claims NO run slot (a beat must never block the user's
     *  next real message), but yields to any run already in flight. */
    private static void sendCacheBeat(final String sid) {
        synchronized (beatCount) {
            Integer b = beatCount.get(sid);
            beatCount.put(sid, (b == null ? 0 : b) + 1);
        }
        IO.execute(() -> {
            try {
                touchRun(sid);
                List<String> bodies = buildBodies(CacheBeat.beatBlock(),
                        Models.selected(appCtx), agent);
                Api.Resp r = null;
                for (String body : bodies) {
                    r = Api.post("/session/" + sid + "/message", body, 120_000);
                    if (r.ok()) break;
                }
                if (r == null || !r.ok()) {
                    synchronized (beatFails) {
                        Long f = beatFails.get(sid);
                        beatFails.put(sid, (f == null ? 0 : f) + 1);
                    }
                    return;                    // quiet backoff — never spam
                }
                synchronized (beatFails) { beatFails.remove(sid); }
                noteActivity(sid);             // the beat itself is activity
            } catch (Throwable ignored) {}
        });
    }

    /** Snapshot views for the pure re-arm rule (RunBook.shouldRearm). */
    private static java.util.Map<String, Long> sentAtMap() {
        synchronized (sentAt) { return new java.util.HashMap<>(sentAt); }
    }
    private static java.util.Map<String, Long> idledAtMap() {
        synchronized (idledAt) { return new java.util.HashMap<>(idledAt); }
    }

    /** P31 test hooks — the suite pins the claim rules from a clean state. */
    static void clearRunsForTest() {
        runs.clear();
        busy = false;
        synchronized (sentAt) { sentAt.clear(); }
        synchronized (idledAt) { idledAt.clear(); }
    }
    static void putRunForTest(String sid) { runs.put(sid, System.currentTimeMillis()); busy = true; }

    // -------------------------------------------------- P31: public state

    /** True when THIS session has a live run (per-chat busy). */
    public static boolean busyFor(String sid) {
        return sid != null && runs.containsKey(sid);
    }

    /** The displayed (or last-displayed) session id, or null for a fresh chat. */
    public static String displayedSession() { return sessionId; }

    /** How many runs are live right now (across all sessions). */
    public static int runningCount() { return runs.size(); }

    /** True when any session OTHER than sid has a live run. */
    public static boolean othersRunning(String sid) {
        for (String s : runs.keySet()) if (!s.equals(sid)) return true;
        return false;
    }

    /** P31 — the all-time credit counter (this device). Moved by the same
     *  delta logic as the session sums, but only for LIVE assistant
     *  messages (replays book nothing), persisted to "oc"/"spend_total"
     *  on every change so a cap survives restarts. The CAP lives in the
     *  "spend_cap" pref; the rules are pure (CreditLimit). */
    private static volatile double spendTotal;
    private static volatile boolean spendLoaded;

    /** P42: the all-time money ledger — sid → (mid → booked cost), kept
     *  in told-state.json. Each message's cost books into the credit
     *  counter EXACTLY ONCE whether it arrived live or in a replay, so
     *  money billed while the app was dead lands the next time its
     *  messages are seen (the old live-only gate silently dropped it —
     *  the field session's “2 dollars” could not be trusted). Sessions
     *  the app has never seen live SEED silently on first replay: their
     *  history predates the counter and re-booking it would lie upward. */
    private static final java.util.LinkedHashMap<String,
            java.util.LinkedHashMap<String, Double>> spendLedger =
            new java.util.LinkedHashMap<>();

    private static void loadSpendTotal() {
        if (spendLoaded) return;
        synchronized (RunHub.class) {
            if (spendLoaded) return;
            try {
                String s = appCtx.getSharedPreferences("oc", Context.MODE_PRIVATE)
                        .getString("spend_total", null);
                spendTotal = s == null ? 0 : Double.parseDouble(s);
            } catch (Exception ignored) {
                spendTotal = 0;
            }
            spendLoaded = true;
        }
    }

    /** P42: book one message's cost into the all-time counter exactly
     *  once. Returns the delta actually booked (0 = nothing new). */
    private static double spendLedgerBook(String sid, String mid,
                                          double cost, boolean live) {
        if (sid == null || sid.isEmpty() || mid == null || mid.isEmpty())
            return 0;
        synchronized (LOCK) {
            java.util.LinkedHashMap<String, Double> per = spendLedger.get(sid);
            boolean known = per != null;
            if (per == null) per = new java.util.LinkedHashMap<>();
            Double prev = per.get(mid);
            double d = cost - (prev == null ? 0 : prev);
            // unknown session replayed → seed silently (see field above)
            if (!live && !known && prev == null) d = 0;
            if (d != 0 || prev == null) {
                per.remove(mid);
                per.put(mid, cost);
            }
            spendLedger.remove(sid);
            spendLedger.put(sid, per);          // refresh session recency
            while (spendLedger.size() > 8) {    // bounded file: newest 8
                String eldest = spendLedger.keySet().iterator().next();
                spendLedger.remove(eldest);
            }
            return d;
        }
    }

    private static void spendDelta(double d) {
        if (d == 0) return;
        loadSpendTotal();
        spendTotal = Math.max(0, spendTotal + d);   // a correction can lower it
        try {
            appCtx.getSharedPreferences("oc", Context.MODE_PRIVATE).edit()
                    .putString("spend_total", String.valueOf(spendTotal)).apply();
        } catch (Exception ignored) {}
    }

    /** P42-check: debounced told-snapshot. The booking path can fire many
     *  times per stream, and the ledger only survives process death if it
     *  reaches disk at (or near) the pace the spend_total pref does — a
     *  stale disk ledger re-books the un-flushed delta on the next replay
     *  (real money shown twice). One pending coalesced write closes that
     *  window; the IO executor serializes it behind whatever is running. */
    private static final java.util.concurrent.atomic.AtomicBoolean toldFlushPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void persistToldSoon() {
        if (!toldFlushPending.compareAndSet(false, true)) return;
        IO.execute(() -> {
            toldFlushPending.set(false);
            persistTold();
        });
    }

    /** All-time spend (this device) — the credit-limit numerator. */
    public static double spendTotal() {
        loadSpendTotal();
        return spendTotal;
    }

    /** Manual reset (Settings → Safety). Never automatic — the user owns it. */
    public static void resetSpendTotal() {
        spendTotal = 0;
        try {
            appCtx.getSharedPreferences("oc", Context.MODE_PRIVATE).edit()
                    .putString("spend_total", "0").apply();
        } catch (Exception ignored) {}
        synchronized (LOCK) { spendLedger.clear(); }   // P42: or the next replay rebooks it all
        persistTold();
    }

    /** The configured cap (0 = no limit), parsed once per read — cheap. */
    public static double spendCap() {
        try {
            return CreditLimit.parseCap(appCtx
                    .getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getString("spend_cap", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** The enforceable credit check — true when spending must be refused. */
    private static boolean creditBlocked() {
        double cap = spendCap();
        if (cap <= 0) return false;
        return CreditLimit.verdict(spendTotal(), cap) == CreditLimit.BLOCK;
    }

    /** The terse preference — a plain app preference since P30. NEVER a
     *  file in the project (the P29 AGENTS.md block leaked into git
     *  diffs and other sessions; the field report killed that). */
    private static boolean tersePref() {
        try {
            return appCtx.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getBoolean("terse", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Record that session sid now runs style state `state` (called only
     *  after a POST that actually carried/omitted the note accordingly). */
    private static void styleMark(String sid, boolean state) {
        if (sid == null) return;
        synchronized (styleTold) { styleTold.put(sid, state); }
        persistTold();
    }

    /** P30: the session's style memory is void — the model must be
     *  re-told on the next send. Used after /compact (the summary that
     *  replaces the window is not guaranteed to carry the note) and by
     *  tests. Pure bookkeeping, no I/O. */
    public static void styleToldReset(String sid) {
        if (sid == null) return;
        synchronized (styleTold) { styleTold.remove(sid); }
        persistTold();
    }

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
        void hubQuestions();           // P50: pending question asks changed
        void hubLive();                // edit feed / peek changed
        void hubReset();               // transcript replaced → full re-render
    }

    private static final List<Ui> uis = new ArrayList<>();

    public static void bindUi(Ui u) {
        boolean added;
        synchronized (uis) { added = !uis.contains(u) && uis.add(u); }
        // P27 — the stale-on-return killer, finally dead at the ROOT: while
        // no view was bound the hub kept mutating the model (SSE never
        // stopped), and those mutations fired notifyRow into an EMPTY sink —
        // the view's per-row cache went stale. A resume-time re-pull then
        // re-delivered identical parts, every upsert saw "!changed" and
        // correctly stayed silent — so NOTHING ever told the view to
        // repaint. The screen sat behind until the activity was recreated
        // (drawer path) — exactly the field repro ("home button → back →
        // stale; drawer → chat → current"). The fix is one honest event:
        // every fresh bind gets a full repaint of the model AS IT IS NOW.
        // Event-driven, zero polling, no re-POST, no stream restart.
        if (added) fire(u::hubReset);
    }

    public static void unbindUi(Ui u) {
        synchronized (uis) { uis.remove(u); }
    }

    private static void fire(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else H.post(r);
    }

    /** P51 — the notify lane can never kill the process. The field OOM
     *  died HERE: notifySpend's loop was the allocation that found a
     *  heap already exhausted by the whole-store replay parse. The loop
     *  is innocent; the process death is not acceptable anyway. P23's
     *  contract (nothing on the send/chat/feed paths dies from a
     *  Throwable) now covers every UI-sink callback: contained, logged,
     *  and an OutOfMemoryError additionally triggers the relief valve
     *  (drop the biggest caches) so the NEXT notification can succeed. */
    private static void uiSafe(String what, Runnable r) {
        Throwable t = Resilience.guard(r);
        if (t == null) return;
        try { Trail.record(appCtx, what, t); } catch (Throwable ignored) {}
        if (t instanceof OutOfMemoryError) relieve();
    }

    /** P51 — emergency relief when the heap is on fire: drop the big,
     *  rebuildable caches (archived transcripts, peek/edit caches), trim
     *  the displayed transcript to its tail, and ask for a collect. The
     *  money ledger (spendLedger / evictedSums) is NEVER touched — the
     *  Σ must stay honest even mid-emergency. */
    static void relieve() {
        try {
            synchronized (LOCK) {
                archive.clear();
                int keep = Math.min(cur.rows.size(), 150);
                if (keep < cur.rows.size()) {
                    List<Row> tail = new ArrayList<>(cur.rows.subList(
                            cur.rows.size() - keep, cur.rows.size()));
                    cur.rows.clear();
                    cur.rows.addAll(tail);
                    cur.idxByKey.clear();
                    for (int i = 0; i < cur.rows.size(); i++) {
                        String k = cur.rows.get(i).key;
                        if (k != null) cur.idxByKey.put(k, i);
                    }
                    cur.rowsAdded++;
                }
            }
            synchronized (peekCache) { peekCache.clear(); }
            synchronized (editFeed) { editFeed.clear(); }
            synchronized (QUESTIONS) { QUESTIONS.clear(); }
            System.gc();
        } catch (Throwable ignored) {
            // relief must never become the crash
        }
    }

    private static void notifyRow(final String key) {
        fire(() -> uiSafe("hub notifyRow", () -> {
            synchronized (uis) { for (Ui u : uis) u.hubRow(key); }
        }));
    }

    private static void notifyReset() {
        fire(() -> uiSafe("hub notifyReset", () -> {
            synchronized (uis) { for (Ui u : uis) u.hubReset(); }
        }));
    }

    private static void notifyBusy() {
        final boolean b = busy;
        fire(() -> uiSafe("hub notifyBusy", () -> {
            synchronized (uis) { for (Ui u : uis) u.hubBusy(b); }
        }));
    }

    private static void notifySpend() {
        fire(() -> uiSafe("hub notifySpend", () -> {
            synchronized (uis) { for (Ui u : uis) u.hubSpend(); }
        }));
    }

    private static void notifyTitle() {
        fire(() -> uiSafe("hub notifyTitle", () -> {
            synchronized (uis) { for (Ui u : uis) u.hubTitle(); }
        }));
    }

    private static void notifyPerms() {
        fire(() -> uiSafe("hub notifyPerms", () -> {
            synchronized (uis) { for (Ui u : uis) u.hubPerms(); }
        }));
    }

    private static void notifyQuestions() {
        fire(() -> uiSafe("hub notifyQuestions", () -> {
            synchronized (uis) { for (Ui u : uis) u.hubQuestions(); }
        }));
    }

    // ------------------------------------------------ P50: question asks

    /** Pending question requests by sessionID (raw question.asked
     *  properties, defensively copied). The question tool and the plan
     *  approval ask live here — the agent BLOCKS server-side until the
     *  user answers, so the store + the pinned card are the only unblock
     *  path. All access synchronized; payloads are plain JSON maps. */
    private static final Map<String, List<Map<String, Object>>> QUESTIONS =
            new HashMap<>();

    /** First pending ask for a session (null = none). */
    public static Map<String, Object> pendingQuestion(String sid) {
        if (sid == null) return null;
        synchronized (QUESTIONS) {
            List<Map<String, Object>> l = QUESTIONS.get(sid);
            return (l == null || l.isEmpty()) ? null : l.get(0);
        }
    }

    /** Pending asks across ALL sessions (any ask keeps the run alive —
     *  the server is blocked on the user, not idle). */
    public static int pendingQuestionCount() {
        synchronized (QUESTIONS) {
            int n = 0;
            for (List<Map<String, Object>> l : QUESTIONS.values()) n += l.size();
            return n;
        }
    }

    static void clearQuestionsForTests() {
        synchronized (QUESTIONS) { QUESTIONS.clear(); }
    }

    private static void notifyLive() {
        fire(() -> uiSafe("hub notifyLive", () -> {
            synchronized (uis) { for (Ui u : uis) u.hubLive(); }
        }));
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
        restoreTold();
    }

    /** Package-visible: JVM tests (P47Test) drive the SSE wiring through
     *  the real listener instead of reflection. */
    static final RunHub HUB = new RunHub();

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

    // ------------------------------------------- P38: told-state on disk

    /** The per-session note ledger survives process death. P37's field
     *  report: continuing a session the morning after re-sent the
     *  environment map — the maps live in memory only, a restart forgets,
     *  and the user sees the block again (and pays the tokens again).
     *  Write-through: the maps stay the source of truth; this file is the
     *  snapshot they restore from at init. */
    private static File toldFile() {
        Context c = appCtx;
        return c == null ? null : new File(c.getFilesDir(), "told-state.json");
    }

    /** Snapshot the three told maps + the P42 spend ledger to disk
     *  (atomic .part swap). */
    private static void persistTold() {
        final StringBuilder sb = new StringBuilder("{\"v\":1");
        synchronized (envTold) { sb.append(",\"env\":").append(toldJson(envTold)); }
        synchronized (renderTold) { sb.append(",\"render\":").append(toldJson(renderTold)); }
        synchronized (styleTold) { sb.append(",\"style\":").append(toldJson(styleTold)); }
        synchronized (LOCK) { sb.append(",\"spend\":").append(spendLedgerJson()); }
        sb.append('}');
        IO.execute(() -> {
            File f = toldFile();
            if (f == null) return;
            try {
                File tmp = new File(f.getParentFile(), f.getName() + ".part");
                try (OutputStream o = new FileOutputStream(tmp)) {
                    o.write(sb.toString().getBytes("UTF-8"));
                }
                if (f.exists()) f.delete();
                tmp.renameTo(f);
            } catch (Exception ignored) {}
        });
    }

    /** One map → compact JSON object. Keys are server session ids
     *  (opaque ASCII), values booleans; quote through Json.quote anyway. */
    private static String toldJson(java.util.Map<String, Boolean> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Boolean> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.quote(e.getKey())).append(':')
              .append(Boolean.TRUE.equals(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /** P42: the spend ledger → JSON (sid → mid → cost). Caller holds LOCK. */
    private static String spendLedgerJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean firstSid = true;
        for (java.util.Map.Entry<String, java.util.LinkedHashMap<String, Double>> sid
                : spendLedger.entrySet()) {
            if (!firstSid) sb.append(',');
            firstSid = false;
            sb.append(Json.quote(sid.getKey())).append(":{");
            boolean firstMid = true;
            for (java.util.Map.Entry<String, Double> mid : sid.getValue().entrySet()) {
                if (!firstMid) sb.append(',');
                firstMid = false;
                sb.append(Json.quote(mid.getKey())).append(':').append(mid.getValue());
            }
            sb.append('}');
        }
        return sb.append('}').toString();
    }

    /** Boot: refill the three maps from the snapshot (missing file =
     *  fresh install, fine). Unknown shapes are ignored, never thrown. */
    private static void restoreTold() {
        File f = toldFile();
        if (f == null || !f.isFile()) return;
        try (FileInputStream fin = new FileInputStream(f)) {
            Map<String, Object> root = Json.obj(Json.parse(Api.readAll(fin)));
            if (root == null) return;
            restoreToldMap(root, "env", envTold);
            restoreToldMap(root, "render", renderTold);
            restoreToldMap(root, "style", styleTold);
            restoreSpendLedger(root);
        } catch (Exception ignored) {}
    }

    /** P42: refill the spend ledger from the snapshot — without it a
     *  process restart would book every replayed message again. */
    private static void restoreSpendLedger(Map<String, Object> root) {
        Map<String, Object> spend = Json.map(root, "spend");
        if (spend == null) return;
        synchronized (LOCK) {
            spendLedger.clear();
            for (Map.Entry<String, Object> sid : spend.entrySet()) {
                Map<String, Object> per = Json.obj(sid.getValue());
                if (per == null || per.isEmpty()) continue;
                java.util.LinkedHashMap<String, Double> into =
                        new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> mid : per.entrySet()) {
                    if (mid.getValue() instanceof Number)
                        into.put(mid.getKey(), ((Number) mid.getValue()).doubleValue());
                }
                if (!into.isEmpty()) spendLedger.put(sid.getKey(), into);
            }
            while (spendLedger.size() > 8) {
                String eldest = spendLedger.keySet().iterator().next();
                spendLedger.remove(eldest);
            }
        }
    }

    private static void restoreToldMap(Map<String, Object> root, String key,
                                       java.util.Map<String, Boolean> into) {
        Map<String, Object> m = Json.map(root, key);
        if (m == null) return;
        synchronized (into) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() instanceof Boolean)
                    into.put(e.getKey(), (Boolean) e.getValue());
            }
        }
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
            runs.clear();                       // P31: no phantom slots either
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

    /** P27: session-cumulative CACHED input tokens (prompt-cache hits the
     *  provider billed at the discounted rate). Bounded iteration — the
     *  bookkeeping map is capped at MSG_CAP entries; called only when the
     *  user opens the Σ popover. */
    public static long sessionCacheRead() {
        synchronized (LOCK) {
            long n = 0;
            for (MsgInfo mi : cur.msgs.values()) n += mi.cacheRead;
            return n;
        }
    }

    /** P25: the pill line — CURRENT context depth vs the model's window,
     *  plus the (unchanged) session cost. "48k / 200k · 24% · $0.0041".
     *  P27: while a run is active the depth reads the run's HIGH-WATER —
     *  per-message usage reports arrive unevenly mid-run (cache-read
     *  accounting lands on some turns, not others), so the raw last-turn
     *  number can jump down and up again; the window only ever fills, so
     *  the meter shows the peak until the run settles. */
    public static String ctxPillLine() {
        long last;
        synchronized (LOCK) {
            last = (busyFor(sessionId) && cur.depthPeak > cur.lastAssistantTok)
                    ? cur.depthPeak : cur.lastAssistantTok;
        }
        // P43: the pill paints the COMPACT meter — "211k · 39%" — not
        // the full "211k / 1.3M · 39%": on a narrow screen the window
        // half was ellipsized away and the cost tail ("…0.3897") read
        // like a fraction, which the field spent an evening debugging.
        // Depth + percent at a glance; the full form lives in the popover.
        String meter = Resilience.pillMeter(last, Models.resolveLimit(
                appCtx, Models.lastFetch(), selProvider(), selModel()));
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

    /** P29: context tokens the NEXT turn re-reads — the exact numerator
     *  the Σ pill uses (run high-water while busy, last turn otherwise).
     *  Read-only view for the cost-prediction hint; the pill itself is
     *  untouched. */
    public static long ctxTokens() {
        synchronized (LOCK) {
            return (busyFor(sessionId) && cur.depthPeak > cur.lastAssistantTok)
                    ? cur.depthPeak : cur.lastAssistantTok;
        }
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

    /** A transcript error card (the old err()) — model-owned. P45: an
     *  overflow error with auto-compact OFF gets the one honest line —
     *  the failure is the DESIGNED behavior of the kill switch, so the
     *  card says what to do next instead of reading like a bug. */
    public static void err(String title, String detail, String raw) {
        final Row r = new Row();
        r.kind = K_ERR;
        r.key = "err-" + System.nanoTime();
        r.ts = System.currentTimeMillis();
        r.text.append(title);
        if (detail != null && !detail.isEmpty()) r.output.append(detail);
        String probe = (title == null ? "" : title) + "\n"
                + (detail == null ? "" : detail) + "\n"
                + (raw == null ? "" : raw);
        if (probe.toLowerCase(java.util.Locale.ROOT).contains("overflow")) {
            if (r.output.length() > 0) r.output.append("\n----\n");
            r.output.append("this chat outgrew the model's window and "
                    + "auto-compact is off, so nothing was summarized — "
                    + "start a fresh chat (⌘ → New chat) or clear the "
                    + "window cap in the Σ popover");
        }
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
                    String psid0 = Json.str(part, "sessionID");
                    touchRun(psid0);              // P31: this run is alive NOW
                    String pt = Json.str(part, "type");
                    if (busy && ("text".equals(pt) || "reasoning".equals(pt)))
                        runHadOutput = true;
                    applyPart(part, null);
                    // P19 self-heal, generalized (P31): parts for a session
                    // we sent to recently — but which never cleanly idled —
                    // mean its run is alive even if we think otherwise.
                    // Re-arm THAT session's run (any session, not just one).
                    String psid = psid0;
                    if (psid != null && !busyFor(psid)
                            && RunBook.shouldRearm(sentAtMap(), idledAtMap(),
                                    psid, System.currentTimeMillis(),
                                    RunBook.REARM_WINDOW_MS)) {
                        claimRun(psid);
                        H.removeCallbacks(watchdog);
                        H.postDelayed(watchdog, 2000);
                        if (sessionId != null && !sessionId.equals(psid))
                            sys("a run is streaming in another chat — "
                                    + "Sessions → long-press → Stop ends it");
                    }
                }
            } else if ("message.part.delta".equals(type)) {
                // P47 — THE live token. Ground truth on opencode v1.18.25
                // (mock streaming provider + timestamped /event probe):
                // the feed publishes message.part.updated snapshots only
                // at part BOUNDARIES (created-empty → final), while the
                // actual per-provider-delta payload rides message.part
                // .delta frames {sessionID, messageID, partID, field:
                // "text", delta} in real time. The app consumed only the
                // snapshots, so the screen showed nothing while the model
                // spoke and the whole reply materialized at once — the
                // exact field report behind P43/P46. Deltas now append to
                // the row as they arrive; the boundary snapshots keep
                // reconciling authoritatively (mergeText growth rule).
                String dsid = Json.str(props, "sessionID");
                String dmid = Json.str(props, "messageID");
                String dpid = Json.str(props, "partID");
                String dfield = Json.str(props, "field");
                String ddelta = Json.str(props, "delta");
                if (dmid != null && dpid != null && "text".equals(dfield)
                        && ddelta != null && !ddelta.isEmpty()) {
                    touchRun(dsid);                 // P31: this run is alive NOW
                    if (busy) runHadOutput = true;  // P11: output happened
                    applyDelta(dsid, dmid, dpid, ddelta);
                }
            } else if ("message.updated".equals(type)) {
                Map<String, Object> minfo = Json.map(props, "info");
                if (minfo != null) {
                    String msid = Json.str(minfo, "sessionID");
                    if (msid != null) noteActivity(msid);   // P43 beat clock
                    applyMessageInfo(txnFor(msid),
                            minfo, true);          // P31: live SSE → spend counts
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
                if (sid != null) noteActivity(sid);   // P43: the run ended
                // P25→P31: a session's idle releases THAT session's run —
                // and only that one. Other sessions' runs are untouched.
                if (sid == null) {
                    // defensive: an idle without a session releases all
                    for (String s : new ArrayList<>(runs.keySet()))
                        releaseRun(s, true);
                } else if (busyFor(sid)) {
                    releaseRun(sid, true);        // the run truly ended
                }
            } else if ("session.error".equals(type)) {
                Map<String, Object> e = Json.map(props, "error");
                String m = e != null ? Json.str(e, "message") : null;
                if (m == null) m = Json.findErrorText(props, 0);
                String raw = String.valueOf(props);
                // P31: the erroring session (best effort — some payloads
                // carry no sid; then a lone run is unambiguous).
                String esid = Json.str(props, "sessionID");
                if (esid == null && runs.size() == 1)
                    esid = runs.keySet().iterator().next();
                if (isModelNotFound(raw + " " + m)) {
                    // P11 self-heal: run-time Model not found (the POST itself
                    // returned 200) — drop the stale pick so the NEXT send
                    // uses the server default instead of failing forever.
                    Models.clear(appCtx);
                    err("model no longer available", "the picked model was "
                            + "removed from the server's catalog — selection "
                            + "cleared, send again (⌘ → Model to choose another)", raw);
                    releaseRun(esid, false);
                } else if (busy && !runHadOutput && !flakeRetried
                        && lastUserText != null && isStreamFlake(raw + " " + m)
                        && esid != null) {
                    // P11: zen streams die with 504 idle-timeout on mobile
                    // networks; retry ONCE when nothing was rendered yet.
                    // P31: only the erroring session's run retries, and only
                    // when we can name it (with parallel runs a guess could
                    // replay someone else's message into the wrong chat).
                    flakeRetried = true;
                    sys("⚠ the model stream dropped (" + nz(m, "network")
                            + ") — retrying once…");
                    sendText(esid, lastUserText);
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
                    releaseRun(esid, false);
                }
            } else if (Questions.isAskedType(type)) {
                // P50 — the question tool / plan approval, ANSWERABLE. The
                // server blocks the run until the user replies through the
                // card; the store is keyed by session so ANY open chat can
                // answer its own ask. question.v2.* aliases carry the same
                // properties (verified against the shipped binary).
                String qsid = Json.str(props, "sessionID");
                if (qsid != null) {
                    synchronized (QUESTIONS) {
                        List<Map<String, Object>> l = QUESTIONS.get(qsid);
                        if (l == null) {
                            l = new ArrayList<>();
                            QUESTIONS.put(qsid, l);
                        }
                        String qid = Json.str(props, "id");
                        if (qid != null) {          // replace-in-place (P31 rule)
                            for (java.util.Iterator<Map<String, Object>> it = l.iterator();
                                    it.hasNext(); ) {
                                if (qid.equals(Json.str(it.next(), "id"))) {
                                    it.remove();
                                    break;
                                }
                            }
                        }
                        l.add(new HashMap<>(props));
                    }
                    ServerService.noteQuestionPending();
                }
                notifyQuestions();
            } else if (Questions.isGoneType(type)) {
                String qsid = Json.str(props, "sessionID");
                String qrid = Json.str(props, "requestID");
                if (qsid != null && qrid != null) {
                    synchronized (QUESTIONS) {
                        List<Map<String, Object>> l = QUESTIONS.get(qsid);
                        if (l != null) {
                            for (java.util.Iterator<Map<String, Object>> it = l.iterator();
                                    it.hasNext(); ) {
                                if (qrid.equals(Json.str(it.next(), "id"))) {
                                    it.remove();
                                    break;
                                }
                            }
                            if (l.isEmpty()) QUESTIONS.remove(qsid);
                        }
                    }
                    ServerService.noteQuestionAnswered();
                }
                notifyQuestions();
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

    // ------------------------------------------------ P40 poisoned root

    /** Sessions diagnosed with an empty compaction root (the model sees
     *  only the latest message). The Context repair note rides sends for
     *  these until the repair lands — the one-send bridge, never a tax. */
    private static final java.util.Set<String> poisonKnown = new java.util.HashSet<>();
    /** Sessions with a repair in flight (server stop → store edit →
     *  restart → verify). Sends for these are politely held. */
    private static final java.util.Set<String> poisonCuring = new java.util.HashSet<>();
    /** P44: sessions with a pending post-compaction anchor. Armed when
     *  a compaction summary is FIRST seen for the session (live SSE or
     *  replay); consumed only by that session's next REAL user send —
     *  never by cache beats (they post their own block directly) and
     *  never on a failed POST (the retry path rides it again). */
    private static final java.util.HashSet<String> compactAnchorPending =
            new java.util.HashSet<>();

    /** P41: compaction-summary message ids already announced in the
     *  chat. The summarize row would otherwise paint as an ordinary
     *  assistant bubble while it actually rewrote the model's memory —
     *  the field report was exactly that silence. Per-message dedupe;
     *  bounded by the same session lifetime as poisonKnown. */
    private static final java.util.Set<String> compactionNoted = new java.util.HashSet<>();
    /** sid → victim message ids the repair will delete. */
    private static final Map<String, List<String>> poisonVictims = new HashMap<>();

    /** P40: diagnose a session from the raw message array the hub just
     *  pulled (loadSession / reconcileOnBind / post-compact check). When
     *  the model's context starts at an EMPTY compaction root while real
     *  turns exist, the session is flagged, the honest line lands once,
     *  and the repair runs at the first idle moment. Never throws. */
    static void diagnosePoison(final String sid, final List<Object> raw) {
        if (sid == null || raw == null || raw.isEmpty()) return;
        diagnosePoisonMsgs(sid, CompactionPoison.parse(raw));
    }

    /** P51: the same diagnosis over the streamed Msg list — the walk
     *  builds it message-by-message, so no whole-store parse exists
     *  anywhere on the replay path anymore. Never throws. */
    static void diagnosePoisonMsgs(final String sid,
                                   final List<CompactionPoison.Msg> msgs) {
        if (sid == null || msgs == null || msgs.isEmpty()) return;
        try {
            if (!CompactionPoison.poisoned(msgs)) return;
            final List<String> victims = CompactionPoison.victims(msgs);
            if (victims.isEmpty()) return;
            boolean first;
            synchronized (LOCK) {
                first = poisonKnown.add(sid);
                poisonVictims.put(sid, victims);
            }
            if (first) sys(CompactionPoison.note());
            if (busyFor(sid)) return;   // never stop the server under a run
            curePoison(sid);
        } catch (Throwable e) {
            Trail.record(appCtx, "hub poison diagnose", e);
        }
    }

    /** P40: the cure, at the source. Stops the server, removes the
     *  poisoned root rows from the store (the app owns both), restarts,
     *  verifies through the same API, and repaints. On any failure the
     *  honest line lands and the Context repair note keeps riding. */
    private static void curePoison(final String sid) {
        final List<String> victims;
        synchronized (LOCK) {
            if (poisonCuring.contains(sid)) return;
            victims = poisonVictims.get(sid);
            if (victims == null || victims.isEmpty()) return;
            poisonCuring.add(sid);
        }
        IO.execute(() -> {
            String fail = null;
            try {
                ServerService.stopForRepair(appCtx);
                if (!awaitCondition("await stop", 15_000,
                        () -> !ServerService.healthy()
                                && ServerService.getState() != ServerService.ST_STARTING))
                    fail = "the server did not stop";
                if (fail == null && !runStoreSurgery(victims))
                    fail = "the store edit failed";
                if (fail == null) ServerService.restart(appCtx);
                if (fail == null && !awaitCondition("await healthy", 60_000,
                        ServerService::healthy))
                    fail = "the server did not come back";
                if (fail == null && !verifyCured(sid))
                    fail = "the after-repair check";
                if (fail == null) {
                    synchronized (LOCK) {
                        poisonKnown.remove(sid);
                        poisonVictims.remove(sid);
                    }
                    sys(CompactionPoison.curedNote());
                    reconcileOnBind();       // repaint from the healed store
                } else {
                    sys(CompactionPoison.failedNote(fail));
                }
            } catch (Throwable e) {
                Trail.record(appCtx, "hub poison cure", e);
                sys(CompactionPoison.failedNote("internal error"));
            } finally {
                synchronized (LOCK) { poisonCuring.remove(sid); }
            }
        });
    }

    /** Bounded wait on a condition (the repair's own clock — 250 ms
     *  steps, capped). Named thread, no leaks, no indefinite hangs. */
    private static boolean awaitCondition(String what, long capMs,
                                          java.util.concurrent.Callable<Boolean> cond) {
        long deadline = System.currentTimeMillis() + capMs;
        while (System.currentTimeMillis() < deadline) {
            try { if (Boolean.TRUE.equals(cond.call())) return true; }
            catch (Exception ignored) {}
            try { Thread.sleep(250); }
            catch (InterruptedException ie) { return false; }
        }
        Trail.record(appCtx, "hub repair " + what, new Exception("timeout"));
        return false;
    }

    /** The store file the server owns: files/home/.local/share/opencode/
     *  opencode.db (the SQLite store since the JSON-layout era ended). */
    private static java.io.File storeDbFile(Context c) {
        return new File(new File(Binaries.homeDir(c), ".local/share/opencode"),
                "opencode.db");
    }

    /** Execute the planned deletes against the store. The server is
     *  stopped; ids are bound, never spliced into SQL. */
    private static boolean runStoreSurgery(List<String> ids) {
        if (ids == null || ids.isEmpty()) return true;
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            java.io.File f = storeDbFile(appCtx);
            if (!f.exists()) return false;
            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    f.getPath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
            int step = CompactionPoison.SQL_BATCH;
            for (int from = 0; from < ids.size(); from += step) {
                int to = Math.min(ids.size(), from + step);
                db.execSQL(CompactionPoison.sqlFor("part", "message_id", to - from),
                        ids.subList(from, to).toArray());
            }
            for (int from = 0; from < ids.size(); from += step) {
                int to = Math.min(ids.size(), from + step);
                db.execSQL(CompactionPoison.sqlFor("message", "id", to - from),
                        ids.subList(from, to).toArray());
            }
            return true;
        } catch (Throwable e) {
            Trail.record(appCtx, "hub store surgery", e);
            return false;
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    /** Confirm through the API the model will actually read: no empty
     *  root left, poison gone. A few retries ride out the restart. */
    private static boolean verifyCured(String sid) {
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = Api.open("GET", "/session/" + sid + "/message", null, 15_000);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    // P51: streamed Msg collection — the after-repair check
                    // must not reintroduce the whole-store parse it just cured
                    Reader in = new BufferedReader(new InputStreamReader(
                            conn.getInputStream(), StandardCharsets.UTF_8), 64 * 1024);
                    final List<CompactionPoison.Msg> msgs = new ArrayList<>();
                    StoreWalk.walk(in, 0, 0, new StoreWalk.Sink() {
                        @Override public boolean abort() { return false; }
                        @Override public void message(Map<String, Object> item,
                                                      int index) {
                            CompactionPoison.collectMsg(item, msgs);
                        }
                        @Override public void renderEnd(
                                List<Map<String, Object>> items,
                                StoreWalk.Stats st) { /* check only */ }
                    });
                    return !CompactionPoison.poisoned(msgs)
                            && CompactionPoison.victims(msgs).isEmpty();
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            try { Thread.sleep(1500); }
            catch (InterruptedException ie) { return false; }
        }
        return false;
    }

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
            touchRun(sid);                      // P31: liveness per run
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
                    noteHtmlWrite(t, part);        // P35: arm the render note
                    break;
                case "patch":
                    upsertTool(t, patchRow(key, part));
                    noteHtmlPatch(t, part);        // P35: .html inside a patch too
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

    /** P47 — append one live delta to its part's row. Keyed exactly like
     *  applyPart keys parts (messageID|partID) so the boundary snapshots
     *  and the delta stream meet in the same row:
     *
     *    - row exists (the normal case: part.updated created it empty
     *      right before the first delta) → append. Text rows only —
     *      tool/file rows are snapshot-owned and never delta-fed.
     *    - row missing (an SSE reconnect swallowed the created-empty
     *      frame) → create an assistant text row on demand. Deltas only
     *      originate from assistant generation, so this can never conjure
     *      a user line; the eventual part.updated snapshot reconciles
     *      type and full text anyway (mergeText adopts its growth).
     *
     *  Append is the honest primitive here: each delta IS the increment,
     *  so re-merge machinery would only risk dropping content. Called
     *  from onEvent (main) or hopped to main like the upserts. */
    static void applyDelta(String sid, String mid, String pid, String delta) {
        // entry-point guards: the onEvent branch pre-filters, but this is
        // a package API — every direct caller gets the same safety
        if (mid == null || pid == null || delta == null || delta.isEmpty())
            return;
        try {
            final Tx t = txnFor(sid);
            final String key = mid + "|" + pid;
            if ("user".equals(roleOf(t, mid))) return;   // user parts never stream
            main(() -> {
                if (deltaAppendTx(t, key, mid, delta)) afterUpsert(t, key);
            });
        } catch (Exception e) {
            // a malformed delta must never take the hub down
        } catch (Throwable e) {
            Trail.record(appCtx, "hub delta", e);
        }
    }

    /** The pure delta state machine — same LOCK, same Tx the upserts
     *  mutate; returns true when the row changed (caller notifies).
     *  JVM-testable without the main-thread hop (P47Test drives the full
     *  snapshot→deltas→snapshot turn through it directly). */
    static boolean deltaAppendTx(Tx t, String key, String mid, String delta) {
        synchronized (LOCK) {
            Row r = rowIn(t, key);
            if (r == null) {
                r = new Row();
                r.kind = K_ASSISTANT;
                r.key = key;
                r.ts = System.currentTimeMillis();
                MsgInfo mi = t.msgs.get(mid);
                r.meta = mi == null ? null : mi.meta;
                r.text.append(delta);
                t.rows.add(r);
                t.idxByKey.put(key, t.rows.size() - 1);
                t.rowsAdded++;
                return true;
            }
            if (r.kind == K_ASSISTANT || r.kind == K_REASON) {
                // P51: the delta lane respects the same wall as the
                // snapshot lane — a monster answer stops growing its
                // render copy past TEXT_CAP (the stream itself is fine)
                if (r.text.length() < TEXT_CAP) {
                    r.text.append(delta);
                    if (r.text.length() > TEXT_CAP) {
                        int extra = r.text.length() - TEXT_CAP;
                        r.text.setLength(TEXT_CAP);
                        r.text.append("\n…[+").append(extra)
                                .append(" chars truncated — the full text is "
                                + "still in the model's context]");
                    }
                    return true;
                }
                return false;             // capped: nothing more to paint
            }
            return false;                 // snapshot-owned row: ignore
        }
    }

    /** Route a part/message to its session's transcript. The DISPLAYED
     *  session uses cur; anything else streams into (or creates) its
     *  archived Tx — visible the moment the user opens that session. */
    private static Tx txnFor(String sid) {
        if (sid == null || sid.equals(sessionId)) {
            if (cur.sid == null) cur.sid = sid;
            return cur;
        }
        synchronized (LOCK) {
            Tx t = archive.get(sid);
            if (t == null) {
                t = new Tx();
                t.sid = sid;
                archive.put(sid, t);
            }
            evictArchive(sid);
            return t;
        }
    }

    /** Shrink the archive, never evicting the queried session or any
     *  session whose run is still streaming (P31: parallel runs — the
     *  archive must not drop a background chat that is mid-run). */
    private static void evictArchive(String protect) {
        while (archive.size() > ARCHIVE_CAP) {
            String victim = null;
            for (String k : archive.keySet()) {
                if (!k.equals(protect) && !runs.containsKey(k)) {
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
        // P38: the bubble shows what the user SAID — the app-injected
        // system-reminder notes on the wire (env map, render check, terse
        // mode) are for the model, never for the human (the P37 field
        // report: the whole block buried the message). Display-only: the
        // server still stores and echoes the full wire text; replay paints
        // through here too, so old sessions clean up as well.
        // P51: the render copy respects the row-text wall like every
        // other lane — a pasted 5 MB log cannot eat the heap alone.
        final String shown = capText(NoteStrip.display(text));
        main(() -> {
            synchronized (LOCK) {
                Row r = rowIn(t, key);
                if (r == null) {
                    r = new Row();
                    r.kind = K_USER;
                    r.key = key;
                    r.ts = System.currentTimeMillis();
                    r.text.append(shown);
                    t.rows.add(r);
                    t.idxByKey.put(key, t.rows.size() - 1);
                    t.rowsAdded++;
                } else {
                    if (!shown.contentEquals(r.text)) {
                        r.text.setLength(0);
                        r.text.append(shown);
                    } else return;
                }
            }
            afterUpsert(t, key);
        });
    }

    static void upsertText(final Tx t, final String key, final String mid, final String text) {
        main(() -> {
            boolean dsml = false;
            synchronized (LOCK) {
                Row r = rowIn(t, key);
                // P37: a text part holding only whitespace is NOT a message —
                // tool-only assistant rounds used to materialize as an
                // invisible padded box + a floating token footer (the
                // empty-item / weird-gap field reports). No content, no row;
                // the moment real text arrives this same key creates it.
                if (r == null && blankText(text)) return;
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
                // P37: leaky-model one-shot. A model printing raw DSML
                // tool-call markup as chat text means its endpoint is not
                // honoring tool calls — say so once, in the visible chat
                // only (background transcripts never surface notes).
                if (t == cur && !t.dsmlNoted && r.text.length() > 0
                        && Dsml.leaky(r.text.toString())) {
                    t.dsmlNoted = true;
                    dsml = true;
                }
            }
            if (dsml) sys(Dsml.note());
            afterUpsert(t, key);
        });
    }

    /** P37: whitespace-only text = no content (see upsertText). */
    static boolean blankText(CharSequence s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++)
            if (!Character.isWhitespace(s.charAt(i))) return false;
        return true;
    }

    /** P37 — some provider endpoints answer tool prompts by printing
     *  their raw tool-call markup (DSML) as plain text. The tool actions
     *  inside that text never ran; the honest app says so once per
     *  session instead of letting the junk sit unexplained. */
    static final class Dsml {
        static boolean leaky(String s) {
            return s != null && (s.contains("<|DSML|") || s.contains("</|DSML|"));
        }
        static String note() {
            return "⚠ this model printed raw tool-call markup (DSML) as chat "
                    + "text — those tool actions did NOT run. Its endpoint is "
                    + "not honoring tool calls; try another model (⌘ → Model)";
        }
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
    /** P51: the biggest text ONE row may hold, in chars. A capped row
     *  keeps its head plus an honest marker; the model's own context
     *  (server store) always carries the full text — this wall only
     *  bounds the RENDER copy, so a single multi-megabyte part can never
     *  eat the heap on its own. */
    static final int TEXT_CAP = 131_072;

    /** P51: apply the row-text wall. Pure; JVM-pinned. */
    static String capText(String s) {
        if (s == null || s.length() <= TEXT_CAP) return s;
        return s.substring(0, TEXT_CAP)
                + "\n…[+" + (s.length() - TEXT_CAP) + " chars truncated — "
                + "the full text is still in the model's context]";
    }

    static String mergeText(String curText, String next) {
        if (curText == null || curText.isEmpty()) return capText(next);
        if (next == null) return curText;
        if (next.equals(curText)) return curText;
        if (next.startsWith(curText)) return capText(next);   // grew
        if (curText.startsWith(next)) return curText; // truncated echo
        return capText(next);                                  // changed → replace
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
        if (out != null && !out.isEmpty()) r.output.append(capText(out));
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

    /** P35: the agent wrote/edited an .html page in THIS session — arm
     *  the render note so the next send teaches the verification loop
     *  (needsNote FALSE = armed; styleMark-style TRUE on delivery). */
    private static void noteHtmlWrite(Tx t, Map<String, Object> part) {
        try {
            if (t == null) return;
            String tool = Json.str(part, "tool");
            if (tool == null) return;
            Map<String, Object> state = Json.map(part, "state");
            if (state == null) state = part;
            Map<String, Object> in = Json.map(state, "input");
            if (in == null) return;
            String abs = resolveAgainstRoot(
                    firstStr(in, "path", "filePath", "file"));
            if (abs != null && CanvasDoc.isRenderable(abs)) {
                synchronized (renderTold) { renderTold.put(t.sid, Boolean.FALSE); }
                persistTold();
            }
        } catch (Exception ignored) {
            // a malformed tool part must never take the feed down
        }
    }

    /** P35: patch parts list whole files — arm on any .html among them. */
    private static void noteHtmlPatch(Tx t, Map<String, Object> part) {
        try {
            if (t == null) return;
            List<Object> files = Json.list(part, "files");
            if (files == null) return;
            for (Object f : files) {
                String abs = resolveAgainstRoot(
                        f == null ? null : String.valueOf(f));
                if (abs != null && CanvasDoc.isRenderable(abs)) {
                    synchronized (renderTold) { renderTold.put(t.sid, Boolean.FALSE); }
                    persistTold();
                    return;
                }
            }
        } catch (Exception ignored) {
            // a malformed tool part must never take the feed down
        }
    }

    /** Absolute path for a (possibly sandbox-relative) tool path. */
    private static String resolveAgainstRoot(String path) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (f.isAbsolute()) return f.getAbsolutePath();
        return editRoot == null ? null : new File(editRoot, path).getAbsolutePath();
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
     *  message, meta propagation onto its assistant row, error surfacing.
     *  live=true only for LIVE SSE events — replays (session open, resume
     *  re-pull, POST reconcile) pass false so the all-time credit counter
     *  never double-counts a cost it already recorded. */
    static void applyMessageInfo(final Tx t, Map<String, Object> info) {
        applyMessageInfo(t, info, false);
    }

    static void applyMessageInfo(final Tx t, Map<String, Object> info, boolean live) {
        applyMessageInfo(t, info, live, true);
    }

    /** P42: {@code repaint=false} runs the accounting ONLY — the replay
     *  passes walk the FULL store for honest sums and must not fire row
     *  repaints or error cards for turns whose rows were trimmed long
     *  ago. Live events and the rendering window pass repaint=true. */
    static void applyMessageInfo(final Tx t, Map<String, Object> info,
                                 boolean live, boolean repaint) {
        if (info == null) return;
        String mid = Json.str(info, "id");
        if (mid == null) return;
        String role = Json.str(info, "role");
        boolean becameSummary = false;   // P41: set live, inside the lock
        boolean summaryFirstSight = false;   // P44: live OR replay, in the lock
        synchronized (LOCK) {
            MsgInfo mi = t.msgs.get(mid);
            if (mi == null) {
                mi = new MsgInfo();
                t.msgs.put(mid, mi);
            }
            if (role != null) mi.role = role;
            // P39: sticky — a summary flag arriving on any update marks the
            // message for the detector, live SSE and replay alike.
            boolean wasSummary = mi.summary;
            if (Boolean.TRUE.equals(info.get("summary"))) mi.summary = true;
            // P40: sticky agent — "compaction" marks the root row the
            // model's context starts from. Replayed GET infos carry it
            // where live summaries only carried the summary flag, so the
            // detector now sees compactions in BOTH paths.
            String agent = Json.str(info, "agent");
            if (agent != null && !agent.isEmpty()) mi.agent = agent;
            if ("compaction".equals(mi.agent)) mi.summary = true;
            // P41: a compaction summary landing LIVE means the model's
            // memory was just rewritten — the transcript kept painting
            // it as an ordinary message and the user had no way to
            // know. One honest line, once per summary message, only for
            // the chat the user is actually looking at (background
            // sessions show the summary row when reopened).
            becameSummary = live && "assistant".equals(role) && t == cur
                    && !wasSummary && mi.summary;
            // P44: the summary's FIRST arrival in this store — live or
            // replayed — arms the post-compaction anchor for this
            // session, so its next real send re-rides the thread.
            summaryFirstSight = !wasSummary && mi.summary;
        }
        final boolean fCompactionLanded = becameSummary
                && compactionNoted.add(mid);
        Map<String, Object> tk = Json.map(info, "tokens");
        long total = 0;
        long cacheRead = 0;
        if (tk != null) {
            Map<String, Object> cache = Json.map(tk, "cache");
            if (cache != null)
                cacheRead = (long) num(cache.get("read"));
            Object serverTotal = tk.get("total");
            if (serverTotal instanceof Number) {
                // P42: the server's own total is the truth (fixture-pinned
                // as the sum of the parts today; drift-proof tomorrow).
                total = (long) ((Number) serverTotal).doubleValue();
            } else {
                total += num(tk.get("input")) + num(tk.get("output"))
                        + num(tk.get("reasoning"));
                if (cache != null)
                    total += cacheRead + num(cache.get("write"));
            }
        }
        final long fCacheRead = cacheRead;
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
        if ("assistant".equals(role)) {
            synchronized (LOCK) {
                if (becameSummary) t.awaitDepthReset = true;   // P42-check: may land tokenless
                if (t.awaitDepthReset && hasSpend) {
                    // P42: the summary message re-bills the whole payload,
                    // so its own token count is NOT the model's new depth
                    // — keeping it as the high-water painted 95% on a
                    // freshly-shrunken window. Reset: the next real turn
                    // reports the honest post-compaction depth. P42-check:
                    // keyed to the first token-bearing update AFTER the
                    // summary, not to the note event itself.
                    t.lastAssistantTok = 0;
                    t.depthPeak = 0;
                    t.awaitDepthReset = false;
                } else if (hasSpend) {
                    t.lastAssistantTok = fTok;   // P25 depth input
                    if (fTok > t.depthPeak) t.depthPeak = fTok;   // P27 high-water
                }
            }
        }
        // P31: the all-time credit counter moves by the same delta logic,
        // but ONLY on live events — a replayed message (fresh MsgInfo at
        // cost 0) would otherwise book its full cost again on every open.
        // The delta itself is taken inside the main lambda where mi.cost
        // is actually updated (single writer, no race).
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
        final boolean fLive = live;
        // P41: say it once, in the open chat, the moment the memory
        // rewrite lands — before the next send can be answered from the
        // shrunken context while the user wonders why it forgot.
        if (fCompactionLanded) sys(CompactionPolicy.summaryNote(userContextCap()));
        // P44: arm the anchor on first sight of a summary — live SSE or
        // a replay discovering it on reopen. The visible note above stays
        // live-and-open only (P41); the anchor must survive reopens,
        // because the amnesia it cures is exactly what a reopened,
        // compacted session resumes with.
        if (summaryFirstSight && t.sid != null) {
            synchronized (compactAnchorPending) {
                compactAnchorPending.add(t.sid);
            }
        }
        // P42: the accounting moved INLINE (was inside the main lambda) —
        // the replay sums pass must book every message even when it skips
        // repainting. Only the row repaint stays on the main thread.
        boolean showError = false;
        synchronized (LOCK) {
            MsgInfo mi = t.msgs.get(fMid);
            if (mi == null) { mi = new MsgInfo(); t.msgs.put(fMid, mi); }
            if (fMeta != null) mi.meta = fMeta;
            if (hasSpend) {
                // P26: move the totals by DELTA — safe for re-reported
                // (cumulative or corrected) values, and the eviction
                // below can never lose a cent/token already counted.
                double prevCost = mi.cost;
                long prevTok = mi.tok;
                double[] evicted = t.evictedSums.remove(fMid);
                if (evicted != null) {
                    // P42-check: recreated after eviction — its money is
                    // already inside the sums; delta from the evicted
                    // values, never from zero (zero re-booked the evicted
                    // tail into the session Σ on every bind).
                    prevCost = evicted[0];
                    prevTok = (long) evicted[1];
                }
                t.costSum += fCost - prevCost;
                t.tokSum += fTok - prevTok;
                // P42: all-time booking rides the persisted ledger —
                // every message books exactly once, live or replayed.
                // (The old live-only gate dropped money billed while the
                // app was dead: background runs never entered the
                // credit counter, so the cap could be exceeded in fact.)
                // P42-check: assistant-only, like the depth input — a
                // future info shape stamping tokens on a user row must
                // not touch the credit counter. The flush rides the same
                // gate: disk ledger keeps pace with the pref or a process
                // death re-books the difference.
                if ("assistant".equals(role)) {
                    double dBooked = spendLedgerBook(t.sid, fMid, fCost, fLive);
                    if (dBooked != 0) { spendDelta(dBooked); persistToldSoon(); }
                }
                mi.cost = fCost;
                mi.tok = fTok;
                mi.cacheRead = fCacheRead;   // P27: Σ popover cache line
                // refresh recency: the freshest messages survive the cap
                t.msgs.remove(fMid);
                t.msgs.put(fMid, mi);
                while (t.msgs.size() > MSG_CAP) {
                    String eldest = t.msgs.keySet().iterator().next();
                    MsgInfo ev = t.msgs.remove(eldest);
                    // P42-check: remember what the sums already hold —
                    // recreated rows delta against these, not zero.
                    if (ev != null && (ev.cost != 0 || ev.tok != 0))
                        t.evictedSums.put(eldest, new double[]{ev.cost, ev.tok});
                }
            }
            // (P37 note: the card view already draws its own ✕.)
            // P42: only a repainting pass may fire the error card — the
            // sums pass must not claim errors for turns whose rows were
            // trimmed long ago, and must leave errorShown false so the
            // rendering window can still show it once.
            showError = repaint && !mi.errorShown && fErrName != null;
            if (showError) mi.errorShown = true;
        }
        if (repaint && fMeta != null) {
            main(() -> {
                synchronized (LOCK) {
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
            });
        }
        if (hasSpend) notifySpend();
        if (showError) {
            err(fErrName, fErrMsg == null ? "" : fErrMsg,
                    String.valueOf(info));
            releaseRun(t.sid, false);
        }
    }

    // ------------------------------------------------------- send path

    /** The send button. Full orchestration, hub-owned: the POST holds an
     *  IO thread for the whole run, so the chat screen can close freely
     *  mid-turn. The run is never aborted from here — only the user's
     *  stop button (abort) does that.
     *  P31: parallel runs — the guard is per-SESSION (a busy OTHER chat
     *  no longer blocks this one, up to RunBook.MAX_PARALLEL), and the
     *  credit limit is enforced before anything is sent. */
    public static void send(String q) {
        final String text = q == null ? "" : q.trim();
        if (text.isEmpty()) return;
        if (creditBlocked()) {
            sys(CreditLimit.blockLine(spendTotal(), spendCap()));
            return;
        }
        synchronized (LOCK) {
            if (poisonCuring.contains(sessionId)) {
                sys("context repair in progress — one moment, the chat "
                        + "continues right after it");
                return;
            }
        }
        lastUserText = text;
        runHadOutput = false;
        IO.execute(() -> {
            String sid = ensureSession();
            if (sid == null) {
                sys("server not healthy yet — try again in a moment "
                        + "(⌘ → Restart server if it persists)");
                return;
            }
            noteActivity(sid);                    // P43: a send refreshes the cache
            int claim = claimRun(sid);
            if (claim != RunBook.CLAIM_OK) {
                sys(claim == RunBook.CLAIM_BUSY
                        ? "this chat is still running — tap ■ to stop it first"
                        : "parallel run limit (" + RunBook.MAX_PARALLEL
                          + ") reached — let one finish or stop it");
                return;
            }
            try {
                validateSelectedModel();          // P11: self-heal stale picks
                // P30: the terse preference rides THIS message as a
                // <system-reminder> prefix when the session hasn't been
                // told the current state — the mid-session toggle flip
                // takes effect on the very next send (P29 waited for a
                // new session; the field called it broken). One pref
                // read per send, captured ONCE so wrap and mark agree.
                final boolean tp = tersePref();
                final Boolean told;
                synchronized (styleTold) { told = styleTold.get(sid); }
                // P35: after the agent wrote an .html page in THIS session,
                // the next send carries the render-check note once — the
                // verification loop taught at the moment it matters. A
                // down endpoint sends no note (never teach a door that
                // does not open).
                final Boolean rTold;
                synchronized (renderTold) { rTold = renderTold.get(sid); }
                final String rNote = RenderCheck.needsNote(rTold)
                        ? RenderCheck.note(RenderServer.port(),
                                           RenderServer.token())
                        : null;
                // P37: the environment map rides the FIRST message of the
                // session — Debian guest vs host tools, and the honest
                // GitHub-token answer (present + what it is for, or absent
                // + where the user adds one). No more blind git/gh probing.
                final Boolean eTold;
                synchronized (envTold) { eTold = envTold.get(sid); }
                // P42: mode-aware note — the field run proved a static
                // Debian claim teaches a native-shell agent to probe a
                // guest that does not exist. note(Context) describes the
                // mode the shell actually lands in today.
                final String eNote = EnvNote.needsNote(eTold)
                        ? EnvNote.note(appCtx) : null;
                String wire = TerseMode.wrap(text, tp, told);
                if (eNote != null) wire = eNote + "\n\n" + wire;
                if (rNote != null) wire = rNote + "\n\n" + wire;
                // P39: context repair rides FIRST (outermost block) when the
                // flat-totals signature says the turns are being answered
                // without history. The model reads the thread again; the
                // user's bubble stays clean (NoteStrip knows the signature).
                final String recap = contextRepair(sid);
                if (recap != null) wire = recap + "\n\n" + wire;
                // P44: the post-compaction anchor — the first real send
                // after a summary lands re-rides the thread recap and the
                // sandbox ground rules the summary just thinned away.
                final boolean anchorArmed = anchorPending(sid);
                final String anchor = anchorArmed
                        ? compactionAnchorBlock(sid, text) : null;
                if (anchor != null) wire = anchor + "\n\n" + wire;
                // P43: the greeting-context guard — a bare "hello world"
                // mid-task used to get a fresh-chat "Hello! What would you
                // like help with?" back (the history REACHED the model; it
                // just read the short line as a new-conversation opener).
                // The recap names the thread so the answer stays in it.
                else if (GreetGuard.isBareGreeting(text)) {
                    String greet = threadContextBlock(sid, text);
                    if (greet != null) wire = greet + "\n\n" + wire;
                }
                List<String> bodies = buildBodies(
                        wire, Models.selected(appCtx), agent);
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
                    releaseRun(sid, false);
                    return;
                }
                if (modelDropped)
                    sys("note: the server ignored the picked model for this "
                            + "message — it answered with its default model");
                styleMark(sid, tp);              // P30: this session is in sync now
                if (anchorArmed) anchorConsume(sid);   // P44: delivered — once
                if (rNote != null) {             // P35: note delivered — once
                    synchronized (renderTold) { renderTold.put(sid, Boolean.TRUE); }
                    persistTold();
                }
                if (eNote != null) {             // P37: env map delivered — once
                    synchronized (envTold) { envTold.put(sid, Boolean.TRUE); }
                    persistTold();
                }
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
                    releaseRun(sid, false);
                } else {
                    Trail.record(appCtx, "hub send", e);
                    sys("send failed · " + Resilience.prettyNetError(e));
                    releaseRun(sid, false);
                }
            }
        });
    }

    // ------------------------------------------------ P39 context repair

    /** The session's assistant messages, chronological (opencode message
     *  ids are monotonic, so id order is time order — the msgs map itself
     *  is recency-ordered by UPDATE, unusable for sequencing). Pure view
     *  for the amnesia detector. */
    static List<AmnesiaGuard.Obs> assistantObs(final Tx t) {
        ArrayList<String[]> rows = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, MsgInfo> e : t.msgs.entrySet()) {
                MsgInfo mi = e.getValue();
                if (mi == null || !"assistant".equals(mi.role)) continue;
                rows.add(new String[]{e.getKey(), String.valueOf(mi.tok),
                        mi.summary ? "1" : "0"});
            }
        }
        rows.sort((a, b) -> String.valueOf(a[0]).compareTo(String.valueOf(b[0])));
        List<AmnesiaGuard.Obs> out = new ArrayList<>(rows.size());
        for (String[] r : rows)
            out.add(new AmnesiaGuard.Obs(Long.parseLong(r[1]), "1".equals(r[2])));
        return out;
    }

    /** The recent thread as digest lines, newest-collected first then
     *  reversed — tail-capped so one huge paste cannot eat the budget.
     *  User rows are already display-form (P38: upsertUser paints the
     *  stripped text), assistant rows are the visible bodies. */
    static String threadDigest(final Tx t) {
        ArrayList<String> lines = new ArrayList<>();
        int budget = AmnesiaGuard.RECAP_MAX_CHARS + 4096;
        int used = 0;
        synchronized (LOCK) {
            for (int i = t.rows.size() - 1; i >= 0 && used < budget; i--) {
                Row r = t.rows.get(i);
                if (r == null || r.text == null || r.text.length() == 0) continue;
                String kind;
                if (r.kind == K_USER) kind = "user";
                else if (r.kind == K_ASSISTANT) kind = "assistant";
                else continue;
                String line = AmnesiaGuard.digestLine(kind, r.text.toString());
                if (line == null) continue;
                lines.add(line);
                used += line.length();
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--)
            b.append(lines.get(i)).append('\n');
        return b.toString();
    }

    /** The recap block when this session's flat totals say the model is
     *  being answered without its history; null when healthy. NEVER
     *  throws — a detector hiccup must never block a send.
     *  P40: a diagnosed poisoned root rides the recap too — the bridge
     *  between diagnosis and the repair landing (one send, not a tax). */
    static String contextRepair(String sid) {
        try {
            if (sid == null) return null;
            final Tx t;
            synchronized (LOCK) { t = cur; }
            if (t == null || !sid.equals(t.sid)) return null;
            synchronized (LOCK) {
                if (poisonKnown.contains(sid))
                    return AmnesiaGuard.recapBlock(threadDigest(t));
            }
            if (!AmnesiaGuard.confirmed(assistantObs(t))) return null;
            return AmnesiaGuard.recapBlock(threadDigest(t));
        } catch (Throwable e) {
            return null;
        }
    }

    /** P43: the session-context block for bare-greeting sends, built
     *  from the REAL thread — the opening request, the previous real
     *  user message, the last assistant reply. Null when the thread is
     *  too young to anchor (a fresh chat greeting must stay a normal
     *  greeting) or the current send is the only user turn. {@code
     *  skipText} excludes a trailing user row that IS this send (the
     *  retry path replays after the echo already painted the row).
     *  Never throws. */
    static String threadContextBlock(String sid, String skipText) {
        try {
            if (sid == null) return null;
            final Tx t;
            synchronized (LOCK) { t = cur; }
            if (t == null || !sid.equals(t.sid)) return null;
            return threadContextBlockTx(t, skipText);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The pure core of the greeting guard — a scan over one transcript
     *  (package-private so the JVM suite pins it without hub state). */
    static String threadContextBlockTx(Tx t, String skipText) {
        try {
            if (t == null) return null;
            String firstUser = null, lastUser = null, lastAssistant = null;
            int turns = 0;
            String skip = skipText == null ? "" : skipText.trim();
            synchronized (LOCK) {
                for (Row r : t.rows) {
                    if (r == null || r.text.length() == 0) continue;
                    if (r.kind == K_USER) {
                        String shown = r.text.toString();
                        if (skip.isEmpty() || !shown.trim().equals(skip)) {
                            if (firstUser == null) firstUser = shown;
                            lastUser = shown;
                        }
                        turns++;
                    } else if (r.kind == K_ASSISTANT) {
                        lastAssistant = r.text.toString();
                        turns++;
                    }
                }
            }
            if (turns < 6 || firstUser == null) return null;
            return GreetGuard.recapBlock(turns, firstUser, lastUser,
                    lastAssistant);
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------- P44 post-compaction anchor

    /** P44: true when {@code sid} has a pending post-compaction anchor. */
    static boolean anchorPending(String sid) {
        if (sid == null) return false;
        synchronized (compactAnchorPending) {
            return compactAnchorPending.contains(sid);
        }
    }

    /** P44: the anchor was delivered on a successful POST — clear it. */
    static void anchorConsume(String sid) {
        if (sid == null) return;
        synchronized (compactAnchorPending) {
            compactAnchorPending.remove(sid);
        }
    }

    /** The user's context-window cap for the selected model (the Σ
     *  slider override), 0 when none — any thread, never throws. */
    static long userContextCap() {
        try {
            String[] pm = Models.selected(appCtx);
            if (pm == null) return 0;
            return AuthStore.contextLimit(appCtx, pm[0], pm[1]);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** P44: the post-compaction anchor block for {@code sid}, built
     *  from the real thread. Null when the store is not this session or
     *  the thread has no opening request. Never throws. */
    static String compactionAnchorBlock(String sid, String skipText) {
        try {
            if (sid == null) return null;
            final Tx t;
            synchronized (LOCK) { t = cur; }
            if (t == null || !sid.equals(t.sid)) return null;
            return compactionAnchorBlockTx(t, skipText);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The pure core — a scan over one transcript (package-private so
     *  the JVM suite pins it without hub state). Unlike the greeting
     *  guard there is NO minimum depth: a compaction summary landing IS
     *  the depth proof; a thread too young to anchor cannot have been
     *  compacted in the first place. */
    static String compactionAnchorBlockTx(Tx t, String skipText) {
        try {
            if (t == null) return null;
            String firstUser = null, lastUser = null, lastAssistant = null;
            int turns = 0;
            String skip = skipText == null ? "" : skipText.trim();
            synchronized (LOCK) {
                for (Row r : t.rows) {
                    if (r == null || r.text.length() == 0) continue;
                    if (r.kind == K_USER) {
                        String shown = r.text.toString();
                        if (skip.isEmpty() || !shown.trim().equals(skip)) {
                            if (firstUser == null) firstUser = shown;
                            lastUser = shown;
                        }
                        turns++;
                    } else if (r.kind == K_ASSISTANT) {
                        lastAssistant = r.text.toString();
                        turns++;
                    }
                }
            }
            if (firstUser == null) return null;
            return CompactionPolicy.anchorBlock(turns, firstUser, lastUser,
                    lastAssistant, userContextCap());
        } catch (Throwable e) {
            return null;
        }
    }

    /** Fire-and-track the same text again (self-heal / stream-flake retry).
     *  P31: the caller's run claim is still held — retries never re-claim. */
    private static void sendText(final String sid, final String q) {
        IO.execute(() -> {
            try {
                touchRun(sid);
                // P30: retries wrap like first sends — the failed original
                // never marked the session (styleMark runs on success only),
                // so the note rides exactly when needed and never doubles.
                final boolean tp = tersePref();
                final Boolean told;
                synchronized (styleTold) { told = styleTold.get(sid); }
                String wire = TerseMode.wrap(q, tp, told);
                final String recap = contextRepair(sid);   // P39: ride here too
                if (recap != null) wire = recap + "\n\n" + wire;
                final boolean anchorArmed = anchorPending(sid);   // P44
                final String anchor = anchorArmed
                        ? compactionAnchorBlock(sid, q) : null;
                if (anchor != null) wire = anchor + "\n\n" + wire;
                else if (GreetGuard.isBareGreeting(q)) {   // P43: same guard
                    String greet = threadContextBlock(sid, q);
                    if (greet != null) wire = greet + "\n\n" + wire;
                }
                List<String> bodies = buildBodies(
                        wire,
                        Models.selected(appCtx), agent);
                Api.Resp r = null;
                for (String body : bodies) {
                    r = Api.post("/session/" + sid + "/message", body, 900_000);
                    if (r.ok()) break;
                }
                if (r == null || !r.ok()) {
                    err("retry failed · HTTP " + (r == null ? 0 : r.status),
                            r == null ? "" : Json.findErrorText(Json.parse(r.body), 0),
                            r == null ? "" : r.body);
                    releaseRun(sid, false);
                    return;
                }
                styleMark(sid, tp);              // P30
                if (anchorArmed) anchorConsume(sid);   // P44: delivered — once
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
                    releaseRun(sid, false);
                }
            }
        });
    }

    // P37: sendImage removed — dead since P29. The attachment tray's
    // sendWithAttachments covers the single-image case (and more), no
    // caller remained in code or tests, and buildImageBodies/Vision stay
    // in service of the live paths.

    /** P29: the multi-image send — the tray flow's network half. Every
     *  picked photo rides ONE message (path 1: N file parts); if the
     *  server refuses image parts, the free vision model describes EACH
     *  photo and the joined descriptions feed the agent as one message.
     *  Hub-owned like every send: outlives the chat screen. */
    public static void sendWithAttachments(final List<File> files, final String text) {
        if (files == null || files.isEmpty()) { send(text); return; }
        if (creditBlocked()) {
            sys(CreditLimit.blockLine(spendTotal(), spendCap()));
            return;
        }
        final String cap = (text == null || text.trim().isEmpty())
                ? (files.size() == 1 ? "what do you see here?"
                                     : "what do you see in these " + files.size() + " images?")
                : text.trim();
        // one image bubble per file, in tray order
        for (int i = 0; i < files.size(); i++) {
            upsertImage(cur, "img" + System.currentTimeMillis() + "-" + i,
                    null, files.get(i).getAbsolutePath(), cap, "user");
        }
        IO.execute(() -> {
            String sid = ensureSession();
            if (sid == null) {
                sys("server not healthy yet — try again in a moment");
                return;
            }
            int claim = claimRun(sid);
            if (claim != RunBook.CLAIM_OK) {
                sys(claim == RunBook.CLAIM_BUSY
                        ? "this chat is still running — tap ■ to stop it first"
                        : "parallel run limit reached — let one finish");
                return;
            }
            try {
                validateSelectedModel();
                List<String> urls = new ArrayList<>();
                for (File f : files) {
                    urls.add(Vision.dataUrl(
                            java.nio.file.Files.readAllBytes(f.toPath())));
                }
                lastUserText = cap;
                runHadOutput = false;
                touchRun(sid);

                // ---- path 1: the server's own file parts (raw pixels)
                // P30: the style note rides the caption like any text send.
                final boolean tp = tersePref();
                final Boolean told;
                synchronized (styleTold) { told = styleTold.get(sid); }
                // P35: the render note rides image sends the same as text.
                final Boolean rTold;
                synchronized (renderTold) { rTold = renderTold.get(sid); }
                final String rNote = RenderCheck.needsNote(rTold)
                        ? RenderCheck.note(RenderServer.port(),
                                           RenderServer.token())
                        : null;
                String wire0 = TerseMode.wrap(cap, tp, told);
                final String wireCap = (rNote != null)
                        ? rNote + "\n\n" + wire0 : wire0;
                Api.Resp r = null;
                for (String body : buildMultiImageBodies(wireCap, urls,
                        Models.selected(appCtx), agent)) {
                    r = Api.post("/session/" + sid + "/message", body, 300_000);
                    if (r.ok()) break;
                }
                if (r != null && r.ok()) {
                    sys("◉ " + urls.size() + (urls.size() == 1
                            ? " image attached" : " images attached")
                            + " — the agent sees the pixels");
                    styleMark(sid, tp);          // P30
                    if (rNote != null) {         // P35: note delivered — once
                        synchronized (renderTold) { renderTold.put(sid, Boolean.TRUE); }
                        persistTold();
                    }
                    reconcile(r.body);
                    H.removeCallbacks(watchdog);
                    H.postDelayed(watchdog, 2000);
                    return;
                }

                // ---- path 2: a FREE vision model describes each one
                sys("◉ asking a free vision model to look…");
                String bearer = Vision.zenKey(appCtx);
                StringBuilder desc = new StringBuilder();
                IOException last = null;
                for (int i = 0; i < files.size(); i++) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(
                            files.get(i).toPath());
                    boolean got = false;
                    for (int c = 0; c < Vision.CANDIDATES.length && !got; c++) {
                        String[] m = Vision.modelAt(c);
                        try {
                            String d = Vision.describe(m[1], Vision.prompt(cap),
                                    bytes, bearer, 45_000);
                            if (files.size() > 1)
                                desc.append("### image ").append(i + 1).append('\n');
                            desc.append(d).append("\n\n");
                            got = true;
                        } catch (IOException e2) {
                            last = e2;             // rotate to the next model
                        }
                    }
                    if (!got) throw (last != null) ? last
                            : new IOException("no model answered");
                }
                sys("◉ a free vision model saw " + files.size()
                        + (files.size() == 1 ? " screenshot"
                                             : " screenshots") + " — feeding the agent");
                sendText(sid, cap + "\n\n[screenshot(s) shared by the user"
                        + " · vision via free model]\n" + desc);
            } catch (Exception e) {
                sys("attachment send failed: " + e);
                releaseRun(sid, false);
            } catch (Throwable e) {
                Trail.record(appCtx, "hub attachment send", e);
                sys("attachment send hit an internal error — contained");
                releaseRun(sid, false);
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

    /** buildBodies' sibling: text + N image file parts, same variant ladder.
     *  P29 multi-attach: one message carries every picked photo, so the
     *  agent sees them TOGETHER — the way a person attaches three pictures
     *  in one bubble, not three bubbles with three trips through the
     *  context. Pure — the JVM suite parses the parts back. */
    static List<String> buildMultiImageBodies(String caption, List<String> dataUrls,
                                              String[] sel, String agentMode) {
        if (dataUrls == null || dataUrls.isEmpty())
            return buildBodies(caption, sel, agentMode);
        if (dataUrls.size() == 1)
            return buildImageBodies(caption, dataUrls.get(0), sel, agentMode);
        LinkedHashMap<String, String> variants = new LinkedHashMap<>();
        StringBuilder files = new StringBuilder();
        for (int i = 0; i < dataUrls.size(); i++) {
            if (i > 0) files.append(',');
            files.append("{\"type\":\"file\",\"mime\":\"image/jpeg\",\"url\":")
                 .append(Json.quote(dataUrls.get(i))).append('}');
        }
        String text = "{\"type\":\"text\",\"text\":" + Json.quote(caption) + "}";
        String model = sel == null ? null
                : "\"model\":{\"providerID\":" + Json.quote(sel[0])
                + ",\"modelID\":" + Json.quote(sel[1]) + "}";
        String ag = "\"agent\":" + Json.quote(agentMode);
        String parts = "\"parts\":[" + text + "," + files + "]";
        if (model != null) variants.put("ma", "{" + model + "," + ag + "," + parts + "}");
        if (model != null) variants.put("m", "{" + model + "," + parts + "}");
        variants.put("a", "{" + ag + "," + parts + "}");
        variants.put("bare", "{" + parts + "}");
        return new ArrayList<>(variants.values());
    }

    /** P31: parallel-safe session creation — two rapid first-sends (or two
     *  chats created at once) must never POST two /session bodies. The
     *  synchronized + re-check makes the second caller reuse the first's
     *  session id instead of racing it. */
    private static synchronized String ensureSession() {
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
     *  else in the app watches runs; nothing else kills them.
     *  P31: aborts the DISPLAYED session's run (background runs in other
     *  chats are stopped from Sessions → long-press → Stop). */
    public static void abort() {
        abortSession(sessionId);
    }

    /** Stop one session's run (displayed chat via abort(), any chat via
     *  the Sessions sheet). The local claim is released even if the POST
     *  fails — a stop the user asked for is never argued with. */
    public static void abortSession(final String sid) {
        if (sid == null) return;
        boolean displayed = sid.equals(sessionId);
        String note = "■ stop requested" + RunBook.stopScopeNote(runningCount(), displayed);
        sys(note);
        IO.execute(() -> {
            try {
                Api.Resp r = Api.call("POST", "/session/" + sid + "/abort",
                        null, 10_000);
                if (!r.ok()) sys("abort returned HTTP " + r.status);
            } catch (Exception e) {
                sys("abort failed: " + e);
            } catch (Throwable e) {
                Trail.record(appCtx, "hub abort", e);
            }
            releaseRun(sid, false);
        });
    }

    /** P29: /compact — the server summarizes the session so the context
     *  window (and every future turn's re-read cost) shrinks WITHOUT
     *  losing the thread. Confirmed route in the bundled binary's OpenAPI:
     *  POST /session/{id}/summarize. Body ladder mirrors send(): the
     *  picked model first, then {} for the server default. The summary
     *  lands as a normal streamed message, so the existing upsert
     *  pipeline renders it and the Σ pill drains by itself — no polling,
     *  no restart, nothing else touched. */
    public static void compact() {
        if (busyFor(sessionId)) {
            sys("wait for this chat's run to finish, then compact");
            return;
        }
        if (creditBlocked()) {
            sys(CreditLimit.blockLine(spendTotal(), spendCap()));
            return;
        }
        IO.execute(() -> {
            try {
                String sid = ensureSession();
                if (sid == null) {
                    sys("nothing to compact yet — send a message first");
                    return;
                }
                String[] sel = Models.selected(appCtx);
                String body = sel == null ? "{}"
                        : "{\"providerID\":" + Json.quote(sel[0])
                        + ",\"modelID\":" + Json.quote(sel[1]) + "}";
                Api.Resp r = Api.post("/session/" + sid + "/summarize",
                        body, 30_000);
                if (!r.ok()) {
                    r = Api.post("/session/" + sid + "/summarize", "{}", 30_000);
                }
                if (r.ok()) {
                    sys("◈ compacting — the summary lands as a new message "
                            + "and the window frees up (history stays in the session)");
                    // P30: the summary replaces the window and is not
                    // guaranteed to carry the style note — void the
                    // session's style memory so the next send re-announces
                    // the terse preference once. Costs a few tokens; buys
                    // certainty the style survives the compact.
                    styleToldReset(sid);
                    // P40: the summary just landed — verify it actually
                    // CARRIES text. An empty summary poisons the session's
                    // context root (the model would see only the latest
                    // message from now on); diagnosePoison catches it and
                    // the repair runs before the amnesia is ever felt.
                    IO.execute(() -> {
                        try { Thread.sleep(1200); } catch (Exception ignored) {}
                        try {
                            Api.Resp g = Api.get("/session/" + sid + "/message");
                            if (g.ok()) diagnosePoison(sid,
                                    Json.arr(Json.parse(g.body)));
                        } catch (Throwable t2) {
                            Trail.record(appCtx, "hub compact check", t2);
                        }
                    });
                } else {
                    sys("compact refused · HTTP " + r.status);
                }
            } catch (Exception e) {
                sys("compact failed: " + Resilience.prettyNetError(e));
            } catch (Throwable t) {
                Trail.record(appCtx, "hub compact", t);
                sys("compact hit an internal error — contained");
            }
        });
    }

    // ------------------------------------------------------- busy state

    /** Settle-time cleanup. P26: the live tree NO LONGER lives in the
     *  transcript — the chat hosts it as a pinned footer while the run
     *  is active, and here (run over) it disappears entirely, exactly as
     *  the field asked. What remains: the edit/write tool cards in the
     *  transcript and the files themselves in the project file manager.
     *  Dead empty THINKING cards are purged as before (P20).
     *  P31: runs when the LAST run releases — with parallel runs, the
     *  footer lives until every chat's work is done. */
    private static void settleBusyUi() {
        liveSelPath = null;
        liveOpen = null;
        liveWaitingPeek = false;
        purgeEmptyThoughts();
        notifySpend();
        notifyLive();
    }

    /** P19 quiet-end, generalized (P31): EACH run is judged by its own
     *  freshest part — a chat that streams keeps its slot, a silent one
     *  releases it after 10 quiet minutes. This NEVER aborts anything
     *  server-side, and the generalized re-arm can still claim the run
     *  back if parts arrive later without a clean idle. */
    private static final Runnable watchdog = new Runnable() {
        @Override public void run() {
            Throwable t = Resilience.guard(() -> {
                boolean any = false;
                long now = System.currentTimeMillis();
                for (java.util.Map.Entry<String, Long> en
                        : new ArrayList<>(runs.entrySet())) {
                    if (now - en.getValue() > Resilience.quietEndMs()) {
                        releaseRun(en.getKey(), false);
                    } else {
                        any = true;
                    }
                }
                if (any) H.postDelayed(this, 5000);
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
                cur.sid = id;                     // P31: transcripts know their session
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
                // P51: streamed walk — same cure as reconcileOnBind; the
                // whole-store parse that used to sit here was the other
                // half of the field OOM (open a session → replay → heap).
                HttpURLConnection conn = Api.open("GET",
                        "/session/" + id + "/message", null, 120_000);
                try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    replayNeeded = true;       // P26: healthy flip will retry
                    sys("history unavailable · HTTP " + code);
                    return;
                }
                Reader in = new BufferedReader(new InputStreamReader(
                        conn.getInputStream(), StandardCharsets.UTF_8), 128 * 1024);
                final Tx tx0 = cur;
                final List<CompactionPoison.Msg> msgs = new ArrayList<>();
                final int[] hiddenSeen = {0};
                final boolean[] anyMessage = {false};
                StoreWalk.walk(in, REPLAY_RENDER, StoreWalk.KEEP_BYTES_DEFAULT,
                        new StoreWalk.Sink() {
                    @Override public boolean abort() { return !id.equals(sessionId); }
                    @Override public void message(Map<String, Object> item, int index) {
                        anyMessage[0] = true;
                        Map<String, Object> info = Json.map(item, "info");
                        if (info == null) info = item;
                        CompactionPoison.collectMsg(item, msgs);
                        if (Boolean.TRUE.equals(info.get("synthetic"))) return;
                        final Map<String, Object> finfo = info;   // lambda capture
                        Resilience.guard(() ->
                                applyMessageInfo(tx0, finfo, false, false));
                    }
                    @Override public void renderEnd(List<Map<String, Object>> items,
                                                    StoreWalk.Stats st) {
                        hiddenSeen[0] = st.hidden;
                        for (Map<String, Object> item : items) {
                            if (!id.equals(sessionId)) return;  // switched mid-replay
                            Map<String, Object> rinfo = Json.map(item, "info");
                            if (rinfo == null) rinfo = item;
                            if (Boolean.TRUE.equals(rinfo.get("synthetic"))) continue;
                            String role = Json.str(rinfo, "role");
                            applyMessageInfo(cur, rinfo);
                            List<Object> parts = StoreWalk.partsOf(item);
                            if (parts == null) continue;
                            for (Object p : parts) {
                                Map<String, Object> pm = Json.obj(p);
                                if (pm != null) applyPart(pm, role);
                            }
                        }
                    }
                });
                if (!id.equals(sessionId)) return;             // switched mid-walk
                if (!anyMessage[0]) {
                    // P39: the server answered fine and knows this session —
                    // but its message store is EMPTY. The history is gone
                    // server-side; say so once instead of leaving the user
                    // in a silently fresh-looking chat.
                    sys("the sandbox's store has no messages for this chat — "
                            + "its history was lost server-side. New sends start "
                            + "a fresh thread; context repair feeds the recent "
                            + "turns back automatically once the detector sees "
                            + "the pattern.");
                    return;
                }
                // P40: the history the user is about to see is also the
                // history the MODEL should see — check the compaction root
                // it starts from before the first send can ride a poisoned
                // context.
                diagnosePoisonMsgs(id, msgs);
                // P43: when the render cap actually bites, say so once —
                // "the tools keep going away" must never be silent again.
                // Only on a fresh bind (rows empty) so the note lands at
                // the top of the painted transcript, never mid-history.
                if (hiddenSeen[0] > 0) {
                    final int hidden = hiddenSeen[0];
                    main(() -> {
                        synchronized (LOCK) {
                            if (!cur.rows.isEmpty()) return;
                        }
                        sys("… " + hidden + " earlier messages stay out of "
                                + "view in this very long chat (render cap) — "
                                + "the thread itself is whole, the model and "
                                + "the ledger still hold all of it");
                    });
                }
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                replayNeeded = true;
                sys("history failed: " + e);
            } catch (Throwable e) {
                replayNeeded = true;
                if (e instanceof OutOfMemoryError) relieve();
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
            // P51: the replay is now a STREAMED walk (StoreWalk) — the
            // whole-store parse that used to live here was the heap
            // killer behind the field OOM. Accounting still walks EVERY
            // message; only the bounded render ring survives in memory.
            HttpURLConnection conn = null;
            try {
                conn = Api.open("GET", "/session/" + id + "/message", null, 120_000);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    replayNeeded = true;       // boot race / blip → retry on healthy
                    return;
                }
                Reader in = new BufferedReader(new InputStreamReader(
                        conn.getInputStream(), StandardCharsets.UTF_8), 128 * 1024);
                final Tx tx0 = cur;
                final List<CompactionPoison.Msg> msgs = new ArrayList<>();
                final Map<String, Object>[] lastRaw = new Map[1];
                final int[] hiddenSeen = {0};
                StoreWalk.walk(in, REPLAY_RENDER, StoreWalk.KEEP_BYTES_DEFAULT,
                        new StoreWalk.Sink() {
                    @Override public boolean abort() { return !id.equals(sessionId); }
                    @Override public void message(Map<String, Object> item, int index) {
                        Map<String, Object> info = Json.map(item, "info");
                        if (info == null) info = item;
                        lastRaw[0] = info;
                        CompactionPoison.collectMsg(item, msgs);
                        if (Boolean.TRUE.equals(info.get("synthetic"))) return;
                        final Map<String, Object> finfo = info;   // lambda capture
                        Resilience.guard(() ->
                                applyMessageInfo(tx0, finfo, false, false));
                    }
                    @Override public void renderEnd(List<Map<String, Object>> items,
                                                    StoreWalk.Stats st) {
                        hiddenSeen[0] = st.hidden;
                        for (Map<String, Object> item : items) {
                            if (!id.equals(sessionId)) return;  // switched mid-replay
                            Map<String, Object> rinfo = Json.map(item, "info");
                            if (rinfo == null) rinfo = item;
                            if (Boolean.TRUE.equals(rinfo.get("synthetic"))) continue;
                            String role = Json.str(rinfo, "role");
                            String mid0 = Json.str(rinfo, "id");
                            boolean knownMsg = false;
                            if (mid0 != null) {
                                synchronized (LOCK) { knownMsg = cur.msgs.containsKey(mid0); }
                            }
                            applyMessageInfo(cur, rinfo);
                            List<Object> parts = StoreWalk.partsOf(item);
                            if (parts == null) continue;
                            for (Object p : parts) {
                                Map<String, Object> pm = Json.obj(p);
                                if (pm == null) continue;
                                // P21: real v1.18.25 fetches carry messageID on
                                // every part — if a future shape drops it, inject
                                // the parent message id so the replay key STILL
                                // matches the live key.
                                if (Json.str(pm, "messageID") == null && mid0 != null) {
                                    pm.put("messageID", mid0);
                                }
                                String pid = Json.str(pm, "id");
                                if (Resilience.stablePartKey(pid)) {
                                    String mid = Json.str(pm, "messageID");
                                    if (mid == null) mid = mid0;
                                    if (mid != null && partWasTrimmed(cur, mid + "|" + pid))
                                        continue;           // ancient, already trimmed
                                } else if (knownMsg) {
                                    continue;   // pid-less replay can't rebuild the key
                                }
                                applyPart(pm, role);
                            }
                        }
                    }
                });
                if (!id.equals(sessionId)) return;             // switched mid-walk
                if (msgs.isEmpty()) {
                    replayNeeded = true;                       // empty store
                    return;
                }
                replayNeeded = false;
                // P40: every bind-time re-pull re-checks the compaction
                // root — the repair can also be triggered from here when
                // a compact poisoned the session between binds.
                diagnosePoisonMsgs(id, msgs);
                refreshTitleIfPlaceholder(id);
                // P43: when the render cap actually bites, say so once —
                // "the tools keep going away" must never be silent again.
                // Only on a fresh bind (rows empty) so the note lands at
                // the top of the painted transcript, never mid-history.
                if (hiddenSeen[0] > 0) {
                    final int hidden = hiddenSeen[0];
                    main(() -> {
                        synchronized (LOCK) {
                            if (!cur.rows.isEmpty()) return;
                        }
                        sys("… " + hidden + " earlier messages stay out of "
                                + "view in this very long chat (render cap) — "
                                + "the thread itself is whole, the model and "
                                + "the ledger still hold all of it");
                    });
                }
                // P21: the settle rule — the LAST message's info, the same
                // input Resilience.lastAssistantDoneFrom read off the old
                // in-memory array (tracked live during the walk now).
                if (Resilience.lastAssistantDoneFromInfo(lastRaw[0])) main(() -> {
                    // settle ONLY the displayed session's own finished run —
                    // never park busy while a DIFFERENT session's run streams
                    if (!busyFor(id) || isFinishingGuard() || !id.equals(sessionId)) return;
                    releaseRun(id, true);
                    sys("↩ back — the run finished while you were away; "
                            + "everything it said is right here");
                });
            } catch (Exception e) {
                replayNeeded = true;   // offline / still starting: retry on healthy
            } catch (Throwable e) {
                replayNeeded = true;
                if (e instanceof OutOfMemoryError) relieve();
                Trail.record(appCtx, "hub replay", e);
            } finally {
                if (conn != null) conn.disconnect();
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
            runs.clear();                    // P31: the old server (and its runs) died with the switch
            busy = false;
            lastUserText = null;
            modelFixRetried = false;
            flakeRetried = false;
            interruptedNotePending = false;
            replayNeeded = false;            // the old session is unreachable — stop retrying it
            liveSelPath = null;
            liveOpen = null;
            if (editWatcher != null) editWatcher.stop();
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
        synchronized (peekCache) { peekCache.clear(); peekStamp.clear(); }
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
        // P28: NO pre-clear here — loadPeek's (len, mtime) memo decides.
        // Pre-clearing would defeat the memo (it skips the read only when
        // the cache is warm), and this is THE hot path during an append
        // storm: every debounced fs batch lands here.
        liveWaitingPeek = false;
        loadPeek(p);
    }

    private static void collapseLive() {
        notifyLive();
    }

    /** View tapped a file row (abs, or null to deselect). P27: selecting a
     *  file is a user interaction — it PINS the card open (the peek must
     *  not be yanked away by the idle auto-collapse) and keeps tracking
     *  the file for the whole run. */
    public static void setLiveSel(String abs) {
        liveSelPath = abs;
        if (abs != null) {
            liveOpen = Boolean.TRUE;
            synchronized (peekCache) { peekCache.remove(abs); peekStamp.remove(abs); }
            liveWaitingPeek = false;
            loadPeek(abs);
        }
        notifyLive();
    }

    public static String liveSel() { return liveSelPath; }

    // ---- P27: the PIN model ------------------------------------------------
    // liveOpen tri-state, now with explicit meaning:
    //   null   — AUTO (the user never touched the card): expanded while the
    //            feed is hot (edits < ACTIVE_MS old), auto-collapsed when
    //            idle. The view applies the collapse structurally ONLY when
    //            no touch is in progress, so the timer can never eat a tap.
    //   TRUE   — PINNED open by the user (tap the head): never auto-collapses.
    //   FALSE  — user explicitly collapsed (rare; head tap while pinned-to-
    //            open is unpin → auto, but a selection keeps this honest).
    // Every repaint and re-bind reads the SAME hub state, so liveOpen and
    // liveSelPath survive every paint — and settleBusyUi (run over) clears
    // both, which is what makes the whole card disappear when the run ends.

    /** Head tap: unpinned → PIN open; pinned → back to auto. */
    public static void toggleLivePin() {
        liveOpen = (liveOpen == Boolean.TRUE) ? null : Boolean.TRUE;
        notifyLive();
    }

    /** True when the user pinned the card open (dot burns accent). */
    public static boolean livePinned() { return liveOpen == Boolean.TRUE; }

    /** The effective expanded state: user choice wins; auto follows heat. */
    public static boolean liveExpanded() {
        if (liveOpen != null) return liveOpen;
        synchronized (editFeed) {
            return EditPulse.hot(editFeed, System.currentTimeMillis());
        }
    }

    // P26: the K_LIVE row is GONE from the transcript model. It used to
    // be inserted at run start — and every tool/text row that streamed in
    // afterwards landed BELOW it, so autoscroll buried the tree above the
    // fold within seconds (the field saw "no files in chat"). The chat
    // now renders the tree from the FEED (liveTree/liveNewest/peek APIs
    // below) in a footer pinned above the composer: always visible while
    // the agent works, gone the moment it settles.

    // P28: per-path (len, mtime) of the LAST peek content in peekCache —
    // the memo that keeps an append storm from re-reading + re-splitting a
    // big file on every debounced fs batch when nothing actually changed.
    private static final Map<String, long[]> peekStamp = new HashMap<>();

    /** Load the peek window off-thread. P28 three tiers, cheapest first:
     *  (1) memo — (len, mtime) unchanged and cache warm → no read at all;
     *  (2) tail — no edit-tool locator and file > 64 KB → read the last
     *      TAIL_BYTES only (a streamed append can only be at the tail) and
     *      count the skipped newlines for honest line numbers;
     *  (3) full — locator needs a whole-file needle search; the read is
     *      hard-capped at 2 MB so a file that grew mid-read can never blow
     *      memory. */
    private static void loadPeek(final String abs) {
        if (abs == null) return;
        final File f = new File(abs);
        final long flen;
        final long fmt;
        try { flen = f.length(); fmt = f.lastModified(); } catch (Exception e) {
            main(() -> { synchronized (peekCache) { peekCache.put(abs, "  (can't stat file)"); } notifyLive(); });
            return;
        }
        synchronized (peekCache) {
            long[] st = peekStamp.get(abs);
            if (st != null && st[0] == flen && st[1] == fmt
                    && peekCache.containsKey(abs)) {
                notifyLive();                       // memo hit — text still good
                return;
            }
        }
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
            } else if (f.isDirectory()) {
                // P43: a directory "edit" is a folder touch (mkdir / a
                // clone landing) — the old path tried to OPEN it and the
                // live card printed the raw "open failed: EISDIR (Is a
                // directory)" the field screenshotted.
                text = "  (folder — no file to preview)";
            } else {
                try {
                    if (flen > 2_000_000) {
                        text = "  (file too large to peek — open it in Files)";
                    } else if (focus == null && flen > EditPulse.TAIL_BYTES) {
                        // ---- P28 tier 2: the tail slice ----
                        byte[] tail = new byte[EditPulse.TAIL_BYTES];
                        boolean headCut;
                        try (java.io.RandomAccessFile raf =
                                     new java.io.RandomAccessFile(f, "r")) {
                            raf.seek(flen - tail.length);
                            raf.readFully(tail);
                        }
                        headCut = tail[0] != '\n';
                        long nlBefore = 0;
                        try (FileInputStream in = new FileInputStream(f)) {
                            byte[] buf = new byte[64 * 1024];
                            long remaining = flen - tail.length;
                            int r;
                            while (remaining > 0 && (r = in.read(buf, 0,
                                    (int) Math.min(buf.length, remaining))) > 0) {
                                nlBefore += EditPulse.countNewlines(buf, r);
                                remaining -= r;
                            }
                        }
                        text = EditPulse.peekTailWindow(tail, nlBefore,
                                EditPulse.PEEK_LINES, headCut);
                    } else {
                        // ---- tier 3: bounded full read ----
                        String content = readBounded(f, flen, 2_000_000);
                        text = EditPulse.peek(content, focus, EditPulse.PEEK_LINES);
                    }
                } catch (Exception e2) {
                    // P43: a folder that appeared mid-flight (TOCTOU) must
                    // not print the raw exception either.
                    if (e2.getMessage() != null
                            && e2.getMessage().contains("EISDIR")) {
                        text = "  (folder — no file to preview)";
                    } else {
                        text = "  (can't peek: " + e2.getMessage() + ")";
                    }
                }
            }
            final String t = text;
            main(() -> {
                synchronized (peekCache) {
                    peekCache.put(abs, t);
                    peekStamp.put(abs, new long[]{flen, fmt});
                }
                liveWaitingPeek = false;
                notifyLive();
            });
        });
    }

    /** Read at most {@code cap} bytes of {@code f} as UTF-8 — never more,
     *  even if the file grew between length() and the read. */
    private static String readBounded(File f, long knownLen, int cap)
            throws Exception {
        try (FileInputStream fin = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                    (int) Math.min(knownLen > 0 ? knownLen : cap, cap));
            byte[] buf = new byte[64 * 1024];
            long budget = cap;
            int r;
            while (budget > 0 && (r = fin.read(buf, 0,
                    (int) Math.min(buf.length, budget))) > 0) {
                out.write(buf, 0, r);
                budget -= r;
            }
            return out.toString("UTF-8");
        }
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

    // ------------------------------------------------ P50: question reply

    /** Remove a pending ask locally (SSE question.replied also lands here
     *  — both paths are idempotent). */
    private static void removeQuestion(String sid, String rid) {
        synchronized (QUESTIONS) {
            List<Map<String, Object>> l = QUESTIONS.get(sid);
            if (l == null) return;
            for (java.util.Iterator<Map<String, Object>> it = l.iterator();
                    it.hasNext(); ) {
                if (rid.equals(Json.str(it.next(), "id"))) {
                    it.remove();
                    break;
                }
            }
            if (l.isEmpty()) QUESTIONS.remove(sid);
        }
    }

    /**
     * One reply ladder, verified against the shipped v1.18.25 binary:
     * POST /api/session/{sid}/question/{rid}/reply  {"answers":[[label,…],…]} → 204,
     * legacy /session/... twin as fallback. Answers are one label array
     * per question, in order (Questions.answersJson keeps indexes aligned;
     * the server renders an empty array as "Unanswered").
     */
    public static void answerQuestion(final String sid, final String rid,
            final List<List<String>> picked,
            final java.util.function.BiConsumer<Boolean, String> cb) {
        PERM.execute(() -> {
            String errS = null;
            boolean ok = false;
            String body = Questions.answersJson(picked);
            try {
                Api.Resp r = Api.post("/api/session/" + sid + "/question/"
                        + rid + "/reply", body, 15_000);
                ok = r.ok();
                if (!ok) {
                    r = Api.post("/session/" + sid + "/question/" + rid
                            + "/reply", body, 15_000);
                    ok = r.ok();
                }
                if (!ok) errS = "HTTP " + (r == null ? "?" : r.status);
            } catch (Exception e) {
                errS = String.valueOf(e);
            }
            final String f = errS;
            final boolean done = ok;
            if (done) {
                removeQuestion(sid, rid);
                ServerService.noteQuestionAnswered();
            }
            main(() -> {
                if (cb != null) cb.accept(done, f);
                else if (!done) sys("question reply failed · " + f);
                notifyQuestions();
            });
        });
    }

    /** Skip an ask (plan: "revise"; question: the agent reads the skip). */
    public static void rejectQuestion(final String sid, final String rid,
            final java.util.function.BiConsumer<Boolean, String> cb) {
        PERM.execute(() -> {
            String errS = null;
            boolean ok = false;
            try {
                Api.Resp r = Api.post("/api/session/" + sid + "/question/"
                        + rid + "/reject", "{}", 15_000);
                ok = r.ok();
                if (!ok) {
                    r = Api.post("/session/" + sid + "/question/" + rid
                            + "/reject", "{}", 15_000);
                    ok = r.ok();
                }
                if (!ok) errS = "HTTP " + (r == null ? "?" : r.status);
            } catch (Exception e) {
                errS = String.valueOf(e);
            }
            final String f = errS;
            final boolean done = ok;
            if (done) {
                removeQuestion(sid, rid);
                ServerService.noteQuestionAnswered();
            }
            main(() -> {
                if (cb != null) cb.accept(done, f);
                notifyQuestions();
            });
        });
    }

    /** Backfill pending asks for a session over HTTP (chat open/resume):
     *  an ask can predate the screen (the SSE event fired while the app
     *  was dead, or the server queued it before our event subscription).
     *  GET /api/session/{sid}/question → replaces the store for THIS sid.
     *  Silent on any failure — the SSE path and the next resume retry. */
    public static void fetchPendingQuestions(final String sid) {
        if (sid == null) return;
        PERM.execute(() -> {
            try {
                Api.Resp r = Api.get("/api/session/" + sid + "/question");
                if (!r.ok() || r.body == null) return;
                Object o = Json.parse(r.body);
                List<Object> arr = null;
                if (o instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> tmp = (List<Object>) o;
                    arr = tmp;
                } else if (o instanceof Map) {
                    // tolerate {requests:[...]} / {items:[...]} wrappers
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    for (String k : new String[]{"requests", "items", "questions"}) {
                        List<Object> l = Json.list(m, k);
                        if (l != null) { arr = l; break; }
                    }
                }
                if (arr == null) return;
                List<Map<String, Object>> fresh = new ArrayList<>();
                for (Object e : arr) {
                    if (e instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) e;
                        fresh.add(new HashMap<>(m));
                    }
                }
                synchronized (QUESTIONS) {
                    if (fresh.isEmpty()) QUESTIONS.remove(sid);
                    else QUESTIONS.put(sid, fresh);
                }
                main(RunHub::notifyQuestions);
            } catch (Exception ignored) {
            }
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

