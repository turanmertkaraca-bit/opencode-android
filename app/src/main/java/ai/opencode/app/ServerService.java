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
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CH, "OpenCode server", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
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
        pendingRestart = false;
        setState(ST_STARTING, "spawning opencode serve");
        runner = new Thread(() -> runServer(bin), "oc-server");
        runner.setDaemon(false);
        runner.start();
        return START_STICKY;
    }

    private void runServer(File bin) {
        try {
            Binaries.makeExec(bin); // idempotent; P0-verified pattern
        } catch (Exception e) {
            setState(ST_EXITED, "chmod failed: " + e.getMessage());
            updateNotif("exec setup failed");
            stopSelf();
            return;
        }

        acquireWakeLock();

        // P8: the project card decides the sandbox root. Everything the
        // agent touches (sessions, file tools, shell cwd) is scoped to it.
        File cwd = (startDir != null && startDir.isDirectory())
                ? startDir : Binaries.homeDir(this);
        servingDir = cwd;
        setState(ST_STARTING, "sandbox: " + cwd.getName());

        ProcessBuilder pb = new ProcessBuilder(
                bin.getAbsolutePath(), "serve",
                "--port", String.valueOf(Api.PORT),
                "--hostname", Api.HOST);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        Binaries.applyEnv(this, pb);

        try {
            proc = pb.start();
        } catch (Exception e) {
            setState(ST_EXITED, "spawn failed: " + e.getMessage());
            updateNotif("spawn failed");
            stopSelf();
            return;
        }

        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()), 16 * 1024)) {
                String line;
                while ((line = r.readLine()) != null) addTail(line);
            } catch (Exception ignored) {}
        }, "oc-drain");
        drain.setDaemon(true);
        drain.start();

        setState(ST_STARTING, "opencode serve pid=" + proc.hashCode());

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 240; i++) { // up to 120 s
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            Process p = proc;
            if (p == null || !p.isAlive()) {
                setState(ST_EXITED, "server exited early · " + lastTailLine());
                updateNotif("server exited");
                stopSelf();
                return;
            }
            try {
                if (serverUp()) {
                    long ms = System.currentTimeMillis() - t0;
                    setState(ST_HEALTHY, "http 200 in " + ms + " ms");
                    updateNotif("running · " + cwd.getName());
                    break;
                }
            } catch (Exception ignored) {}
        }

        if (state == ST_HEALTHY) startSse();

        // watcher: crash detection + late health
        while (RUNNING) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            Process p = proc;
            if (p == null || !p.isAlive()) {
                setState(ST_EXITED, "server exited · " + lastTailLine());
                updateNotif("server exited");
                stopSelf();
                return;
            }
            if (state != ST_HEALTHY) {
                try {
                    if (serverUp()) {
                        setState(ST_HEALTHY, "http 200 (late)");
                        updateNotif("running · " + cwd.getName());
                        startSse();
                    }
                } catch (Exception ignored) {}
            }
        }
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

    /** Global event processing: permissions + rebroadcast. */
    private void ingest(Map<String, Object> ev) {
        String type = Json.str(ev, "type");

        if ("permission.asked".equals(type) || "permission.updated".equals(type)) {
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
        } else if ("permission.replied".equals(type)) {
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

    /** GET /permission — server's own list of pending permission requests. */
    private void seedPermissions() {
        try {
            Api.Resp r = Api.get("/permission");
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

    private void acquireWakeLock() {
        if (wakeLock != null) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {}
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
        if (wakeLock != null) {
            try { wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }
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
