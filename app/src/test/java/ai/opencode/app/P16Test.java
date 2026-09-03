package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * P16 regression tests — the "I can't add my opencode go key" round.
 *
 * The user proved the P14 claim ("Zen and Go are plans on the same
 * gateway, same row, same key") wrong: models.dev and the server carry
 * TWO distinct opencode providers, each with its own key. P16 fixes the
 * dead end — these tests pin the exact conditions that made the bug
 * impossible to hit again, plus the live-directory engine's event
 * mapping and the path clamp.
 */
@RunWith(RobolectricTestRunner.class)
public class P16Test {

    // ------------------------------------------------------- key rows

    /** THE P16 fix: both opencode providers must exist as separate key
     *  slots, with ids matching the catalog/server EXACTLY. */
    @Test
    public void knownProviders_containSeparateZenAndGoSlots() {
        String zenId = null, goId = null, zenName = null, goName = null;
        for (String[] k : AuthStore.KNOWN) {
            if ("opencode".equals(k[0]))    { zenId = k[0]; zenName = k[1]; }
            if ("opencode-go".equals(k[0])) { goId = k[0]; goName = k[1]; }
        }
        assertEquals("opencode (Zen) row must exist", "opencode", zenId);
        assertEquals("opencode-go row must exist", "opencode-go", goId);
        assertTrue("Zen row must say Zen", zenName.contains("Zen"));
        assertTrue("Go row must say Go", goName.contains("Go"));
        assertNotEquals("display names must differ", zenName, goName);
        // hints must say the keys are separate — the confusion that cost
        // the user an afternoon
        for (String[] k : AuthStore.KNOWN) {
            if ("opencode".equals(k[0]) || "opencode-go".equals(k[0])) {
                String low = k[2].toLowerCase();
                assertTrue("hint must mention separateness",
                        low.contains("separat") || low.contains("not interchangeable"));
            }
        }
    }

    /** save→hasKey round-trip per provider id, including opencode-go, and
     *  removal on empty key — the exact state the picker reads. */
    @Test
    public void authStore_roundTripsGoKeyIndependently() throws Exception {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        assertFalse(AuthStore.hasKey(c, "opencode-go"));
        AuthStore.setApiKey(c, "opencode-go", "go-key-123");
        assertTrue(AuthStore.hasKey(c, "opencode-go"));
        // the zen slot must stay untouched by a go save (and vice versa)
        AuthStore.setApiKey(c, "opencode", "zen-key-456");
        assertTrue(AuthStore.hasKey(c, "opencode"));
        java.util.Map<String, Object> auth = AuthStore.readAuth(c);
        assertEquals("go-key-123",
                ((java.util.Map<?, ?>) auth.get("opencode-go")).get("key"));
        assertEquals("zen-key-456",
                ((java.util.Map<?, ?>) auth.get("opencode")).get("key"));
        // empty save removes only its own slot
        AuthStore.setApiKey(c, "opencode-go", "");
        assertFalse(AuthStore.hasKey(c, "opencode-go"));
        assertTrue(AuthStore.hasKey(c, "opencode"));
    }

    // ------------------------------------------------- DirWatcher core

    /** Event → badge mapping the live rail shows. Uses the mirrored
     *  constants (framework FileObserver constants are non-static). */
    @Test
    public void dirWatcher_classify_mapsEventsToActions() {
        assertEquals("new", DirWatcher.classify(DirWatcher.EV_CREATE));
        assertEquals("new", DirWatcher.classify(DirWatcher.EV_MOVED_TO));
        assertEquals("mod", DirWatcher.classify(DirWatcher.EV_CLOSE_WRITE));
        assertEquals("del", DirWatcher.classify(DirWatcher.EV_DELETE));
        assertEquals("del", DirWatcher.classify(DirWatcher.EV_MOVED_FROM));
        assertEquals("del", DirWatcher.classify(DirWatcher.EV_DELETE_SELF));
        assertEquals("ignore-only events return null",
                null, DirWatcher.classify(DirWatcher.EV_ACCESS));
    }

    /** The debounce pipeline: notes for the same path collapse into ONE
     *  delivery, distinct paths deliver together, and nothing arrives
     *  after stop(). Robolectric's FileObserver shadow does not watch
     *  real files, so the test drives note() directly (package-private
     *  for exactly this reason). */
    @Test
    public void dirWatcher_debouncesAndDelivers() throws Exception {
        File root = new File(org.robolectric.RuntimeEnvironment.getApplication()
                .getCacheDir(), "watch-" + System.nanoTime());
        assertTrue(root.mkdirs());
        try {
            java.util.List<String[]> hits =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            DirWatcher w = new DirWatcher(android.os.Looper.getMainLooper(),
                    (path, action) -> hits.add(new String[]{path, action}));
            w.start(root);
            assertTrue("watcher should be running", w.isRunning());

            File a = new File(root, "a.txt");
            File b = new File(root, "b.txt");
            w.note(a.getAbsolutePath(), "mod");
            w.note(a.getAbsolutePath(), "mod");   // same path → collapses
            w.note(b.getAbsolutePath(), "new");

            // pump the looper past the debounce window
            org.robolectric.shadows.ShadowLooper.runMainLooperToNextTask();
            org.robolectric.shadows.ShadowLooper.runMainLooperToNextTask();
            org.robolectric.shadows.ShadowLooper.runMainLooperToNextTask();

            int aHits = 0, bHits = 0;
            for (String[] hit : hits) {
                if (hit[0].endsWith("a.txt")) aHits++;
                if (hit[0].endsWith("b.txt") && "new".equals(hit[1])) bHits++;
            }
            assertEquals("same-path bursts deliver once", 1, aHits);
            assertEquals("distinct path delivers with its action", 1, bHits);

            w.stop();
            assertFalse(w.isRunning());
            w.note(new File(root, "late.txt").getAbsolutePath(), "new");
            org.robolectric.shadows.ShadowLooper.runMainLooperToNextTask();
            for (String[] hit : hits)
                assertFalse("nothing after stop()", hit[0].endsWith("late.txt"));
        } finally {
            File[] k = root.listFiles();
            if (k != null) for (File x : k) x.delete();
            root.delete();
        }
    }

    // ------------------------------------------------------- path clamp

    /** safe() must keep every create/rename target inside the project
     *  root — traversal ("../../etc/passwd") and slashes get flattened. */
    @Test
    public void filesSafe_clampsTraversal() throws Exception {
        java.lang.reflect.Method m = FilesActivity.class.getDeclaredMethod(
                "safe", File.class, String.class);
        m.setAccessible(true);
        File dir = new File("/data/data/x/files/proj");
        String out = (String) m.invoke(null, dir, "../../etc/passwd");
        assertTrue("clamped path must stay under the project dir",
                out.startsWith(dir.getAbsolutePath() + "/"));
        assertFalse("must not contain traversal segments",
                out.contains(".."));
    }
}
