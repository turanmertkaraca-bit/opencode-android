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

    @Override
    public void onCreate() {
        super.onCreate();
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
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    public static File crashFile(Context c) {
        return new File(c.getFilesDir(), "last-crash.txt");
    }
}
