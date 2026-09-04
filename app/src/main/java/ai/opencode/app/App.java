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

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(android.app.Activity a) { TOP = a; }
            @Override public void onActivityPaused(android.app.Activity a) {
                if (TOP == a) TOP = null;
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
    }

    /** Currently resumed activity, or null. */
    public static android.app.Activity top() { return TOP; }

    public static File crashFile(Context c) {
        return new File(c.getFilesDir(), "last-crash.txt");
    }
}
