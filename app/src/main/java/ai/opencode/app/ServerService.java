package ai.opencode.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
    private static final StringBuilder tail = new StringBuilder();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static PowerManager.WakeLock wakeLock;

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
        if (ecoIdle) releaseWakeLock();
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
    private static final Set<String> seenPermIds = ConcurrentHashMap.newKeySet();
    /** Request ids we already answered — tombstones so a re-seed/refresh can
     *  never re-queue a stale ask and flap the dialog forever. */
    private static final Set<String> answeredPermIds = ConcurrentHashMap.newKeySet();

    public static int getState() { return state; }
    public static String getTail() { synchronized (tail) { return tail.toString(); } }
    public static boolean healthy() { return state == ST_HEALTHY; }

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
                catch (Exception ignored) {}
            }
        }, 1200);
    }

    public static void subscribe(Evt e) { listeners.add(e); }
    public static void unsubscribe(Evt e) { listeners.remove(e); }
    public static void subscribeEvents(EventListener e) { evtListeners.add(e); }
    public static void unsubscribeEvents(EventListener e) { evtListeners.remove(e); }

    /** Head of the pending permission queue (null if none). */
    public static Map<String, Object> peekPermission() { return PERMS.peek(); }
    /** Remove the head permission (after it has been answered). */
    public static void dropPermission() { PERMS.poll(); }
    /** Tombstone a request id and purge it from the queue (after the reply
     *  POST — covers both the dialog path and permission.replied events). */
    public static void noteAnswered(String id) {
        if (id == null) return;
        answeredPermIds.add(id);
        if (answeredPermIds.size() > 256) answeredPermIds.clear();
        for (java.util.Iterator<Map<String, Object>> it = PERMS.iterator(); it.hasNext(); ) {
            if (id.equals(Json.str(it.next(), "id"))) it.remove();
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
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CH, "OpenCode server", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            userStop = true;
            stopServer();
            setState(ST_STOPPED, "stopped");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotif("starting…"));
        if (runner != null && runner.isAlive()) {
            setState(state, "already running");
            return START_STICKY;
        }
        final File bin = Binaries.binaryFile(this);
        if (!bin.exists() || !Binaries.isElf(bin)) {
            setState(ST_EXITED, "no valid binary imported");
            updateNotif("no binary imported");
            stopSelf();
            return START_NOT_STICKY;
        }
        RUNNING = true;
        // P18: a plain start (boot, screen open, restart()'s delayed relaunch)
        // always re-arms the supervisor — the ACTION_STOP intent that
        // restart() queues first would otherwise leave userStop=true here
        // and the server would never spawn.
        userStop = false;
        pendingRestart = false;
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
        Binaries.applyEnv(this, pb);

        long[] deaths = new long[8];        // recent death timestamps (ring)
        int deathIdx = 0;
        int attempts = 0;                   // consecutive auto-restarts

        startSse();   // ONE SSE owner thread for every spawn of this service

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
                } catch (Exception ignored) {}
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
                            break;
                        }
                    } catch (Exception ignored) {}
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
            while (RUNNING && !userStop) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                if (!p.isAlive()) { died = true; break; }
                if (++hbTick >= 15) {
                    hbTick = 0;
                    appendDiag("hb", "server up · :" + Api.PORT);
                }
                if (state != ST_HEALTHY) {
                    try {
                        if (serverUp()) {
                            setState(ST_HEALTHY, "http 200 (late)");
                            updateNotif("running · " + cwd.getName());
                            // no startSse() here — the one SSE thread picks
                            // the healthy state up on its next loop pass
                        }
                    } catch (Exception ignored) {}
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
                setState(ST_EXITED, "sandbox keeps dying (" + streak
                        + "× in 10 min) — ⌘ → Restart server");
                updateNotif("⚠ sandbox keeps dying — open OpenCode");
                return;                 // supervisor surrenders; manual restart re-arms
            }
            setState(ST_STARTING, "server died (exit " + exit + ") — auto-restarting");
        }
    }

    private static String nz(String s, String fb) {
        return (s == null || s.isEmpty()) ? fb : s;
    }

    /** Append one line to files/sandbox-diag.log (head-truncated at 24 kB).
     *  P18: “no Java crash file is written” was the field blocker — now
     *  every server death leaves exit code + last output + memory pressure
     *  on disk, so the NEXT report has ground truth. */
    private void appendDiag(String event, String detail) {
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

    /**
     * Owns /event for as long as the server lives. Parses data frames into
     * Maps and: (a) rebroadcasts to UI listeners, (b) queues permissions.
     */
    private void startSse() {
        Thread t = new Thread(() -> {
            while (RUNNING) {
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
                        while (RUNNING && (line = r.readLine()) != null) {
                            if (line.startsWith("data:")) {
                                String d = line.length() > 5 ? line.substring(5).trim() : "";
                                if (data.length() > 0) data.append('\n');
                                data.append(d);
                            } else if (line.isEmpty() && data.length() > 0) {
                                Map<String, Object> ev = Json.obj(Json.parse(data.toString()));
                                data.setLength(0);
                                if (ev != null) ingest(ev);
                            }
                        }
                    }
                } catch (Exception e) {
                    // reconnect below
                } finally {
                    sseConn = null;
                    if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
                }
                if (RUNNING) {
                    try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
                }
            }
        }, "oc-sse");
        t.setDaemon(true);
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
                if (id != null && !answeredPermIds.contains(id) && seenPermIds.add(id)) {
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
                    try { l.onEvent(ev); } catch (Exception ignored) {}
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
                if (id != null && !answeredPermIds.contains(id) && seenPermIds.add(id)) {
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

    private static void releaseWakeLock() {
        if (wakeLock != null) {
            try { wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }
    }

    private void stopServer() {
        RUNNING = false;
        servingDir = null;
        HttpURLConnection c = sseConn;
        if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        Process p = proc;
        proc = null;
        if (p != null) p.destroy();
        Thread r = runner;
        if (r != null) r.interrupt();
        releaseWakeLock();
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
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("OpenCode server")
                .setContentText(text)
                .setContentIntent(pOpen)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "Stop", pStop).build())
                .build();
    }

    private void updateNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotif(text));
    }
}
