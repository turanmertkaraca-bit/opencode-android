package ai.opencode.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P2: the service now OWNS the SSE event stream (previously the chat
 * activity did). Why: permission requests must be caught even when no
 * screen is open — the agent stalls until POST /permission/{id}/reply
 * arrives. The service parses /event, queues permissions, nudges via the
 * foreground notification, and rebroadcasts parsed events to UI listeners.
 *
 * Also holds a partial wake lock while the server runs (Termux pattern):
 * long agent runs must survive screen-off.
 */
public class ServerService extends Service {

    private static final String CH = "server";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_STOP = "ai.opencode.app.STOP";

    // ---- server state (single in-process owner: this service) ----
    public static final int ST_IDLE = 0, ST_STARTING = 1, ST_HEALTHY = 2, ST_EXITED = 3, ST_STOPPED = 4;
    private static volatile int state = ST_IDLE;
    private static volatile Process proc;
    private static volatile Thread runner;
    private static volatile boolean RUNNING = false;
    private static volatile HttpURLConnection sseConn;
    /** P52: the one live SSE owner. Stored so stopServer can interrupt it
     *  and so a superseded thread (fast restart / failed spawn) exits on
     *  its generation check instead of double-ingesting /event. */
    private static final Object SSE_LOCK = new Object();
    private static volatile int sseGen;
    private static volatile Thread sseThread;
    private static final StringBuilder tail = new StringBuilder();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static PowerManager.WakeLock wakeLock;
    /** P52: acquire/release check-then-act used to race across the SSE
     *  and supervisor threads, leaking a PARTIAL_WAKE_LOCK. One lock. */
    private static final Object WAKE_LOCK = new Object();
    /** P52: appendDiag/appendDiagStatic truncate+append is read-modify-
     *  write; two threads can interleave and corrupt the file. One lock. */
    private static final Object DIAG_LOCK = new Object();

    // ---- P17: eco idle — the wake lock exists ONLY while the agent works
    // The P16 code held a PARTIAL_WAKE_LOCK for the ENTIRE server lifetime
    // (Termux pattern, but Termux users accept the drain). The user felt
    // it: "my phone feels hot when it's running while doing nothing". With
    // eco idle ON (the default), the lock is acquired when agent activity
    // events arrive (message parts, permission asks) and RELEASED on
    // session.idle — so a healthy-but-idle server no longer pins the CPU
    // out of deep sleep with the screen off. Settings → keep alive →
    // "Cool idle" restores the always-held behavior when flipped off.
    private static volatile boolean ecoIdle = true;
    private static volatile boolean agentActive;

    // ---- P18: unstoppable sandbox ----
    /** True only for explicit user stops (notification Stop / Settings
     *  restart) — auto-restart must never fight a deliberate stop. */
    private static volatile boolean userStop;
    /** One-shot note for the UI: set when an AUTO-restart becomes healthy,
     *  consumed by ChatActivity so it can say “sandbox recovered — this
     *  chat is still attached” instead of the user discovering it. */
    private static volatile String recoveryNote;

    /** P19: set by the drain thread the moment the child announces the port
     *  it actually bound (—port 0). Health is gated on this so the app can
     *  never mistake a WEDGED ORPHAN sitting on the old port for the new
     *  server being healthy. */
    private static volatile boolean portKnown;

    /** Consume the pending recovery note (null when none). One-shot. */
    public static String consumeRecoveryNote() {
        String n = recoveryNote;
        recoveryNote = null;
        return n;
    }
    /** App context captured in onCreate — the wake lock is static now. */
    private static volatile Context appCtx;

    /** True while agent events are flowing (runs, permission asks). */
    public static boolean agentActive() { return agentActive; }

    private void noteActivity() {
        agentActive = true;
        if (ecoIdle && wakeLock == null) acquireWakeLock();
    }

    private static void noteIdle() {
        agentActive = false;
        // P52: a session.idle for ONE session must not release the lock
        // while another run is still in flight (mirrors the hibernate gate).
        if (ecoIdle && !RunHub.busy()) releaseWakeLock();
    }

    public interface Evt { void on(int newState, String detail); }
    public interface EventListener { void onEvent(Map<String, Object> ev); }

    private static final CopyOnWriteArrayList<Evt> listeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<EventListener> evtListeners = new CopyOnWriteArrayList<>();

    // ---- permission queue (P2, P4 rework) ----
    // ---- P8 per-project sandbox ----
    /** Desired server working directory (the project folder). opencode
     *  scopes its project tools + sessions to cwd, so each project card
     *  opens ITS OWN sandbox rooted at that folder. Null → app home. */
    private static volatile File startDir;
    private static volatile File servingDir;
    /** P42: set when a chosen project folder could not be served and the
     *  sandbox fell back to app home — the agent must be TOLD (env note),
     *  or it writes to the project path it was given, ENOENTs, and burns
     *  the session probing mounts. Null = serving the real folder. */
    public static volatile String servingFallback;
    /** True between restart() and the next spawn — screens must not
     *  "helpfully" auto-start the service in that window. */
    private static volatile boolean pendingRestart;

    public static void setStartDir(File d) { startDir = d; }
    public static boolean pendingRestart() { return pendingRestart; }
    /** The directory the RUNNING server was started in (null = not serving). */
    public static File servingDir() { return servingDir; }
    /** True when opening this folder requires a server restart. */
    public static boolean needsSwitch(File dir) {
        return dir != null && (servingDir == null || !dir.equals(servingDir));
    }
    /** Runtime project switch: set the sandbox root and restart the server. */
    public static void switchTo(Context c, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        startDir = dir;
        if (!dir.equals(servingDir)) restart(c);
    }

