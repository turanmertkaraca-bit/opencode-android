package ai.opencode.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * P10 SELF-TEST — renders the app's REAL views to PNG screenshots so the
 * look & feel is reviewed from actual pixels, not imagination ("build it
 * and take screen shots of the app to see if it looks good or not").
 *
 * Same rendering stack as the device (framework views, native graphics via
 * Robolectric's layoutlib), motion disabled so nothing is captured at
 * alpha 0, ServerService pinned HEALTHY so the sandbox veil is hidden.
 *
 * Output: /home/z/my-project/screenshots/*.png
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h892dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenTest {

    private static final String OUT = "/home/z/my-project/screenshots";

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void motionOff(Activity a) {
        a.getSharedPreferences("oc", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("motion", false).commit();
    }

    private static void setStateHealthy() throws Exception {
        Field f = ServerService.class.getDeclaredField("state");
        f.setAccessible(true);
        f.setInt(null, ServerService.ST_HEALTHY);
    }

    private static void save(Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView();
        int w = a.getResources().getDisplayMetrics().widthPixels;
        int h = a.getResources().getDisplayMetrics().heightPixels;
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bmp));
        new File(OUT).mkdirs();
        File f = new File(OUT, name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        System.out.println("[screen] " + f.getAbsolutePath() + " " + bmp.getWidth() + "x" + bmp.getHeight());
    }

    // ------------------------------------------------------------- screens

    @Test
    public void homeDeck() throws Exception {
        ActivityController<HomeActivity> ctl = Robolectric.buildActivity(HomeActivity.class);
        HomeActivity home = ctl.get();
        motionOff(home);
        Shadows.shadowOf(home).grantPermissions(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        // three believable project cards
        File base = new File(home.getFilesDir(), "demo-projects");
        File p1 = new File(base, "playground"); p1.mkdirs();
        File p2 = new File(base, "notes-api"); p2.mkdirs();
        File p3 = new File(base, "tiny-game"); p3.mkdirs();
        Projects.add(home, p1.getAbsolutePath());
        Projects.add(home, p2.getAbsolutePath());
        Projects.add(home, p3.getAbsolutePath());
        setStateHealthy();
        home = ctl.setup().get();
        idle();
        save(home, "1-home-deck.png");
        home.finish();
    }

    @Test
    public void chatTranscript() throws Exception {
        ActivityController<ChatActivity> ctl = Robolectric.buildActivity(ChatActivity.class);
        ChatActivity chat = ctl.get();
        motionOff(chat);
        setStateHealthy();
        AuthStore.setApiKey(chat, "opencode", "sk-zen-test-1234567890abcd");
        chat = ctl.setup().get();
        idle();

        // drive the REAL part pipeline with an SSE-shaped transcript
        part(chat, "{\"type\":\"text\",\"messageID\":\"m1\",\"id\":\"p1\","
                + "\"text\":\"why does the login crash on cold start?\"}", "user");
        part(chat, "{\"type\":\"reasoning\",\"messageID\":\"m2\",\"id\":\"p2\","
                + "\"text\":\"The user says login crashes on cold start. Likely the "
                + "session cache is read before the file exists. Let me check "
                + "SessionStore.load() and the init order in MainActivity.\"}", null);
        part(chat, "{\"type\":\"tool\",\"messageID\":\"m2\",\"id\":\"p3\",\"tool\":\"read\","
                + "\"state\":{\"status\":\"running\",\"title\":\"src/auth/SessionStore.java\","
                + "\"input\":{\"filePath\":\"src/auth/SessionStore.java\"}}}", null);
        part(chat, "{\"type\":\"tool\",\"messageID\":\"m2\",\"id\":\"p4\",\"tool\":\"bash\","
                + "\"state\":{\"status\":\"completed\",\"title\":\"npm test --silent\","
                + "\"input\":{\"command\":\"npm test --silent\"},"
                + "\"output\":\"\\u2713 47 passing (3.2s)\\n1 failing: auth cold-start\"}}", null);
        part(chat, "{\"type\":\"tool\",\"messageID\":\"m2\",\"id\":\"p5\",\"tool\":\"edit\","
                + "\"state\":{\"status\":\"completed\",\"title\":\"SessionStore.java +12 \\u22123\","
                + "\"input\":{\"filePath\":\"src/auth/SessionStore.java\","
                + "\"oldText\":\"if (cache == null)\",\"newText\":\"if (cache == null || cold)\"}}}", null);
        part(chat, "{\"type\":\"text\",\"messageID\":\"m3\",\"id\":\"p6\","
                + "\"text\":\"**Found it.** `SessionStore.load()` ran before the cache file "
                + "existed, and the NPE crashed the splash. Fixed by guarding the cold path:\\n\\n"
                + "- guard the null cache read\\n"
                + "- seed an empty session on first run\\n"
                + "- added a regression test\\n\\n"
                + "Cold start now lands on the login screen cleanly.\"}", null);
        part(chat, "{\"type\":\"patch\",\"messageID\":\"m3\",\"id\":\"p7\","
                + "\"files\":[\"src/auth/SessionStore.java\",\"src/auth/SessionStore.test.ts\"]}", null);
        idle();

        openRow(chat, "bash");    // show code blocks on the shell card
        openRow(chat, "reasoning"); // show the thinking body
        idle();
        save(chat, "2-chat-transcript.png");

        // permission ask pinned above the composer
        ingest(chat, "{\"type\":\"permission.asked\",\"properties\":{"
                + "\"id\":\"req-shot-1\",\"sessionID\":\"ses-x\",\"permission\":\"bash\","
                + "\"patterns\":[\"adb*\"],\"metadata\":{\"title\":\"Run adb devices\","
                + "\"command\":\"adb devices\"}}}");
        idle();
        save(chat, "3-chat-permission.png");
        chat.finish();
    }

    @Test
    public void settingsAndKeys() throws Exception {
        ActivityController<SettingsActivity> ctl = Robolectric.buildActivity(SettingsActivity.class);
        SettingsActivity settings = ctl.get();
        motionOff(settings);
        setStateHealthy();
        settings = ctl.setup().get();
        idle();
        save(settings, "4-settings.png");
        settings.finish();

        ActivityController<KeysActivity> ctl2 = Robolectric.buildActivity(KeysActivity.class);
        KeysActivity keys = ctl2.get();
        motionOff(keys);
        AuthStore.setApiKey(keys, "opencode", "sk-zen-live-9876543210abcdef");
        keys = ctl2.setup().get();
        idle();
        save(keys, "5-api-keys.png");
        keys.finish();
    }

    // ------------------------------------------------------------ helpers

    private static void part(Activity a, String json, String roleHint) throws Exception {
        Method m = ChatActivity.class.getDeclaredMethod("applyPart", Map.class, String.class);
        m.setAccessible(true);
        m.invoke(a, Json.obj(Json.parse(json)), roleHint);
    }

    private static void ingest(Activity a, String json) throws Exception {
        android.app.Service svc = Robolectric.setupService(ServerService.class);
        Method m = ServerService.class.getDeclaredMethod("ingest", Map.class);
        m.setAccessible(true);
        m.invoke(svc, Json.obj(Json.parse(json)));
    }

    /** Expand one row by tool name (or the reasoning card) before capture. */
    private static void openRow(Activity a, String key) throws Exception {
        Field rowsF = ChatActivity.class.getDeclaredField("rows");
        rowsF.setAccessible(true);
        List<?> rows = (List<?>) rowsF.get(a);
        Class<?> rowCls = rows.isEmpty() ? null : rows.get(0).getClass();
        if (rowCls == null) return;
        Field kindF = rowCls.getDeclaredField("kind"); kindF.setAccessible(true);
        Field toolF = rowCls.getDeclaredField("tool"); toolF.setAccessible(true);
        Field openF = rowCls.getDeclaredField("open"); openF.setAccessible(true);
        for (Object o : rows) {
            boolean hit = "reasoning".equals(key)
                    ? kindF.getInt(o) == ChatActivity.K_REASON
                    : key.equals(String.valueOf(toolF.get(o)));
            if (hit) {
                openF.setBoolean(o, true);
                Method t = ChatActivity.class.getDeclaredMethod("touchView",
                        rowCls);
                t.setAccessible(true);
                t.invoke(a, o);
                return;
            }
        }
    }
}
