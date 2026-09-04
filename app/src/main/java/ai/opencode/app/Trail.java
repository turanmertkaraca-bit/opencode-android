package ai.opencode.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * P23 — the blast-radius log (files/guard-trail.log).
 *
 * Every failure the app CONTAINS instead of crashing lands here: one
 * bounded, greppable line per event (ts · what · class[: message] · top
 * app frame — same convention as sandbox-diag.log). Before P23 the send
 * path's catch(Exception) guards left Errors a clear shot at the process:
 * the field device died on send (exit reason CRASH, 1 kB last-crash.txt)
 * while every audited stage "had a try". From P23 on a Throwable anywhere
 * on the send/chat/feed paths is contained, recorded here, and shown in
 * ⌘ → Logs & shell with a copy button — the trace the field could never
 * paste is now one tap away, and the app keeps running.
 *
 * The record() family NEVER throws and NEVER re-enters the failing
 * subsystem (no dialogs, no toasts, no sys() rows from here).
 */
public final class Trail {

    private Trail() {}

    private static final Object LOCK = new Object();
    private static final int CAP = 24 * 1024;
    private static final int KEEP = 12 * 1024;

    /** Rotation cap (bytes) — package-private for the JVM tests. */
    static int capBytes() { return CAP; }

    public static File file(Context c) {
        return new File(c.getFilesDir(), "guard-trail.log");
    }

    /** Append one contained-failure line. Never throws. */
    public static void record(Context c, String what, Throwable t) {
        if (c == null) return;
        try { record(file(c), what, t); } catch (Throwable ignored) {}
    }

    /** File-based core (JVM-testable): append with tail rotation. */
    public static void record(File f, String what, Throwable t) {
        if (f == null) return;
        try {
            String line = Resilience.guardLine(System.currentTimeMillis(), what, t) + "\n";
            byte[] bytes = line.getBytes("UTF-8");
            synchronized (LOCK) {
                if (f.length() > CAP) {
                    String old = Api.readAll(new FileInputStream(f));
                    int cut = Math.max(0, old.length() - KEEP);
                    cut = old.indexOf('\n', cut);
                    String kept = cut > 0 ? old.substring(cut + 1) : "";
                    FileOutputStream fo = new FileOutputStream(f, false);
                    fo.write(kept.getBytes("UTF-8"));
                    fo.write(bytes);
                    fo.close();
                } else {
                    FileOutputStream fo = new FileOutputStream(f, true);
                    fo.write(bytes);
                    fo.close();
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Full contents for Diagnostics (bounded read). Never throws. */
    public static String read(Context c) {
        if (c == null) return "";
        try {
            File f = file(c);
            if (!f.isFile()) return "";
            return Api.readAll(new FileInputStream(f));
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear(Context c) {
        if (c == null) return;
        try { file(c).delete(); } catch (Throwable ignored) {}
    }
}
