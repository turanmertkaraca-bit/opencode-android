package ai.opencode.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * P52 — the SCREENSHOT HARNESS. Not a pass/fail test: it renders the
 * real screens (with real view trees) to PNGs under
 * {@code app/build/screenshots/} so the orchestrator can SEE the UI
 * without a device. Every screen is independently try/caught — one that
 * cannot be built in Robolectric logs and the run continues; the test
 * never asserts on the output.
 *
 * Phone target: 1080x2340 at xxhdpi (480dpi, a Pixel-ish window).
 * Native graphics is requested so {@code view.draw(canvas)} produces
 * real pixels rather than Robolectric's legacy no-op canvas.
 *
 * Follows the existing UI-test conventions: RobolectricTestRunner +
 * ActivityController.setup() (P28UiTest/P32UiTest/P33UiTest), the hub
 * reset/row seams from P48Test/P24ChatTest, and ShadowDialog for the
 * Sheet.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotTest {

    private static final int W = 1080;
    private static final int H = 2340;

    // ---- hub reset (P48Test/P49Test pattern) ------------------------------

    @Before
    public void resetHub() throws Exception {
        RunHub.Tx t = RunHub.tx();
        synchronized (RunHub.lock()) {
            t.rows.clear();
            t.idxByKey.clear();
            t.msgs.clear();
            t.typeCount.clear();
            t.trimmedKeys.clear();
        }
        setStatic("sessionId", null);
        setStatic("sessionTitle", "screenshots");
        setStatic("busy", false);
    }

    private static void setStatic(String name, Object v) throws Exception {
        Field f = RunHub.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void invoke(Activity a, String method) {
        try {
            Method m = a.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(a);
        } catch (Throwable ignored) {
            // best-effort: the screen still renders whatever it has
        }
    }

    /**
     * ChatActivity's full-screen "starting sandbox" veil is a PRIVATE field
     * ({@code private View veil}, ChatActivity.java:194) with no
     * public/package-private seam that hides it synchronously. The only
     * hide path is the private {@code syncVeil(int)} — and on the healthy
     * branch it FADES via an animator, which does not settle before
     * {@code view.draw()}. So: clear the field directly (immediate GONE),
     * falling back to {@code syncVeil(ServerService.ST_HEALTHY)}, then to
     * zero scrim alpha. A miss just leaves the veil as-is; nothing throws.
     */
    private static void hideVeil(Activity a) {
        if (!(a instanceof ChatActivity)) return;
        // 1. the field: no animation, immediate GONE (preferred)
        try {
            Field f = ChatActivity.class.getDeclaredField("veil");
            f.setAccessible(true);
            Object v = f.get(a);
            if (v instanceof View) {
                View veil = (View) v;
                veil.animate().cancel();
                veil.setAlpha(0f);
                veil.setVisibility(View.GONE);
                return;
            }
        } catch (Throwable ignored) {
            // fall through to the method seam
        }
        // 2. the real hide path the healthy-server flip calls
        try {
            Method m = ChatActivity.class.getDeclaredMethod("syncVeil", int.class);
            m.setAccessible(true);
            m.invoke(a, ServerService.ST_HEALTHY);
            Field f = ChatActivity.class.getDeclaredField("veil");
            f.setAccessible(true);
            Object v = f.get(a);
            if (v instanceof View) {
                View veil = (View) v;
                veil.animate().cancel();
                veil.setVisibility(View.GONE);
            }
            return;
        } catch (Throwable ignored) {
            // fall through to the scrim-alpha last resort
        }
        // 3. last resort: make the scrim transparent only
        try {
            Field f = ChatActivity.class.getDeclaredField("veil");
            f.setAccessible(true);
            Object v = f.get(a);
            if (v instanceof View) ((View) v).setAlpha(0f);
        } catch (Throwable ignored) {
            // the chat screenshot just shows the veil, as before
        }
    }

    // ---- synthetic transcript --------------------------------------------

    private static RunHub.Row addRow(int kind, String key, String text) {
        RunHub.Row r = new RunHub.Row();
        r.kind = kind;
        r.key = key;
        r.text.append(text);
        r.shown = r.text.length();
        synchronized (RunHub.lock()) {
            List<RunHub.Row> rows = RunHub.rows();
            Map<String, Integer> idx = RunHub.idx();
            idx.put(key, rows.size());
            rows.add(r);
        }
        return r;
    }

    private static void chatTranscript() {
        // user message
        addRow(RunHub.K_USER, "s|u1", "How is the chat transcript structured?");

        // assistant markdown reply: heading, bold, inline code, list, fence
        RunHub.Row answer = addRow(RunHub.K_ASSISTANT, "s|a1",
                "## Short answer\n\n"
                        + "The transcript is one `LinearLayout` inside a "
                        + "`ScrollView`, painted row by row.\n\n"
                        + "**Each row kind has its own card:**\n\n"
                        + "- user rows are neutral rounded pills\n"
                        + "- assistant rows render **markdown**\n"
                        + "- tool + thought cards collapse by default\n\n"
                        + "```java\n"
                        + "for (RunHub.Row r : RunHub.rows()) {\n"
                        + "    paintRowOnce(r);\n"
                        + "}\n"
                        + "```\n\n"
                        + "That is the whole feed loop.");
        answer.meta = "⇅ 1.2k tok · $0.0041";

        // tool card (expanded so input/output are visible)
        RunHub.Row tool = addRow(RunHub.K_TOOL, "s|t1", "");
        tool.tool = "bash";
        tool.status = "completed";
        tool.title = "npm test";
        tool.input.append("npm test --silent");
        tool.output.append("42 passing (1.2s)");
        tool.open = true;

        // thought / reasoning card (expanded)
        RunHub.Row think = addRow(RunHub.K_REASON, "s|r1",
                "Checking the project layout before answering — the row "
                        + "model lives in RunHub, so the view only paints.");
        think.open = true;

        // error row (title + detail)
        RunHub.Row err = addRow(RunHub.K_ERR, "s|e1",
                "provider request failed");
        err.output.append("HTTP 429: rate limit exceeded — retrying in 2 s");

        // a transcript note
        addRow(RunHub.K_SYS, "s|sys1", "⏵ server ready · sandbox warm");
    }

    // ---- screens ---------------------------------------------------------

    @Test
    public void renderAllScreens() {
        snapChat();
        snapHome();
        snapSettings();
        snapFiles();
        snapSheet();
        snapStorage();
    }

    private void snapChat() {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity act = ctl.setup().get();
            idleMain();

            // empty state first (hero + suggestion chips)
            invoke(act, "renderAll");
            idleMain();
            hideVeil(act);
            shoot(act, "chat_empty");

            // then a populated transcript — no boot veil over the design
            chatTranscript();
            invoke(act, "renderAll");
            idleMain();
            hideVeil(act);
            shoot(act, "chat");
        } catch (Throwable t) {
            log("chat", t);
        }
    }

    private void snapHome() {
        try (ActivityController<HomeActivity> ctl =
                     Robolectric.buildActivity(HomeActivity.class)) {
            HomeActivity act = ctl.setup().get();
            invoke(act, "init");            // force the deck build (P31UiTest)
            idleMain();
            shoot(act, "home");
        } catch (Throwable t) {
            log("home", t);
        }
    }

    private void snapSettings() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity act = ctl.setup().get();
            idleMain();
            shoot(act, "settings");
        } catch (Throwable t) {
            log("settings", t);
        }
    }

    private void snapFiles() {
        try (ActivityController<FilesActivity> ctl =
                     Robolectric.buildActivity(FilesActivity.class)) {
            FilesActivity act = ctl.setup().get();
            idleMain();
            shoot(act, "files");
        } catch (Throwable t) {
            log("files", t);
        }
    }

    private void snapSheet() {
        try (ActivityController<SettingsActivity> ctl =
                     Robolectric.buildActivity(SettingsActivity.class)) {
            SettingsActivity act = ctl.setup().get();
            idleMain();
            Sheet.show(act, "Session")
                    .sub("2 messages · sandbox ready")
                    .msg("The agent is waiting for your approval before it "
                            + "runs the shell command.")
                    .row("⌘", "Model", "claude-sonnet-4 · live",
                            Theme.ACCENT_LT, null)
                    .row("⇅", "Compact", "summarise the session",
                            Theme.TXT_DIM, null)
                    .pill("Allow once", Sheet.PRIMARY, null)
                    .pill("Cancel", Sheet.QUIET, null);
            idleMain();
            Dialog d = ShadowDialog.getLatestDialog();
            if (d == null || d.getWindow() == null) {
                log("sheet", new IllegalStateException("no dialog"));
                return;
            }
            shootView(d.getWindow().getDecorView(), "sheet");
        } catch (Throwable t) {
            log("sheet", t);
        }
    }

    private void snapStorage() {
        // P53: the scan runs on a worker thread; the first frame renders
        // "calculating…", which is the honest state under Robolectric.
        try (ActivityController<StorageActivity> ctl =
                     Robolectric.buildActivity(StorageActivity.class)) {
            StorageActivity act = ctl.setup().get();
            idleMain();
            shoot(act, "storage");
        } catch (Throwable t) {
            log("storage", t);
        }
    }

    // ---- render + write --------------------------------------------------

    private static void shoot(Activity a, String name) {
        shootView(a.getWindow().getDecorView(), name);
    }

    private static void shootView(View decor, String name) {
        try {
            decor.measure(
                    View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, W, H);
            Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Theme.BG);        // opaque base (decor may be clear)
            decor.draw(canvas);
            File out = new File(screenshotsDir(), name + ".png");
            try (FileOutputStream o = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            bmp.recycle();
            System.out.println("[screenshot] " + out.getAbsolutePath());
        } catch (Throwable t) {
            log(name, t);
        }
    }

    /** app/build/screenshots regardless of the test working dir. */
    private static File screenshotsDir() {
        File base = new File(System.getProperty("user.dir", "."));
        File appModule = new File(base, "app");
        File dir = new File(appModule.isDirectory() ? appModule : base,
                "build/screenshots");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static void log(String name, Throwable t) {
        System.err.println("[screenshot] " + name + " skipped: " + t);
    }
}