    private static final ConcurrentLinkedQueue<Map<String, Object>> PERMS = new ConcurrentLinkedQueue<>();
    // P46/P52 long-session cap: seen ids grow by one per permission the
    // server ever showed; a month of hard use must not grow the set
    // forever. The old ConcurrentHashMap.newKeySet() had no order, so the
    // only way to bound it was `size() <= 256 && add(id)` — which
    // SHORT-CIRCUITED the add once full and permanently disabled
    // permission delivery (the long-session field report). An
    // access-ordered LRU evicts the oldest id instead of refusing the
    // newest, so delivery never stops. All access is under PERM_LOCK.
    private static final Object PERM_LOCK = new Object();
    private static final int PERM_ID_CAP = 256;
    private static final Set<String> seenPermIds = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Boolean> e) {
                    return size() > PERM_ID_CAP;
                }
            });
    /** Request ids we already answered — tombstones so a re-seed/refresh can
     *  never re-queue a stale ask and flap the dialog forever. Same bounded
     *  LRU (P52 item 9: no clear() tombstone hole). */
    private static final Set<String> answeredPermIds = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Boolean> e) {
                    return size() > PERM_ID_CAP;
                }
            });

    public static int getState() { return state; }
    public static String getTail() { synchronized (tail) { return tail.toString(); } }
    public static boolean healthy() { return state == ST_HEALTHY; }

    /**
     * P40: stop the server and HOLD it stopped while the context repair
     * edits the store — the surgical sibling of stopForDelete. The
     * supervisor stays disarmed so nothing respawns mid-surgery;
     * restart() re-arms and respawns when the repair is done. Safe from
     * any thread.
     */
    public static void stopForRepair(Context c) {
        userStop = true;
        pendingRestart = false;
        Intent stop = new Intent(c, ServerService.class).setAction(ACTION_STOP);
        try { c.startService(stop); } catch (Exception ignored) {}
    }

    /**
     * P6: stop + start again (after auth/config changes so the server picks
     * them up; cold start is ~5 s). Safe to call from any foreground screen.
     */
    public static void restart(Context c) {
        pendingRestart = true;
        userStop = false;               // a manual restart re-arms the supervisor
        recoveryNote = null;
        Intent stop = new Intent(c, ServerService.class).setAction(ACTION_STOP);
        try { c.startService(stop); } catch (Exception ignored) {}
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (Binaries.binaryReady(c)) {
                try { c.startForegroundService(new Intent(c, ServerService.class)); }
                catch (Exception e) {
                    // P52: a background-FGS refusal must not strand the
                    // sandbox: pendingRestart=false lets every onResume
                    // guard auto-start again, and the watchdog retries.
                    pendingRestart = false;
                    userStop = false;
                    wantSvc(c, true);
                    scheduleWatchdog(c);
                }
            }
        }, 1200);
    }

    /**
     * P30: stop the server and DO NOT spawn anything again — used when
     * the project it serves is being deleted (the server's cwd must not
     * have the floor pulled out from under proot mid-delete). Unlike
     * restart(), the supervisor stays disarmed and servingDir is cleared
     * so the next project open makes a clean switch. Safe from any thread.
     */
    public static void stopForDelete(Context c) {
        userStop = true;
        pendingRestart = false;
        servingDir = null;
        Intent stop = new Intent(c, ServerService.class).setAction(ACTION_STOP);
        try { c.startService(stop); } catch (Exception ignored) {}
    }

    public static void subscribe(Evt e) { if (!listeners.contains(e)) listeners.add(e); }
    public static void unsubscribe(Evt e) { listeners.remove(e); }
    public static void subscribeEvents(EventListener e) { if (!evtListeners.contains(e)) evtListeners.add(e); }
    public static void unsubscribeEvents(EventListener e) { evtListeners.remove(e); }

    /** Head of the pending permission queue (null if none). */
    public static Map<String, Object> peekPermission() { return PERMS.peek(); }
    /** Remove the head permission (after it has been answered). */
    public static void dropPermission() { PERMS.poll(); }
    /** Tombstone a request id and purge it from the queue (after the reply
     *  POST — covers both the dialog path and permission.replied events). */
    public static void noteAnswered(String id) {
        if (id == null) return;
        synchronized (PERM_LOCK) {
            answeredPermIds.add(id);
        }
        for (java.util.Iterator<Map<String, Object>> it = PERMS.iterator(); it.hasNext(); ) {
            if (id.equals(Json.str(it.next(), "id"))) it.remove();
        }
    }

    /** True when this id is new (records it in the bounded LRU). Never
     *  gates on size — the LRU evicts the oldest instead, so permission
     *  delivery can never be permanently disabled (P52). */
    private static boolean markSeenPerm(String id) {
        if (id == null) return false;
        synchronized (PERM_LOCK) {
            if (answeredPermIds.contains(id)) return false;
            return seenPermIds.add(id);
        }
    }
    public static int pendingPermissions() { return PERMS.size(); }

    private static void setState(int s, String detail) {
        state = s;
        for (Evt e : listeners) {
            try { e.on(s, detail); } catch (Exception ignored) {}
        }
    }

    private static void addTail(String line) {
        synchronized (tail) {
            if (tail.length() > 6000) tail.delete(0, tail.length() - 3000);
            tail.append(line).append('\n');
        }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();
        procStartTs = System.currentTimeMillis();   // P27 boot-budget t0
        // P25: the run engine binds here too (defensive — App.onCreate
        // already does it). From now on the SSE feed NEVER drops frames:
        // RunHub listens for the whole process lifetime, screen or not.
        RunHub.init(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CH, "OpenCode server", NotificationManager.IMPORTANCE_LOW));
    }

    /** P27: process start — the cold-boot budget's other endpoint. */
    private static volatile long procStartTs;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            userStop = true;
            wantSvc(this, false);          // P50: a user stop clears the want
            stopServer();
            setState(ST_STOPPED, "stopped");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotif("starting…"));
        if (runner != null && runner.isAlive()) {
            setState(state, "already running");
            scheduleWatchdog(this);        // P50: the chain rides every start
            return START_STICKY;
        }
        final File bin = Binaries.binaryFile(this);
        if (!bin.exists() || !Binaries.isElf(bin)) {
            setState(ST_EXITED, "no valid binary imported");
            updateNotif("no binary imported");
            wantSvc(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        RUNNING = true;
        // P51: heal opencode.json before the server ever reads it — a
        // hand-edited or agent-written context cap must not wedge the
        // sandbox at boot (the "changed the cap → sandbox won't start"
        // field report; every app write is already sanitized, this
        // catches everything that arrived around it).
        if (AuthStore.healConfig(this))
            updateNotif("config healed");
        // P18: a plain start (boot, screen open, restart()'s delayed relaunch)
        // always re-arms the supervisor — the ACTION_STOP intent that
        // restart() queues first would otherwise leave userStop=true here
        // and the server would never spawn.
        userStop = false;
        pendingRestart = false;
        wantSvc(this, true);              // P50: the sandbox is WANTED — the
                                          // watchdog may resurrect it forever
        scheduleWatchdog(this);           // P50: arm the keep-alive chain
        setState(ST_STARTING, "spawning opencode serve");
        runner = new Thread(() -> runServer(bin), "oc-server");
        runner.setDaemon(false);
        runner.start();
        return START_STICKY;
    }

    /**
     * P18 supervisor: the P16 watcher treated any server death as final —
     * setState(ST_EXITED) + stopSelf() — so a blipped server left the chat
     * dead until the user cold-booted the app (the field report). Now the
     * supervisor loop OWNS the whole lifecycle: spawn → wait healthy →
     * watch → on death log diagnostics, check the crash-streak guard, and
     * respawn with backoff. Sessions live on disk, so after an auto-restart
     * the same chat continues (the UI gets a recovery note).
     */
    private void runServer(File bin) {
        try {
            Binaries.makeExec(bin); // idempotent; P0-verified pattern
        } catch (Exception e) {
            setState(ST_EXITED, "chmod failed: " + e.getMessage());
            updateNotif("exec setup failed");
            stopSelf();
            return;
        }

        // P17: eco idle reads the pref at spawn; the lock is taken here
        // only when eco idle is OFF (legacy always-on behavior).
        try {
            ecoIdle = getSharedPreferences("oc", MODE_PRIVATE)
                    .getBoolean("eco_idle", true);
        } catch (Exception ignored) {}
        agentActive = false;
        if (!ecoIdle) acquireWakeLock();

        // P8: the project card decides the sandbox root. Everything the
        // agent touches (sessions, file tools, shell cwd) is scoped to it.
        File cwd = (startDir != null && startDir.isDirectory())
                ? startDir : Binaries.homeDir(this);
        servingDir = cwd;
        // P42: a silent fallback teaches the agent nothing — surface it
        // through the env note instead of letting it become a probe loop.
        servingFallback = (startDir != null && !startDir.isDirectory())
                ? startDir.getAbsolutePath() : null;
        RenderServer.setRoot(cwd);   // P35: the render endpoint serves THIS root
        // P26: tell the hub which root this server owns — a DECK SWITCH
        // lands here as a different root and the hub resets to a fresh
        // chat instead of POSTing into a session this server never had.
        RunHub.onProjectRoot(cwd.getAbsolutePath());

        // P41: pin the post-compaction memory floor BEFORE the server
        // reads its config. When the sandbox summarizes a full context,
        // compaction.preserve_recent_tokens decides how many tokens of
        // real recent turns the model keeps verbatim — the server's own
        // default (2k–15k) is how long chats lost their thread after
        // every silent compaction. Write-if-absent from the bundled
        // snapshot's window for the picked model (pure rule in
        // CompactionPolicy); a user's own compaction block always wins
        // and a failure is one diagnostics line, never a boot blocker.
        try {
            String[] sel = Models.selected(this);
            long lim = sel == null ? 0
                    : Models.bundledLimit(this, sel[0], sel[1]);
            // P42-check: the user's window cap shrinks the window the
            // server actually enforces — the floor must assume the
            // smaller of the two, or a capped model compacts into a
            // no-op (the exact hazard the floor exists to prevent).
            long win = sel == null ? lim
                    : CompactionPolicy.effectiveWindow(lim,
                            AuthStore.contextLimit(this, sel[0], sel[1]));
            if (AuthStore.ensureCompactionPreserve(this, win))
                appendDiag("config", "compaction preserve_recent_tokens pinned ("
                        + win + " tok window)");
        } catch (Throwable t) {
            appendDiag("config", "compaction policy not written: " + t);
        }

        // P45: the auto-compaction KILL SWITCH — compaction.auto rides the
        // switch (default OFF: the sandbox never summarizes a chat's
        // memory on its own; a full context errors instead). Same
        // diagnostics-line-on-failure contract as the floor above.
        try {
            if (AuthStore.ensureCompactionAuto(this))
                appendDiag("config", "compaction auto="
                        + AuthStore.compactionAuto(this)
                        + " pinned (" + (AuthStore.compactionAuto(this)
                        ? "summarizing allowed" : "never summarize") + ")");
        } catch (Throwable t) {
            appendDiag("config", "compaction auto not written: " + t);
        }

        // P19: pick a bindable port BEFORE spawning. Verified on the rig:
        // opencode maps --port 0 to its own default (4096), so a true
        // ephemeral spawn is impossible — the app asks the kernel instead.
        // When the default is free we keep it; when a wedged orphan (the
        // field crash) holds it, we serve on the next free port and the
        // banner parse below confirms the child actually owns it.
        int want = Resilience.pickFreePort(Api.PORT);
        if (want != Api.PORT) {
            appendDiag("port", "default " + Api.PORT + " busy — serving on " + want);
            Api.setPort(want);
        }
        ProcessBuilder pb = new ProcessBuilder(
                bin.getAbsolutePath(), "serve",
                "--port", String.valueOf(want),
                "--hostname", Api.HOST);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        try {
            Binaries.applyEnv(this, pb);   // P23: was unguarded — a throw here
        } catch (Throwable t) {            // killed the supervisor thread
            Trail.record(this, "sandbox env", t);
            appendDiag("env", "applyEnv failed: " + t);
        }

        long[] deaths = new long[8];        // recent death timestamps (ring)
        int deathIdx = 0;
        int attempts = 0;                   // consecutive auto-restarts

        startSse();   // ONE SSE owner thread for every spawn of this service

        // P35: the render endpoint — the browser the agent can use. It is
        // the APP's own loopback server (token-gated, one POST /render
        // route), independent of the opencode child's lifecycle: a render
        // check never races a sandbox restart. Idempotent per process;
        // respawns and deck switches only refresh the served root.
        RenderServer.ensureStarted(this, cwd, this::appendDiag);

        while (RUNNING && !userStop) {
            if (attempts > 0) {
                long back = Resilience.restartBackoffMs(attempts - 1);
                setState(ST_STARTING, "auto-restart in " + back / 1000 + "s");
                updateNotif("recovering sandbox…");
                try { Thread.sleep(back); } catch (InterruptedException e) { return; }
                if (!RUNNING || userStop) return;
            }

            // A previous server (or an orphan from a killed app process) can
            // still hold the port — with --port 0 the respawn no longer NEEDS
            // that port, but the orphan still burns RAM and may hold the
            // session store, so sweep it: kill every process whose argv[0]
            // is exactly our binary, then the old port-owner belt-and-braces.
            int swept = sweepOrphans(bin.getAbsolutePath());
            if (swept > 0) appendDiag("orphan-sweep", "killed " + swept + " leftover opencode process(es)");
            int stale = killStalePortOwner();
            if (stale > 0) appendDiag("stale-port", "killed pid " + stale);

            setState(ST_STARTING, "sandbox: " + cwd.getName());
            final Process p;
            try {
                p = pb.start();
            } catch (Exception e) {
                appendDiag("spawn-fail", String.valueOf(e.getMessage()));
                setState(ST_EXITED, "spawn failed: " + e.getMessage());
                updateNotif("spawn failed");
                return;                     // spawn errors are not transient
            }
            proc = p;
            portKnown = false;              // re-armed: the new child announces

            final int thisAttempt = attempts;
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()), 16 * 1024)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        addTail(line);
                        // P19: adopt the port the child actually bound.
                        int pp = Api.parseListenPort(line);
                        if (pp > 0) {
                            if (pp != Api.PORT) appendDiag("port", "bound " + pp
                                    + " (default " + 4096 + " was taken or ephemeral)");
                            Api.setPort(pp);
                            portKnown = true;
                        }
                    }
                } catch (Throwable ignored) { }
            }, "oc-drain");
            drain.setDaemon(true);
            drain.start();

            setState(ST_STARTING, "opencode serve pid=" + p.hashCode());

            // wait healthy (up to 120 s) — but ONLY against the port this
            // child announced (or, as a legacy fallback, after 20 s against
            // whatever Api.PORT holds if the banner never appeared)
            long t0 = System.currentTimeMillis();
            boolean healthy = false;
            boolean portFallbackLogged = false;
            for (int i = 0; i < 240 && RUNNING && !userStop; i++) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                if (!p.isAlive()) break;
                boolean gate = portKnown || (System.currentTimeMillis() - t0 > 20_000);
                if (gate && !portFallbackLogged && !portKnown) {
                    portFallbackLogged = true;
                    appendDiag("port", "no listen banner in 20 s — probing default port");
                }
                if (gate) {
                    try {
                        if (serverUp()) {
                            healthy = true;
                            long ms = System.currentTimeMillis() - t0;
                            setState(ST_HEALTHY, "http 200 in " + ms + " ms on :" + Api.PORT
                                    + (attempts > 0 ? " (auto-recovered)" : ""));
                            updateNotif("running · " + cwd.getName());
                            if (attempts > 0) {
                                recoveryNote = "sandbox auto-recovered — the previous "
                                        + "server process died (" + nz(lastTailLine(), "unknown")
                                        + "). This chat is still attached; resend if the "
                                        + "last message didn't finish";
                                appendDiag("recovered", "attempt " + thisAttempt);
                            }
                            if (attempts == 0) {
                                // P27 phase 2: the cold-boot budget, ON RECORD —
                                // spawn→healthy is the number the field feels;
                                // process→healthy covers an app-start that
                                // triggered this spawn. Kept off the critical
                                // path: the hygiene sweep runs AFTER health.
                                long procMs = procStartTs > 0
                                        ? System.currentTimeMillis() - procStartTs : -1;
                                appendDiag("boot-budget", "spawn→healthy " + ms
                                        + " ms · process→healthy " + procMs + " ms");
                                final Context fc = appCtx;
                                new Thread(() -> Debian.bootHygiene(fc), "oc-hygiene")
                                        .start();
                            }
                            break;
                        }
                    } catch (Throwable ignored) { }
                }
            }

            if (healthy) {
                // SSE: started ONCE for the whole service lifetime (its own
                // loop waits for ST_HEALTHY and reconnects across respawns
                // — calling it per-spawn would double-deliver events).
            }

            // watcher: death detection + late health (inner, per-spawn).
            // P19: also the heartbeat — one diag line every 30 s so that a
            // WHOLE-PROCESS death (the field crash: nothing recorded, nothing
            // loggable from a dead JVM) still leaves “how long ago did the
            // log stop, and what was memory then” on disk for the next report.
            boolean died = false;
            int hbTick = 0;
            boolean hibernateAnnounced = false;
            while (RUNNING && !userStop) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                syncEcoIdle();   // P50: the Cool idle flip applies LIVE — no restart
                if (!p.isAlive()) { died = true; break; }
                if (++hbTick >= 15) {
                    hbTick = 0;
                    appendDiag("hb", "server up · :" + Api.PORT);
                }
                // P31: AUTO-HIBERNATE. App in the background + no run active
                // (any chat) + no permission waiting + quiet past the
                // threshold → the sandbox stops ITSELF and RAM/battery go
                // back to the phone. Never while work is in flight — runs
                // outlive everything, that guarantee is untouched. Reopening
                // boots the sandbox and lands in the last chat (Resume).
                if (!hibernateAnnounced && state == ST_HEALTHY
                        && hibernateEnabled()) {
                    long due = hibernateDueAt();
                    if (due > 0 && System.currentTimeMillis() >= due) {
                        appendDiag("hibernate", "idle in background past "
                                + hibernateMinutes() + " min, no runs, no "
                                + "pending permissions — stopping the sandbox "
                                + "(chats live on disk; reopening resumes)");
                        wantSvc(appCtx, false);   // P50: opt-in sleep — the
                                                  // watchdog must NOT undo it
                        main.post(() -> {
                            stopServer();
                            setState(ST_STOPPED,
                                    "idle sleep — saved. Open the app to resume");
                            stopForeground(true);
                            stopSelf();
                        });
                        return;   // leave the supervisor loop quietly
                    }
                }
                if (state != ST_HEALTHY) {
                    try {
                        if (serverUp()) {
                            setState(ST_HEALTHY, "http 200 (late)");
                            updateNotif("running · " + cwd.getName());
                            // no startSse() here — the one SSE thread picks
                            // the healthy state up on its next loop pass
                        }
                    } catch (Throwable ignored) { }
                }
            }

            if (!died) return;          // user stop / service teardown

            // ---- death path (P18): diagnose, guard, respawn ----
            String why = nz(lastTailLine(), "no output");
            int exit = -1;
            try { exit = p.exitValue(); } catch (Exception ignored) {}
            attempts++;
            deaths[deathIdx++ % deaths.length] = System.currentTimeMillis();
            appendDiag("died", "exit=" + exit + " · " + why);

            int streak = Resilience.deathsInWindow(deaths, System.currentTimeMillis(), 10 * 60_000);
            if (streak >= 3) {
                appendDiag("give-up", streak + " deaths in 10 min");
                wantSvc(appCtx, false);   // P50: surrender stops the resurrection
                setState(ST_EXITED, "sandbox keeps dying (" + streak
                        + "× in 10 min) — ⌘ → Restart server");
                updateNotif("⚠ sandbox keeps dying — open OpenCode");
                return;                 // supervisor surrenders; manual restart re-arms
            }
            setState(ST_STARTING, "server died (exit " + exit + ") — auto-restarting");
        }
    }

    // ---- P31: auto-hibernate helpers (the pure rule lives in Hibernate)

    private boolean hibernateEnabled() {
        try {
            // P50 — default OFF. This pref was the background killer: with
            // the factory default ON, the sandbox stopped itself after
            // DEFAULT_MINUTES quiet background minutes ("the app cannot
            // work in background, it keeps closing after a little while").
            // The user flipped "Cool idle" (the WAKE LOCK switch — a
            // different feature) and the sandbox still died, because the
            // real stopper lived here. Background survival is now the
            // factory posture; hibernating is the opt-in.
            return getSharedPreferences("oc", MODE_PRIVATE)
                    .getBoolean("hibernate", HIBERNATE_DEFAULT);
        } catch (Exception e) {
            return HIBERNATE_DEFAULT;
        }
    }

    /** P50: the factory posture — background working is the feature,
     *  self-hibernation is the opt-in. JVM-pinned in P50Test. */
    static final boolean HIBERNATE_DEFAULT = false;

    private int hibernateMinutes() {
        try {
            return getSharedPreferences("oc", MODE_PRIVATE)
                    .getInt("hibernate_min", Hibernate.DEFAULT_MINUTES);
        } catch (Exception e) {
            return Hibernate.DEFAULT_MINUTES;
        }
    }

    /** When hibernation is due (epoch ms), 0 = not due / not applicable.
     *  The rule is pure (Hibernate.due) — the inputs are gathered here. */
    private long hibernateDueAt() {
        long bg = App.bgSince();
        if (bg <= 0) return 0;
        // P50: a pending QUESTION means the server is blocked on the user,
        // not idle — hibernating would kill the very turn the agent asked
        // for (same rule permissions already follow).
        boolean due = Hibernate.due(bg, System.currentTimeMillis(),
                Hibernate.minutesToMs(hibernateMinutes()),
                RunHub.busy(),
                pendingPermissions() > 0 || RunHub.pendingQuestionCount() > 0);
        return due ? System.currentTimeMillis() : 0;
    }

    private static String nz(String s, String fb) {
        return (s == null || s.isEmpty()) ? fb : s;
    }

    /** P50: re-read the eco_idle pref and reconcile the wake lock LIVE.
     *  The pref used to be read once at spawn — flipping "Cool idle"
     *  appeared dead until the next restart (one more silent no-op the
     *  user paid for). One pref read per 2 s watcher pass. */
    private void syncEcoIdle() {
        boolean pref;
        try {
            pref = getSharedPreferences("oc", MODE_PRIVATE)
                    .getBoolean("eco_idle", true);
        } catch (Exception e) {
            return;
        }
        if (pref == ecoIdle) return;
        ecoIdle = pref;
        appendDiag("eco", "cool idle now " + pref + " — wake lock "
                + (pref ? "follows runs only" : "always held"));
        if (!pref) acquireWakeLock();
        else if (!agentActive) releaseWakeLock();
    }

    /** Append one line to files/sandbox-diag.log (head-truncated at 24 kB).
     *  P18: “no Java crash file is written” was the field blocker — now
     *  every server death leaves exit code + last output + memory pressure
     *  on disk, so the NEXT report has ground truth. */
    /** Static twin of appendDiag for callers outside the service instance
     *  (Settings env-reset, App crash hook). Same 24 kB head-trim. */
    public static void appendDiagStatic(Context c, String event, String detail) {
        synchronized (DIAG_LOCK) {
            try {
                long mem = -1;
                try {
                    String mi = Api.readAll(new java.io.FileInputStream("/proc/meminfo"));
                    mem = Resilience.parseMemAvailableKb(mi);
                } catch (Exception ignored) {}
                File f = new File(c.getFilesDir(), "sandbox-diag.log");
                String line = Resilience.diagLine(System.currentTimeMillis(), event,
                        detail, mem) + "\n";
                if (f.length() > 24 * 1024) {
                    String old = Api.readAll(new java.io.FileInputStream(f));
                    int cut = Math.max(0, old.length() - 12 * 1024);
                    cut = old.indexOf('\n', cut);
                    if (cut > 0) line = old.substring(cut + 1) + line;
                }
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f,
                        f.length() <= 24 * 1024);
                fo.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fo.close();
            } catch (Exception ignored) {}
        }
    }

    private void appendDiag(String event, String detail) {
        synchronized (DIAG_LOCK) {
            try {
                long mem = -1;
                try {
                    String mi = Api.readAll(new java.io.FileInputStream("/proc/meminfo"));
                    mem = Resilience.parseMemAvailableKb(mi);
                } catch (Exception ignored) {}
                File f = new File(getFilesDir(), "sandbox-diag.log");
                String line = Resilience.diagLine(System.currentTimeMillis(), event, detail, mem) + "\n";
                if (f.length() > 24 * 1024) {
                    String old = Api.readAll(new java.io.FileInputStream(f));
                    int cut = Math.max(0, old.length() - 12 * 1024);
                    cut = old.indexOf('\n', cut);
                    if (cut > 0) line = old.substring(cut + 1) + line;
                }
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f, f.length() <= 24 * 1024);
                fo.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fo.close();
            } catch (Exception ignored) {}
        }
    }

    /** P19: kill every live process whose argv[0] is EXACTLY our opencode
     *  binary path — orphans from a killed app process (the field crash:
     *  the child outlives the app, keeps its port and its session store,
     *  and every fresh spawn then fights it). Exact-path match only, so
     *  no other app's processes can ever be touched. Returns count killed. */
    private static int sweepOrphans(String binPath) {
        int killed = 0;
        try {
            File[] dirs = new File("/proc").listFiles();
            if (dirs == null) return 0;
            int myPid = android.os.Process.myPid();
            for (File d : dirs) {
                int pid;
                try { pid = Integer.parseInt(d.getName()); } catch (Exception e) { continue; }
                if (pid == myPid) continue;
                String cl;
                try {
                    cl = Api.readAll(new java.io.FileInputStream(new File(d, "cmdline")));
                } catch (Exception e) { continue; }
                if (!Resilience.isOcCmdline(cl, binPath)) continue;
                try {
                    new ProcessBuilder("kill", "-9", String.valueOf(pid)).start().waitFor();
                    killed++;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return killed;
    }

    /** Kill a leftover process listening on Api.PORT (best effort). The
     *  zombie is our own uid's child, so the kill is permitted. Returns
     *  the pid killed, or 0. */
    private static int killStalePortOwner() {
        try {
            String hexPort = String.format("%04X", Api.PORT);
            String inode = null;
            for (String tbl : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
                String t;
                try { t = Api.readAll(new java.io.FileInputStream(tbl)); } catch (Exception e) { continue; }
                for (String line : t.split("\n")) {
                    String[] parts = line.trim().split("\\s+");
                    // sl local_address state ... inode
                    if (parts.length < 10) continue;
                    if (!parts[3].equalsIgnoreCase("0A")) continue;   // LISTEN
                    String local = parts[1];                           // ADDR:PORT
                    int c = local.lastIndexOf(':');
                    if (c < 0 || !local.substring(c + 1).equalsIgnoreCase(hexPort)) continue;
                    inode = parts[9];
                    break;
                }
                if (inode != null) break;
            }
            if (inode == null) return 0;
            File[] procDirs = new File("/proc").listFiles();
            if (procDirs == null) return 0;
            int myPid = android.os.Process.myPid();
            String needle = "socket:[" + inode + "]";
            for (File d : procDirs) {
                int pid;
                try { pid = Integer.parseInt(d.getName()); } catch (Exception e) { continue; }
                if (pid == myPid) continue;
                File fdDir = new File(d, "fd");
                File[] fds = fdDir.listFiles();
                if (fds == null) continue;
                for (File fd : fds) {
                    try {
                        String lk = android.system.Os.readlink(fd.getAbsolutePath());
                        if (needle.equals(lk)) {
                            final int target = pid;
                            try {
                                new ProcessBuilder("kill", "-9", String.valueOf(target)).start().waitFor();
                            } catch (Exception ignored) {}
                            return target;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /** Health = any known server endpoint answering. P7: /project is not
     *  guaranteed on every build, so /config/providers is accepted too. */
    private static boolean serverUp() {
        try { if (Api.status("/project", 2000) == 200) return true; } catch (Exception ignored) {}
        try { if (Api.status("/config/providers", 2000) == 200) return true; } catch (Exception ignored) {}
        try { if (Api.status("/doc", 2000) == 200) return true; } catch (Exception ignored) {}
        return false;
    }

    // ------------------------------------------------------------------ SSE

    /** P46: the stream governor (see PartGovernor). message.part.updated
     *  frames carry the WHOLE cumulative part text on every delta — on a
     *  phone the oc-sse thread drowned in quadratic JSON work on thinking
     *  models and the UI burst-fed. The governor applies at most one
     *  frame per part per interval, holds only the latest raw snapshot
     *  in between (lossless: every frame IS the full state), and force-
     *  flushes on message.updated / session.idle / session.error and on
     *  stream end. Instance field: one SSE loop per service lifetime. */
    private final PartGovernor gov = new PartGovernor();

    /** Throttle-aware dispatch of one raw SSE data frame (oc-sse thread). */
    private void dispatchRaw(String raw) {
        long now = System.currentTimeMillis();
        for (String chunk : gov.offer(raw, now)) ingestChunk(chunk, now);
    }

    /** Parse + ingest one raw frame, then deliver anything the boundary
     *  unblocked (message.updated / session.idle / session.error flush
     *  every held part — the turn's end must never wait on a timer). */
    private void ingestChunk(String raw, long now) {
        Map<String, Object> ev = Json.obj(Json.parse(raw));
        if (ev == null) return;
        ingest(ev);
        gov.noteType(Json.str(ev, "type"));
        for (String c : gov.drain(now, false)) {
            Map<String, Object> ev2 = Json.obj(Json.parse(c));
            if (ev2 != null) ingest(ev2);
        }
    }

    /** Drain everything held, regardless of interval. */
    private void flushGovernor() {
        long now = System.currentTimeMillis();
        for (String c : gov.drain(now, true)) {
            try {
                Map<String, Object> ev = Json.obj(Json.parse(c));
                if (ev != null) ingest(ev);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Owns /event for as long as the server lives. Parses data frames into
     * Maps and: (a) rebroadcasts to UI listeners, (b) queues permissions.
     */
    private void startSse() {
        final int gen;
        synchronized (SSE_LOCK) { gen = ++sseGen; }   // supersede any old owner
        Thread t = new Thread(() -> {
            while (RUNNING && gen == sseGen) {
                if (state != ST_HEALTHY) {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                    continue;
                }
                HttpURLConnection c = null;
                try {
                    c = Api.open("GET", "/event", null, 0);
                    c.setRequestProperty("Accept", "text/event-stream");
                    if (c.getResponseCode() != 200) {
                        c.disconnect();
                        Thread.sleep(3000);
                        continue;
                    }
                    sseConn = c;
                    // P4: ask the server for anything pending RIGHT NOW —
                    // recovers asks that fired before connect / during a
                    // reconnect gap, and un-stalls agents that asked while
                    // the app was dead. Safe: queue is id-deduped.
                    seedPermissions();
                    StringBuilder data = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream()), 32 * 1024)) {
                        String line;
                        while (RUNNING && gen == sseGen && (line = r.readLine()) != null) {
                            if (line.startsWith("data:")) {
                                String d = line.length() > 5 ? line.substring(5).trim() : "";
                                if (data.length() > 0) data.append('\n');
                                data.append(d);
                            } else if (line.isEmpty() && data.length() > 0) {
                                String raw = data.toString();
                                data.setLength(0);
                                dispatchRaw(raw);
                            }
                        }
                    }
                } catch (Throwable e) {
                    // P23: reconnect below — the SSE loop must survive
                    // anything (Errors included); a dead feed was one of
                    // the ways a run went silent on the field device.
                } finally {
                    sseConn = null;
                    if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
                    // P46: the feed ended (clean drop or reset) — deliver
                    // every held frame before the reconnect sleep so the
                    // governor can never swallow a part's final state.
                    try { flushGovernor(); } catch (Throwable ignored) {}
                }
                if (RUNNING && gen == sseGen) {
                    try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
                }
            }
        }, "oc-sse");
        t.setDaemon(true);
        sseThread = t;
        t.start();
    }

    /** Global event processing: permissions + activity gating + rebroadcast. */
    private void ingest(Map<String, Object> ev) {
        String type = Json.str(ev, "type");

        // P17 eco idle: agent activity holds the wake lock; idle releases it.
        if ("session.idle".equals(type)) {
            noteIdle();
        } else if (type != null && (type.startsWith("message.")
                || type.startsWith("permission.")
                || "session.error".equals(type))) {
            noteActivity();
        }

        if ("permission.asked".equals(type) || "permission.updated".equals(type)
                || "permission.v2.asked".equals(type)
                || "permission.v2.updated".equals(type)) {
            Map<String, Object> perm = normalizePermission(Json.map(ev, "properties"));
            if (perm != null) {
                String id = Json.str(perm, "id");
                if (markSeenPerm(id)) {
                    PERMS.add(perm);
                    if (evtListeners.isEmpty()) {
                        updateNotif("⚠ permission requested — open OpenCode to review");
                    }
                }
            }
        } else if ("permission.replied".equals(type)
                || "permission.v2.replied".equals(type)) {
            // answered elsewhere (e.g. server auto-mode) → keep our queue in sync
            Map<String, Object> props = Json.map(ev, "properties");
            String rid = props == null ? null : Json.str(props, "requestID");
            if (rid != null) noteAnswered(rid);
        }

        if (!evtListeners.isEmpty()) {
            main.post(() -> {
                for (EventListener l : evtListeners) {
                    try { l.onEvent(ev); } catch (Throwable ignored) { }
                }
            });
        }
    }

    /**
     * v1.18.x verified from the shipped binary: permission.asked properties
     * ARE the request — {id, sessionID, permission:"bash", patterns:[...],
     * metadata:{...}, always:[...], tool?{messageID,callID}}. Older builds
     * nested the object under properties.permission — accept both.
     */
    private static Map<String, Object> normalizePermission(Map<String, Object> props) {
        if (props == null) return null;
        Map<String, Object> nested = Json.map(props, "permission");
        if (nested != null && Json.str(nested, "id") != null) return nested;
        if (Json.str(props, "id") != null) return props;
        return null;
    }

    /** GET /permission (v1) — and v2 /api/permission/request as fallback —
     *  server's own list of pending permission requests. */
    private void seedPermissions() {
        try {
            Api.Resp r = Api.get("/permission");
            if (!r.ok()) {
                // v2 surface (shipped binary): GET /api/permission/request
                try {
                    Api.Resp r2 = Api.get("/api/permission/request");
                    if (r2.ok()) r = r2;
                } catch (Exception ignored) {}
            }
            if (!r.ok()) return;
            List<Object> arr = Json.arr(Json.parse(r.body));
            if (arr == null) return;
            boolean added = false;
            for (Object o : arr) {
                Map<String, Object> perm = normalizePermission(Json.obj(o));
                String id = perm == null ? null : Json.str(perm, "id");
                if (markSeenPerm(id)) {
                    PERMS.add(perm);
                    added = true;
                }
            }
            if (added && evtListeners.isEmpty()) {
                updateNotif("⚠ permission requested — open OpenCode to review");
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------- lifecycle

    private static void acquireWakeLock() {
        synchronized (WAKE_LOCK) {
            if (wakeLock != null) return;
            Context c = appCtx;
            if (c == null) return;
            try {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:server");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            } catch (Exception ignored) {}
        }
    }

    private static void releaseWakeLock() {
        synchronized (WAKE_LOCK) {
            if (wakeLock != null) {
                try { wakeLock.release(); } catch (Exception ignored) {}
                wakeLock = null;
            }
        }
    }

    // ------------------------------------------------ P50: keep-alive core

    /**
     * The WANT flag — persisted, process-death-proof. True from spawn
     * until a deliberate stop (user stop, opt-in hibernate, crash-loop
     * give-up). The watchdog reads it after an OEM kill: if the sandbox
     * is still wanted, the service boots again. This is the difference
     * between “Android MAY restart me” (START_STICKY, postponed forever
     * under Doze) and “the app puts itself back” (an allow-while-idle
     * alarm the system honors).
     */
    static void wantSvc(Context c, boolean v) {
        try {
            c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .edit().putBoolean("svc_want", v).apply();
        } catch (Exception ignored) {}
    }

    static boolean svcWant(Context c) {
        try {
            return c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getBoolean("svc_want", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Arm (or re-arm) the keep-alive alarm chain. allow-while-idle so a
     *  Dozed device still fires it; every fire re-arms the next tick while
     *  the keep-alive pref holds. exact-alarms fall back to windowed ones
     *  without the exact-alarm permission — cadence, not precision, is
     *  what matters here. */
    static void scheduleWatchdog(Context c) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager)
                    c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    c, 1001, new Intent(c, WatchdogReceiver.class),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                            | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            long at = System.currentTimeMillis() + WatchdogReceiver.INTERVAL_MS;
            try {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,
                        at, pi);
            } catch (Exception exactFail) {
                try {
                    am.setWindow(android.app.AlarmManager.RTC_WAKEUP,
                            at, 10 * 60_000L, pi);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** P52: tear the chain down when the user no longer wants it (keep-
     *  alive off) or the sandbox is deliberately stopped (svcWant false).
     *  Without this the allow-while-idle alarm fired forever, draining
     *  battery after the feature was disabled. Same request code + Intent
     *  as scheduleWatchdog so the PendingIntent matches; FLAG_NO_CREATE
     *  avoids resurrecting an alarm that is already gone. */
    static void cancelWatchdog(Context c) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager)
                    c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    c, 1001, new Intent(c, WatchdogReceiver.class),
                    android.app.PendingIntent.FLAG_NO_CREATE
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (pi == null) return;
            am.cancel(pi);
            pi.cancel();
        } catch (Exception ignored) {}
    }

    /** P50: a question needs the user — surface it on the notification
     *  (the sandbox is blocked on an answer; the run is NOT idle).
     *  Static + null-safe: the hub fires it from any process state. */
    static void noteQuestionPending() {
        Context c = appCtx;
        if (c == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    NotificationManager nm = (NotificationManager)
                            c.getSystemService(NOTIFICATION_SERVICE);
                    if (nm == null) return;
                    android.app.Notification n = buildNotifStatic(c,
                            "⏸ question needs your answer — the agent is waiting");
                    nm.notify(NOTIF_ID, n);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /** The ask was answered/rejected — restore the running notice. */
    static void noteQuestionAnswered() {
        Context c = appCtx;
        if (c == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    NotificationManager nm = (NotificationManager)
                            c.getSystemService(NOTIFICATION_SERVICE);
                    if (nm == null) return;
                    File d = servingDir;
                    nm.notify(NOTIF_ID, buildNotifStatic(c,
                            "running · " + (d != null ? d.getName() : "sandbox")));
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /** Static twin of buildNotif (noteQuestion* fire from appCtx without
     *  a live service instance). Same actions: open + battery help. */
    private static android.app.Notification buildNotifStatic(Context c, String text) {
        Intent open = new Intent(c, MainActivity.class);
        android.app.PendingIntent pOpen = android.app.PendingIntent.getActivity(
                c, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE
                        | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification.Builder b = new android.app.Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("OpenCode server")
                .setContentText(text)
                .setContentIntent(pOpen)
                .setOngoing(true);
        // P50: when the OS still optimizes the battery away, the single
        // most useful tap on this notice is the exemption dialog.
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            boolean exempt = pm != null
                    && pm.isIgnoringBatteryOptimizations(c.getPackageName());
            if (!exempt) {
                android.app.PendingIntent pBatt = android.app.PendingIntent.getActivity(
                        c, 2, new Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + c.getPackageName())),
                        android.app.PendingIntent.FLAG_IMMUTABLE);
                b.addAction(new android.app.Notification.Action.Builder(
                        null, "Allow background run", pBatt).build());
            }
        } catch (Exception ignored) {}
        return b.build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // P50: a swipe-away must not end the sandbox. Most OEM skins kill
        // the PROCESS on swipe (the notification is gone with it); the
        // queued start is ignored for a dead process — so re-arm the
        // watchdog chain too: the next tick resurrects the service with
        // its notification. userStop stays respected (a Stop is a stop).
        if (!userStop && svcWant(this)) {
            try {
                startService(new Intent(this, ServerService.class));
            } catch (Exception ignored) {}
            scheduleWatchdog(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void stopServer() {
        RUNNING = false;
        servingDir = null;
        HttpURLConnection c = sseConn;
        if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        // P52: supersede + reap the SSE owner so a fast restart can never
        // leave two threads ingesting /event (double-delivered events).
        synchronized (SSE_LOCK) { sseGen++; }
        Thread st = sseThread;
        sseThread = null;
        if (st != null) {
            st.interrupt();
            try { st.join(1000); } catch (InterruptedException ignored) {}
        }
        Process p = proc;
        proc = null;
        if (p != null) {
            p.destroy();
            // P52: SIGTERM may be ignored by a wedged child; escalate after
            // a short grace. Direct child only — killing the process GROUP
            // would kill this app (same pgid), so that is deliberately NOT
            // done (see report).
            try {
                if (!p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                    p.destroyForcibly();
            } catch (InterruptedException ignored) {
                try { p.destroyForcibly(); } catch (Exception ignored2) {}
            }
        }
        Thread r = runner;
        if (r != null) r.interrupt();
        runner = null;
        releaseWakeLock();
        RenderServer.stop();   // P35: the endpoint dies with the service
        agentActive = false;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    private String lastTailLine() {
        synchronized (tail) {
            String t = tail.toString().trim();
            int i = t.lastIndexOf('\n');
            return (i >= 0 ? t.substring(i + 1) : t);
        }
    }

    private Notification buildNotif(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("OpenCode server")
                .setContentText(text)
                .setContentIntent(pOpen)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "Stop", pStop).build());
        // P50: no battery exemption yet → the notice carries the one-tap
        // fix. This is the kill-proof that survives every in-app surface.
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean exempt = pm != null
                    && pm.isIgnoringBatteryOptimizations(getPackageName());
            if (!exempt) {
                PendingIntent pBatt = PendingIntent.getActivity(this, 2,
                        new Intent(android.provider.Settings
                                        .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())),
                        PendingIntent.FLAG_IMMUTABLE);
                b.addAction(new Notification.Action.Builder(
                        null, "Allow background run", pBatt).build());
            }
        } catch (Exception ignored) {}
        return b.build();
    }

    private void updateNotif(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotif(text));
        } catch (Throwable t) {
            // P23: a notification hiccup must never take the supervisor down
            Trail.record(this, "notification", t);
        }
    }
}
