package ai.opencode.app;

import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P16 — the live-directory engine behind Files ("lets me see the real
 * time changes that are being made — that would be soo cool if done
 * right").
 *
 * A stack of framework FileObservers (inotify), made recursive by hand:
 * watching a directory also watches every (non-hidden) subdirectory it
 * already contains, and new subdirectories get their own observer the
 * moment a CREATE event lands. Caps keep a runaway tree (node_modules…)
 * from eating the phone: max depth 6, max 220 observers, hidden dirs
 * skipped.
 *
 * Events are coalesced on a handler with a short debounce so an agent
 * burst (edit + save + rename…) collapses into ONE listener callback per
 * 280 ms — the same trick the chat's paint coalescing uses (P15), tuned
 * for the file system. The debounce schedules on the FIRST event of a
 * burst (never resets), so continuous writes still deliver.
 *
 * classify() is deliberately a pure static function so the JVM test
 * suite can pin the event→action mapping without a device.
 */
public final class DirWatcher {

    public interface Listener {
        /** path = absolute path that changed; action = new | mod | del. */
        void onChange(String path, String action);
    }

    // android.os.FileObserver's event constants are non-static instance
    // members on Android (only FileObserver subclasses see them) — mirror
    // the stable inotify-derived values here so static code paths (and the
    // JVM tests) can use them. Values identical to the framework's.
    public static final int EV_ACCESS      = 0x00000001;
    public static final int EV_CLOSE_WRITE = 0x00000008;
    public static final int EV_MOVED_FROM  = 0x00000040;
    public static final int EV_MOVED_TO    = 0x00000080;
    public static final int EV_CREATE      = 0x00000100;
    public static final int EV_DELETE      = 0x00000200;
    public static final int EV_DELETE_SELF = 0x00000400;
    public static final int EV_MOVED_SELF  = 0x00000800;

    private final Handler h;
    private final Listener listener;
    private final Map<String, FileObserver> obs = new LinkedHashMap<>();
    /** pending[path] = action — later events for the same path overwrite. */
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Runnable fire = this::fireNow;
    private boolean running;

    private static final int MAX_DEPTH = 6;
    private static final int MAX_DIRS = 220;
    static final long DEBOUNCE_MS = 280;
    private static final int MASK = EV_CREATE | EV_DELETE | EV_CLOSE_WRITE
            | EV_MOVED_FROM | EV_MOVED_TO | EV_DELETE_SELF | EV_MOVED_SELF;

    public DirWatcher(Looper looper, Listener listener) {
        this.h = new Handler(looper);
        this.listener = listener;
    }

    /** (Re)start watching root recursively. Replaces any previous watch. */
    public synchronized void start(File root) {
        stop();
        running = true;
        if (root != null && root.isDirectory()) add(root, 0);
    }

    public synchronized void stop() {
        running = false;
        for (FileObserver fo : obs.values()) {
            try { fo.stopWatching(); } catch (Exception ignored) {}
        }
        obs.clear();
        pending.clear();
        h.removeCallbacks(fire);
    }

    public synchronized boolean isRunning() { return running && !obs.isEmpty(); }

    public synchronized int watchedDirs() { return obs.size(); }

    private void add(File dir, int depth) {
        if (!running || dir == null || depth > MAX_DEPTH) return;
        String canonical;
        try { canonical = dir.getCanonicalPath(); } catch (Exception e) { canonical = dir.getAbsolutePath(); }
        final String path = canonical;
        if (obs.containsKey(path) || obs.size() >= MAX_DIRS) return;

        FileObserver fo = new FileObserver(path, MASK) {
            @Override
            public void onEvent(int event, String child) {
                if (child == null) return;
                int ev = event & 0xFFFF;
                if ((ev & (EV_DELETE_SELF | EV_MOVED_SELF)) != 0) {
                    h.post(() -> release(path));
                    return;
                }
                File f = new File(path, child);
                String action = classify(ev);
                if (action == null) return;
                if ((ev & EV_CREATE) != 0 && f.isDirectory()) {
                    h.post(() -> add(f, depth + 1));
                }
                if ((ev & (EV_DELETE | EV_MOVED_FROM)) != 0) {
                    h.post(() -> releaseDescendants(f.getAbsolutePath()));
                }
                note(f.getAbsolutePath(), action);
            }
        };
        obs.put(path, fo);
        fo.startWatching();

        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory() && !k.getName().startsWith(".")) add(k, depth + 1);
            }
        }
    }

    /** Package-private so the JVM tests can drive the debounce directly
     *  (Robolectric's FileObserver shadow does not watch real files). */
    synchronized void note(String path, String action) {
        if (!running) return;
        boolean wasEmpty = pending.isEmpty();
        pending.put(path, action);
        // Schedule on the FIRST event of a burst, not every event — an
        // agent streaming many writes must never starve delivery; this
        // fires at most one batch per DEBOUNCE_MS while bursts continue.
        if (wasEmpty) {
            h.removeCallbacks(fire);
            h.postDelayed(fire, DEBOUNCE_MS);
        }
    }

    private void fireNow() {
        Map<String, String> batch;
        synchronized (this) {
            if (pending.isEmpty()) return;
            batch = new HashMap<>(pending);
            pending.clear();
        }
        for (Map.Entry<String, String> e : batch.entrySet()) {
            try { listener.onChange(e.getKey(), e.getValue()); } catch (Exception ignored) {}
        }
    }

    private synchronized void release(String path) {
        FileObserver fo = obs.remove(path);
        if (fo != null) try { fo.stopWatching(); } catch (Exception ignored) {}
    }

    private void releaseDescendants(String prefix) {
        synchronized (this) {
            for (String p : obs.keySet().toArray(new String[0])) {
                if (p.startsWith(prefix + "/")) {
                    FileObserver fo = obs.remove(p);
                    if (fo != null) try { fo.stopWatching(); } catch (Exception ignored) {}
                }
            }
        }
    }

    /** Pure: event mask → the badge the UI shows. Null = ignore. */
    public static String classify(int ev) {
        if ((ev & (EV_DELETE | EV_MOVED_FROM | EV_DELETE_SELF | EV_MOVED_SELF)) != 0) return "del";
        if ((ev & (EV_CREATE | EV_MOVED_TO)) != 0) return "new";
        if ((ev & EV_CLOSE_WRITE) != 0) return "mod";
        return null;
    }
}
