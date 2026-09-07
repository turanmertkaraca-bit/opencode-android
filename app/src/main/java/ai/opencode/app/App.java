package ai.opencode.app;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * P7: global crash capture. The user's device reported "going into chat
 * crashes the app" with no way to hand me the stack — so from now on every
 * uncaught exception is written to files/last-crash.txt and surfaced on the
 * next boot (MainActivity) and in Diagnostics.
 */
public class App extends Application {

    // P12: top-of-stack activity — lets the QA bridge capture the app's own
    // window ("shot" command) without any capture permission.
    private static volatile android.app.Activity TOP;

    // P31: when the whole app went to the background (last activity
    // paused). 0 = foreground. The auto-hibernate watchdog reads this.
    private static volatile long bgSince;

    /** Epoch ms of the last foreground→background transition, 0 if the
     *  app is (or came back) in the foreground. */
    public static long bgSince() { return bgSince; }

    @Override
    public void onCreate() {
        super.onCreate();
        // P27: the design system reads its prefs (AMOLED pure black default)
        // ONCE at process start — every screen, drawable and markdown link
        // renders from the same palette from the first frame.
        Theme.apply(this);
        // P25: the run engine exists for the WHOLE process lifetime — it
        // owns the transcript, busy state, send orchestration and the
        // live-edit feed, so a running agent turn never depends on any
        // screen. Subscribes itself to the service's SSE feed here.
        RunHub.init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(android.app.Activity a) {
                TOP = a;
                bgSince = 0;                    // P31: back in the foreground
            }
            @Override public void onActivityPaused(android.app.Activity a) {
                if (TOP == a) {
                    TOP = null;
                    bgSince = System.currentTimeMillis();   // P31
                }
            }
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityStarted(android.app.Activity a) {}
            @Override public void onActivityStopped(android.app.Activity a) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle out) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                File f = crashFile(App.this);
                try (OutputStream o = new FileOutputStream(f)) {
                    o.write(("thread: " + t.getName() + "\ntime: "
                            + new java.util.Date() + "\n\n" + sw)
                            .getBytes("UTF-8"));
                }
                // P23: name the crash IN the incident log the field reports
                // paste. The exit-forensics row only ever said "crash" —
                // now the sandbox log carries WHAT threw and WHERE, even
                // if last-crash.txt itself never reaches us.
                try {
                    long mem = -1;
                    try {
                        mem = Resilience.parseMemAvailableKb(Api.readAll(
                                new java.io.FileInputStream("/proc/meminfo")));
                    } catch (Throwable ignored) {}
                    File d = new File(getFilesDir(), "sandbox-diag.log");
                    StringBuilder b = new StringBuilder();
                    b.append(Resilience.diagLine(System.currentTimeMillis(),
                            "app-crash",
                            "thread " + t.getName() + " · "
                                    + Resilience.guardLine(0, "uncaught", e)
                                            .replaceFirst("^[0-9]+ · ", ""),
                            mem)).append('\n');
                    try (OutputStream o = new FileOutputStream(d, true)) {
                        o.write(b.toString().getBytes("UTF-8"));
                    }
                } catch (Throwable ignored) {}
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
        // P30 upgrade migration: devices that toggled the terse style under
        // P29 carry the app's MANAGED BLOCK inside the project's AGENTS.md —
        // a chat style sitting in a file that git diffs and other sessions
        // read as project rules. P30 keeps the style purely in-app, so any
        // block we own is stripped from every known project ONCE per boot
        // (strip is pure, idempotent, byte-preserving for user content;
        // files without the block are never written). Off the main thread,
        // Throwable-guarded — the migration can never delay or break boot.
        final Thread mig = new Thread(() -> {
            Throwable t2 = Resilience.guard(() -> stripTerseBlocks(App.this));
            if (t2 != null) Trail.record(App.this, "p30 agents migration", t2);
        }, "p30-agents-migration");
        mig.setPriority(Thread.MIN_PRIORITY);
        mig.start();
    }

    /** P30: remove the app's terse managed block from every known
     *  project's AGENTS.md. Quiet no-op when the block is absent. Each
     *  strip is recorded in the incident log so Diagnostics can show
     *  exactly what the upgrade cleaned up (and that user content was
     *  preserved). */
    private static void stripTerseBlocks(Context c) {
        java.util.List<Projects.P> ps = Projects.list(c);
        for (Projects.P p : ps) {
            if (p.path == null || p.path.isEmpty()) continue;
            try {
                File f = new File(p.path, "AGENTS.md");
                if (!f.isFile() || !f.canWrite()) continue;
                String cur = Api.readAll(new java.io.FileInputStream(f));
                if (!TerseMode.isOn(cur)) continue;      // never touches clean files
                String out = TerseMode.strip(cur);
                File tmp = new File(p.path, "AGENTS.md.part");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(out.getBytes("UTF-8"));
                }
                if (!f.delete() || !tmp.renameTo(f))
                    throw new java.io.IOException("swap failed");
                long mem = -1;
                try {
                    mem = Resilience.parseMemAvailableKb(Api.readAll(
                            new java.io.FileInputStream("/proc/meminfo")));
                } catch (Throwable ignored) {}
                StringBuilder b = new StringBuilder();
                b.append(Resilience.diagLine(System.currentTimeMillis(),
                        "p30-migration",
                        "stripped terse managed block from " + p.path
                                + "/AGENTS.md (user content preserved)",
                        mem)).append('\n');
                File d = new File(c.getFilesDir(), "sandbox-diag.log");
                try (OutputStream o2 = new FileOutputStream(d, true)) {
                    o2.write(b.toString().getBytes("UTF-8"));
                }
            } catch (Exception ignored) {
                // one unreadable/unwritable project never blocks the others
            }
        }
    }

    /** Currently resumed activity, or null. */
    public static android.app.Activity top() { return TOP; }

    public static File crashFile(Context c) {
        return new File(c.getFilesDir(), "last-crash.txt");
    }
}
