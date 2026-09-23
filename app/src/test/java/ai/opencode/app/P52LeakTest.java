package ai.opencode.app;

import android.os.Handler;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P52 — the long-session memory/lifecycle audit, pinned where it can be
 * pinned without touching production.
 *
 * The audit (see the P52 report) found several confirmed growth paths
 * that production does NOT yet bound (RunHub.evictedSums, the P43 beat
 * maps, the P40 poison sets, failedImgs). Those cannot be asserted to a
 * wall that does not exist — a failing pin is not a regression test — so
 * they are reported instead. What this class DOES pin:
 *
 *   1. the activity listener lifecycle — a destroyed ChatActivity must
 *      not stay on ServerService's static listener list or the hub's
 *      static UI sink (the classic "activity retained forever" leak);
 *   2. RunHub's documented walls — MSG_CAP / TYPE_COUNT_CAP / trim /
 *      edit-focus / image cache / archive / spend-ledger / the per-session
 *      LRUs (sentAt, idledAt, styleTold, renderTold, envTold);
 *   3. screen-scoped Handler callbacks — onPause must cancel the runnables
 *      the code claims to cancel (flushPaints, deferredLiveUpdate,
 *      veilTicker, the stream tick, the cache-beat clock);
 *   4. the other static collections — ServerService's permission-id LRUs
 *      and RenderCheck's verdict LRU stay bounded under simulated use.
 *
 * Everything is deterministic; the two activity tests skip via Assume if
 * this Robolectric sandbox cannot build ChatActivity (they never fail for
 * an environment reason).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class P52LeakTest {

    // ============================================ 1. listener lifecycle

    @Test
    public void chatActivityCycles_leaveNoStaticListenerOrUiReferences()
            throws Exception {
        List<Object> listeners = rawList(ServerService.class, "listeners");
        List<Object> evtListeners = rawList(ServerService.class, "evtListeners");
        List<Object> uis = rawList(RunHub.class, "uis");

        int beforeL = listeners.size();
        int beforeE = evtListeners.size();
        int beforeU = uis.size();

        boolean built;
        boolean subscribed = false;
        try {
            subscribed = cycleChatActivity(listeners);
            built = true;
        } catch (Throwable t) {
            built = false;
        }
        Assume.assumeTrue("Robolectric cannot build ChatActivity here", built);
        Assume.assumeTrue("ChatActivity did not reach subscribe() "
                + "(stale-theme early return?)", subscribed);

        for (int i = 0; i < 25; i++) cycleChatActivity(listeners);

        int chatListeners = 0;
        for (Object o : listeners) if (o instanceof ChatActivity) chatListeners++;
        int chatUis = 0;
        for (Object o : uis) if (o instanceof ChatActivity) chatUis++;

        assertEquals("a destroyed ChatActivity must not stay subscribed to "
                + "ServerService.listeners", 0, chatListeners);
        assertEquals("a destroyed ChatActivity must not stay bound as a "
                + "RunHub.Ui", 0, chatUis);
        assertTrue("listener list must not grow per resume/pause cycle ("
                + listeners.size() + " vs " + beforeL + ")",
                listeners.size() <= beforeL + 1);
        assertTrue("event-listener list must not grow per cycle",
                evtListeners.size() <= beforeE + 1);
        assertTrue("ui sink list must not grow per cycle",
                uis.size() <= beforeU + 1);
    }

    /** One create→resume→pause→destroy lap. Returns whether the activity
     *  was actually subscribed while resumed (the early-return guard). */
    private static boolean cycleChatActivity(List<Object> listeners) {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ctl.setup();
            boolean subscribed = false;
            for (Object o : listeners) if (o instanceof ChatActivity) subscribed = true;
            ctl.pause();
            return subscribed;
        }
    }

    // ============================================ 2. RunHub bounded state

    @Before
    public void resetHub() throws Exception {
        RunHub.Tx t = RunHub.tx();
        synchronized (RunHub.lock()) {
            t.rows.clear();
            t.idxByKey.clear();
            t.msgs.clear();
            t.typeCount.clear();
            t.trimmedKeys.clear();
            t.evictedSums.clear();
            t.lastAssistantTok = 0;
            t.depthPeak = 0;
            t.costSum = 0;
            t.tokSum = 0;
        }
        setStatic(RunHub.class, "sessionId", null);
        setStatic(RunHub.class, "sessionTitle", null);
        setStatic(RunHub.class, "busy", false);
        RunHub.clearRunsForTest();
        rawMap(RunHub.class, "styleTold").clear();
        rawMap(RunHub.class, "renderTold").clear();
        rawMap(RunHub.class, "envTold").clear();
        rawMap(RunHub.class, "editFocus").clear();
        rawMap(RunHub.class, "archive").clear();
        RunHub.imageCache.clear();
    }

    @Test
    public void runHubSoak_perSessionCollectionsStayWithinTheirWalls()
            throws Exception {
        int n = RunHub.MSG_CAP + 100;
        for (int i = 0; i < n; i++) {
            RunHub.applyMessageInfo(RunHub.tx(), obj(
                    "id", "m" + i, "role", "assistant",
                    "tokens", obj("input", 10, "output", 5),
                    "cost", 0.001));
        }
        synchronized (RunHub.lock()) {
            assertTrue("per-message bookkeeping stays under MSG_CAP",
                    RunHub.tx().msgs.size() <= RunHub.MSG_CAP);
        }
        assertEquals("session sums survive the eviction wall",
                n * 0.001, RunHub.sessionCost(), 1e-9);
        assertEquals((long) n * 15L, RunHub.sessionTok());

        for (int i = 0; i < RunHub.TYPE_COUNT_CAP + 500; i++) {
            RunHub.applyPart(obj("type", "text", "sessionID", (Object) null,
                    "messageID", "flood" + i, "text", "x"), "assistant");
        }
        synchronized (RunHub.lock()) {
            assertTrue("pid-less part counter stays capped",
                    RunHub.tx().typeCount.size() <= RunHub.TYPE_COUNT_CAP);
            assertTrue("rendered rows stay inside the trim wall",
                    RunHub.tx().rows.size() <= RunHub.TRIM_OVER + 1);
            assertTrue("trimmed-key memory stays capped",
                    RunHub.tx().trimmedKeys.size() <= 4096);
        }

        assertLruCapped("styleTold", 200, 64);
        assertLruCapped("renderTold", 200, 64);
        assertLruCapped("envTold", 200, 64);
        assertLruCapped("sentAt", 200, 64);
        assertLruCapped("idledAt", 200, 64);

        Map<String, Object> focus = rawMap(RunHub.class, "editFocus");
        for (int i = 0; i < RunHub.EDIT_FOCUS_CAP + 60; i++) {
            focus.put("/p/f" + i + ".ts", "snippet " + i);
        }
        assertTrue("edit-focus snippets stay capped",
                focus.size() <= RunHub.EDIT_FOCUS_CAP);

        for (int i = 0; i < 20; i++) RunHub.imageCache.put("img" + i, null);
        assertTrue("decoded-image cache stays capped",
                RunHub.imageCache.size() <= 6);

        Map<String, Object> archive = rawMap(RunHub.class, "archive");
        synchronized (RunHub.lock()) {
            archive.clear();
            for (int i = 0; i < 20; i++) archive.put("arch-" + i, new RunHub.Tx());
        }
        Method evict = RunHub.class.getDeclaredMethod("evictArchive", String.class);
        evict.setAccessible(true);
        synchronized (RunHub.lock()) { evict.invoke(null, "arch-0"); }
        assertTrue("archived transcripts stay under ARCHIVE_CAP",
                archive.size() <= RunHub.ARCHIVE_CAP);

        Method book = RunHub.class.getDeclaredMethod("spendLedgerBook",
                String.class, String.class, double.class, boolean.class);
        book.setAccessible(true);
        for (int i = 0; i < 20; i++) book.invoke(null, "spend-" + i, "m1", 0.1, true);
        Map<String, Object> ledger = rawMap(RunHub.class, "spendLedger");
        synchronized (RunHub.lock()) {
            assertTrue("the persisted spend ledger keeps only the newest sessions",
                    ledger.size() <= 8);
        }
    }

    private static void assertLruCapped(String field, int fill, int cap)
            throws Exception {
        Map<String, Object> m = rawMap(RunHub.class, field);
        for (int i = 0; i < fill; i++) m.put(field + "-" + i, Long.valueOf(i));
        assertTrue(field + " must stay under its LRU cap (" + m.size() + ")",
                m.size() <= cap);
    }

    // ==================================== 3. screen-scoped Handler cleanup

    @Test
    public void pausedChatActivity_dropsItsScreenScopedCallbacks()
            throws Exception {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            Handler ui = (Handler) instanceField(a, "ui");
            Handler smoother = (Handler) instanceField(a, "smoother");
            Handler beatClock = (Handler) instanceField(a, "beatClock");
            Runnable flush = (Runnable) instanceField(a, "flushPaints");
            Runnable deferred = (Runnable) instanceField(a, "deferredLiveUpdate");
            Runnable veil = (Runnable) instanceField(a, "veilTicker");
            Runnable tick = (Runnable) instanceField(a, "tick");
            Runnable beat = (Runnable) instanceField(a, "beatTick");

            Assume.assumeTrue("screen-scoped runnables unavailable in this build",
                    ui != null && smoother != null && beatClock != null
                            && flush != null && deferred != null && veil != null
                            && tick != null && beat != null);

            ui.postDelayed(flush, 60_000);
            ui.postDelayed(deferred, 60_000);
            ui.postDelayed(veil, 60_000);
            smoother.postDelayed(tick, 60_000);
            beatClock.postDelayed(beat, 60_000);

            ctl.pause();

            assertFalse("flushPaints must be cancelled on pause",
                    ui.hasCallbacks(flush));
            assertFalse("deferredLiveUpdate must be cancelled on pause",
                    ui.hasCallbacks(deferred));
            assertFalse("veilTicker must be cancelled on pause",
                    ui.hasCallbacks(veil));
            assertFalse("the stream tick must be cancelled on pause",
                    smoother.hasCallbacks(tick));
            assertFalse("the cache-beat clock must be cancelled on pause",
                    beatClock.hasCallbacks(beat));

            // belt-and-braces: the guard flags the cancellers flip
            assertFalse("beat clock flag must be off after pause",
                    (Boolean) instanceField(a, "beatClockRunning"));
            assertFalse("smoother flag must be off after pause",
                    (Boolean) instanceField(a, "smootherRunning"));
        }
    }

    // ==================================== 4. static-collection census

    @Test
    public void staticCollectionCensus_simulatedUse_staysBounded()
            throws Exception {
        int permCap = ((Integer) staticField(ServerService.class, "PERM_ID_CAP"))
                .intValue();
        Method mark = ServerService.class
                .getDeclaredMethod("markSeenPerm", String.class);
        mark.setAccessible(true);
        for (int i = 0; i < permCap * 2; i++) mark.invoke(null, "seen-" + i);
        Set<String> seen = rawSet(ServerService.class, "seenPermIds");
        assertTrue("seen permission ids are an LRU, never a growing set",
                seen.size() <= permCap);

        for (int i = 0; i < permCap * 2; i++) {
            ServerService.noteAnswered("answered-" + i);
        }
        Set<String> answered = rawSet(ServerService.class, "answeredPermIds");
        assertTrue("answered permission tombstones stay capped",
                answered.size() <= permCap);

        for (int i = 0; i < RenderCheck.STATE_CAP * 2; i++) {
            RenderCheck.remember("/p/page" + i + ".html", i % 2 == 0);
        }
        Map<String, Object> state = rawMap(RenderCheck.class, "STATE");
        synchronized (RenderCheck.class) {
            assertTrue("render-check verdicts stay under STATE_CAP",
                    state.size() <= RenderCheck.STATE_CAP);
        }
    }

    // ------------------------------------------------------------ helpers

    private static Object instanceField(Object target, String name)
            throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object staticField(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setStatic(Class<?> c, String name, Object v)
            throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rawList(Class<?> c, String name) throws Exception {
        return (List<Object>) staticField(c, name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawMap(Class<?> c, String name)
            throws Exception {
        return (Map<String, Object>) staticField(c, name);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> rawSet(Class<?> c, String name) throws Exception {
        return (Set<String>) staticField(c, name);
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
