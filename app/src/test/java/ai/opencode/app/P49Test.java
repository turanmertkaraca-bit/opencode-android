package ai.opencode.app;

import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P49 — the CONSISTENCY release, pinned where each piece lives.
 *
 * FIELD REPORT DRIVING THIS RELEASE (v0.48.0 + the lost first P49): the
 * first P49 build was never published; the build that ran in the field
 * regressed Settings — opening it lagged, blacked the window, and the
 * ANR killer ended the process, and it got worse as the install grew.
 * The crash class was not new to the lost build: P48 already walked the
 * ENTIRE Debian rootfs (and the Alpine toolkit) RECURSIVELY ON THE UI
 * THREAD every time Settings or Diagnostics opened — seconds of main
 * thread block on a grown install, then the ANR kill. The chat's
 * remaining glitches traced to three more sources: the keyboard resize
 * never re-anchored the pinned bottom (newest rows hid behind the IME),
 * the catch-up markdown rebuild ran at EVERY burst boundary (dozens of
 * full re-renders per long answer, each a hitch), and the scroll pin's
 * base position went stale after long pill flights (the ↓ pill flashed
 * back for a frame after landing). All pinned here:
 *
 *   1. THE SETTINGS ANR — size cards measure OFF the main thread behind
 *      a process-wide cache (Sandbox.sizeAsync/sizeKnown); log viewers
 *      read a TAIL-BOUNDED window (Api.readTail). The walk contract is
 *      pinned with a deterministic executor.
 *   2. THE KEYBOARD RE-ANCHOR — the pin policy is a pure decision
 *      (ChatActivity.pinDecision): up always unpins, down re-pins only
 *      at the true bottom; the listener stays wiring.
 *   3. THE QUIET FINALIZE — streaming rows keep the cheap plain-text
 *      painter; the ONE markdown rebuild lands 600 ms after the row
 *      stops growing, re-armed when the stream resumes. Pinned through
 *      the real ChatActivity.
 *   4. The version moved to 0.49.0-p49.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class P49Test {

    // ==================================== 1. The pin decision (pure)

    @Test public void pin_upwardAlwaysUnpins_evenAtBottom() {
        assertFalse("an upward drag must unpin (P48 contract, now pure)",
                ChatActivity.pinDecision(100, 200, true));
    }

    @Test public void pin_downwardRepinsOnlyAtTrueBottom() {
        assertFalse("downward but not at the bottom: stay unpinned",
                ChatActivity.pinDecision(200, 100, false));
        assertTrue("downward and at the bottom: pin",
                ChatActivity.pinDecision(200, 100, true));
    }

    @Test public void pin_stillFrame_keepsTheBottomTruth() {
        assertTrue(ChatActivity.pinDecision(50, 50, true));
        assertFalse(ChatActivity.pinDecision(50, 50, false));
    }

    @Test public void pin_onePixelJitter_isNotAnUpwardDrag() {
        // the P48 tolerance: y < prev - 1, so a single pixel counts as
        // "still" and keeps the bottom truth
        assertTrue(ChatActivity.pinDecision(99, 100, true));
    }

    // ==================================== 2. The bounded tail read

    private static File write(String name, String body) throws Exception {
        File f = File.createTempFile(name, ".log");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        f.deleteOnExit();
        return f;
    }

    @Test public void readTail_emptyFile() throws Exception {
        assertEquals("", Api.readTail(write("p49empty", ""), 64));
    }

    @Test public void readTail_missingFileIsEmptyNeverThrows() throws Exception {
        assertEquals("", Api.readTail(new File("/nonexistent/p49.log"), 64));
    }

    @Test public void readTail_smallFile_comesBackWhole() throws Exception {
        File f = write("p49small", "alpha\nbeta\n");
        assertEquals("alpha\nbeta\n", Api.readTail(f, 64 * 1024));
    }

    @Test public void readTail_cutMidLine_dropsTheTornHead() throws Exception {
        // three 7-byte lines; a 10-byte window starts inside "ghijkl"
        File f = write("p49cut", "abcdef\nghijkl\nmnopqr\n");
        String tail = Api.readTail(f, 10);
        assertEquals("the torn partial line must never paint",
                "mnopqr\n", tail);
    }

    @Test public void readTail_exactBoundary_keepsEveryLine() throws Exception {
        // a window that starts exactly at a '\n' keeps the following lines
        File f = write("p49edge", "abcdef\nghijkl\nmnopqr\n");
        assertEquals("ghijkl\nmnopqr\n", Api.readTail(f, 15));
    }

    // ==================================== 3. The size-walk cache

    private static File tmpTree(long... fileSizes) throws Exception {
        File d = Files.createTempDirectory("p49tree").toFile();
        d.deleteOnExit();
        long n = 0;
        for (long sz : fileSizes) {
            File f = new File(d, "f" + (n++));
            try (FileOutputStream o = new FileOutputStream(f)) {
                byte[] b = new byte[(int) sz];
                o.write(b);
            }
            f.deleteOnExit();
        }
        return d;
    }

    @Test public void sizeAsync_unknownWalksAndCaches() throws Exception {
        File root = tmpTree(10, 5);                    // 15 bytes total
        String key = "p49-walk-" + System.nanoTime();
        assertEquals("never measured: the instant read is -1",
                -1L, Sandbox.sizeKnown(key));
        AtomicLong got = new AtomicLong(-2);
        Sandbox.sizeAsync(key, root, 60_000L, got::set, Runnable::run);
        assertEquals("the walk delivered the true size", 15L, got.get());
        assertEquals("and the cache holds it for the next screen",
                15L, Sandbox.sizeKnown(key));
    }

    @Test public void sizeAsync_freshCache_neverReWalks() throws Exception {
        File root = tmpTree(7);
        String key = "p49-fresh-" + System.nanoTime();
        Sandbox.sizeAsync(key, root, Long.MAX_VALUE, null, Runnable::run);
        // prove the fresh path serves from the cache: the root is DELETED
        // and the read still returns the measured number
        root.delete();
        AtomicLong got = new AtomicLong(-2);
        Sandbox.sizeAsync(key, root, Long.MAX_VALUE, got::set, Runnable::run);
        assertEquals("a fresh cache answer never touches the disk again",
                7L, got.get());
    }

    @Test public void sizeAsync_staleCache_reWalks() throws Exception {
        File root = tmpTree(9);
        String key = "p49-stale-" + System.nanoTime();
        Sandbox.sizeAsync(key, root, 0L, null, Runnable::run);   // first walk
        try (FileOutputStream o = new FileOutputStream(new File(root, "more"))) {
            o.write(new byte[11]);
        }
        AtomicLong got = new AtomicLong(-2);
        Sandbox.sizeAsync(key, root, 0L, got::set, Runnable::run);   // stale → re-walk
        assertEquals("a stale cache measures the grown tree again",
                20L, got.get());
    }

    @Test public void sizeAsync_deliveryIsOffTheCallerThread() throws Exception {
        // the app contract: the walk and the delivery NEVER run on the
        // caller (UI) thread — pinned with a thread-collecting executor
        File root = tmpTree(4);
        String key = "p49-thread-" + System.nanoTime();
        final AtomicLong tid = new AtomicLong();
        Sandbox.sizeAsync(key, root, 0L, v -> tid.set(Thread.currentThread().getId()),
                r -> new Thread(r, "p49-lane").start());
        assertFalse("the fresh number must not land on the calling thread",
                tid.get() == Thread.currentThread().getId());
    }

    // ============================== 4. The quiet finalize (real chat)

    private RunHub.Row addRow(String key, String text, int kind) {
        RunHub.Tx t = RunHub.tx();
        RunHub.Row r = new RunHub.Row();
        r.kind = kind;
        r.key = key;
        r.text.append(text);
        synchronized (RunHub.lock()) {
            t.rows.add(r);
            t.idxByKey.put(key, t.rows.size() - 1);
        }
        return r;
    }

    private static void set(String field, Object v) throws Exception {
        Field f = RunHub.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Runnable> finalizePending(ChatActivity a)
            throws Exception {
        Field f = ChatActivity.class.getDeclaredField("finalizePending");
        f.setAccessible(true);
        return (Map<String, Runnable>) f.get(a);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, View> viewsByKey(ChatActivity a) throws Exception {
        Field f = ChatActivity.class.getDeclaredField("viewByKey");
        f.setAccessible(true);
        return (Map<String, View>) f.get(a);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, View> bodiesByKey(ChatActivity a) throws Exception {
        Field f = ChatActivity.class.getDeclaredField("bodyByKey");
        f.setAccessible(true);
        return (Map<String, View>) f.get(a);
    }

    @Before
    public void resetHub() throws Exception {
        RunHub.Tx t = RunHub.tx();
        synchronized (RunHub.lock()) {
            t.rows.clear();
            t.idxByKey.clear();
            t.msgs.clear();
            t.typeCount.clear();
            t.trimmedKeys.clear();
            t.lastAssistantTok = 0;
            t.depthPeak = 0;
            t.costSum = 0;
            t.tokSum = 0;
        }
        set("sessionId", "sess-p49");
        set("sessionTitle", "test");
        set("busy", false);
        RunHub.clearRunsForTest();
        set("lastUserText", null);
    }

    @Test
    public void finalize_streamingRowKeepsTheCaret_thenQuietLandsMarkdown()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.Row r = addRow("p49|ans",
                    "plain while streaming, markdown when settled", RunHub.K_ASSISTANT);
            r.shown = 5;                        // mid-glide
            a.paintRowOnce(r);                  // builds the streaming view

            TextView body = (TextView) bodiesByKey(a).get("p49|ans");
            assertTrue("the streaming view exists", body != null);
            assertTrue("the caret paints while the row is behind",
                    body.getText().toString().endsWith("▍"));

            // drain the whole machine: the smoother's ticks catch the row
            // up, the quiet window elapses, the ONE markdown rebuild lands
            Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks();
            idleMain();

            assertTrue("the finalize ran (nothing left pending)",
                    finalizePending(a).isEmpty());
            TextView settled = (TextView) bodiesByKey(a).get("p49|ans");
            assertTrue("the settled view exists", settled != null);
            assertFalse("the caret is gone once the row settles",
                    settled.getText().toString().endsWith("▍"));
            assertTrue(settled.getText().toString().contains("plain while streaming"));
        }
    }

    @Test
    public void finalize_growingRowReArms_neverDoublesUp() throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.Row r = addRow("p49|grow", "first burst of the answer",
                    RunHub.K_ASSISTANT);
            r.shown = r.text.length();
            a.scheduleFinalize(r);               // burst boundary: armed
            assertEquals("exactly one finalize per row",
                    1, finalizePending(a).size());

            r.text.append(" plus a second burst before the quiet window elapsed");
            r.shown = r.text.length();
            a.scheduleFinalize(r);               // re-armed, not duplicated
            assertEquals("a re-arm replaces the pending callback",
                    1, finalizePending(a).size());

            Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks();
            idleMain();
            assertTrue("the quiet finalize lands exactly once",
                    finalizePending(a).isEmpty());
        }
    }

    @Test
    public void finalize_viewIsRebuiltOnlyAfterQuiet_notPerBurst()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            RunHub.Row r = addRow("p49|stage", "streaming tail text here",
                    RunHub.K_ASSISTANT);
            r.shown = 5;
            a.paintRowOnce(r);
            View before = viewsByKey(a).get("p49|stage");

            // a burst boundary (the flush path: requestPaint → flush) with
            // NO quiet time: the catch-up only ARMS the finalize — the
            // view must stay the streaming one
            r.shown = r.text.length();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            a.requestPaint(r);
            a.flushPaintsNow();
            View afterArm = viewsByKey(a).get("p49|stage");
            assertTrue("no rebuild at the burst boundary itself",
                    afterArm == before);
            assertFalse("the finalize is armed by the boundary flush",
                    finalizePending(a).isEmpty());

            Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks();
            idleMain();
            View settled = viewsByKey(a).get("p49|stage");
            assertTrue("the rebuild lands after the quiet window",
                    settled != before);
        }
    }

    // ==================================================== 5. Version

    @Test public void version_isAtLeastP49() {
        assertTrue(SettingsActivity.VERSION_TAG.compareTo("0.49.0-p49") > 0
                || SettingsActivity.VERSION_TAG.startsWith("0.49.0"));
    }
}
