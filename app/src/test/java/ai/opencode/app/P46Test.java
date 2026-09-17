package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P46 — the "keep it alive and honest" release, pinned where each piece
 * lives.
 *
 * FIELD REPORT DRIVING THIS RELEASE (v0.45.0): "clone works fine in
 * /tmp; the project folder's fuse filesystem can't do the atomic
 * rename during git clone" (fix: git shim injects --separate-git-dir
 * for FUSE destinations so the real .git lives on private ext4; new
 * projects default to private storage outright). "maybe give it its
 * own private storage for proot? the live file watcher make sure that
 * works too" (proot rootfs was ALREADY private; the missing half was
 * the workspace — seeded and defaulted private; the watcher is
 * path-driven and inotify is native on ext4). "real-time token
 * streaming doesn't work — I wait ~15 s then bursts" (the SSE thread
 * drowned in quadratic JSON parses of cumulative part frames; the
 * PartGovernor bounds the work to one apply per part per interval
 * with lossless final-state flushes). Long-session stability: the
 * seen-permission id set now carries a hard cap like every other
 * P26 bookkeeping map.
 */
@RunWith(RobolectricTestRunner.class)
public class P46Test {

    private static final String TYPE = "\"type\":\"message.part.updated\"";

    private static String frame(String sid, String mid, String pid,
                                String ptype, String text) {
        return "{\"type\":\"message.part.updated\",\"properties\":{\"part\":{"
                + "\"id\":\"" + pid + "\",\"sessionID\":\"" + sid
                + "\",\"messageID\":\"" + mid + "\",\"type\":\"" + ptype
                + "\",\"text\":\"" + text + "\",\"time\":null}}}";
    }

    // ================================== 1. PartGovernor scan

    @Test public void envelopeType_readsFirstTypeField() {
        assertEquals("message.part.updated",
                PartGovernor.envelopeType(TYPE + ",\"x\":1}"));
        assertEquals("session.idle",
                PartGovernor.envelopeType("{\"type\":\"session.idle\"}"));
        assertNull(PartGovernor.envelopeType("{\"nope\":1}"));
        assertNull(PartGovernor.envelopeType(null));
    }

    @Test public void partKey_keysTextAndReasoningParts() {
        String k1 = PartGovernor.partKey(
                frame("s1", "m1", "prt_a", "text", "hello"), TYPE.length());
        assertEquals("s1|m1|prt_a", k1);
        String k2 = PartGovernor.partKey(
                frame("s1", "m1", "prt_b", "reasoning", "hmm"), TYPE.length());
        assertEquals("s1|m1|prt_b", k2);
    }

    @Test public void partKey_ignoresToolPartsAndMissingIds() {
        // tool parts don't flood → no key → never throttled
        assertNull(PartGovernor.partKey(
                frame("s1", "m1", "prt_a", "tool", "{}"), TYPE.length()));
        // no session id → no key → pass-through (scan miss can't lose data)
        assertNull(PartGovernor.partKey(
                "{\"type\":\"message.part.updated\",\"properties\":{}}",
                TYPE.length()));
    }

    // ================================== 2. Governor state machine

    @Test public void governor_firstFrameApplies_immediately() {
        PartGovernor g = new PartGovernor();
        List<String> out = g.offer(frame("s", "m", "prt_a", "text", "hi"), 1000);
        assertEquals(1, out.size());
        assertEquals(1, g.appliedCount());
    }

    @Test public void governor_burstWithinInterval_holdsLatestOnly() {
        PartGovernor g = new PartGovernor();
        assertTrue(g.offer(frame("s", "m", "prt_a", "text", "c1"), 1000).size() == 1);
        // 49 more deltas inside the 250 ms window: all held, latest wins
        for (int i = 2; i <= 50; i++) {
            List<String> out = g.offer(
                    frame("s", "m", "prt_a", "text", "c" + i), 1000 + i);
            assertEquals("delta " + i + " must be held, not applied",
                    0, out.size());
        }
        assertEquals(1, g.pendingCount());
        assertEquals(1, g.appliedCount());
        // drain after the interval → exactly ONE frame, the LATEST state
        List<String> out = g.drain(1300, false);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("c50"));
        assertEquals(0, g.pendingCount());
    }

    @Test public void governor_nextInterval_appliesAgain() {
        PartGovernor g = new PartGovernor();
        assertTrue(g.offer(frame("s", "m", "prt_a", "text", "a"), 1000).size() == 1);
        assertTrue(g.offer(frame("s", "m", "prt_a", "text", "ab"), 1100).size() == 0);
        // 250 ms after the FIRST apply of this part, the next frame lands
        List<String> out = g.offer(frame("s", "m", "prt_a", "text", "abc"), 1260);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("abc"));
    }

    @Test public void governor_independentParts_doNotBlockEachOther() {
        PartGovernor g = new PartGovernor();
        assertTrue(g.offer(frame("s", "m", "prt_a", "text", "a"), 1000).size() == 1);
        assertTrue(g.offer(frame("s", "m", "prt_b", "text", "b"), 1010).size() == 1);
        assertTrue(g.offer(frame("s", "m", "prt_a", "text", "a2"), 1020).size() == 0);
    }

    @Test public void governor_messageUpdated_forceFlushesEverything() {
        PartGovernor g = new PartGovernor();
        assertTrue(g.offer(frame("s", "m", "prt_a", "text", "applied"), 1000).size() == 1);
        assertTrue(g.offer(frame("s", "m", "prt_a", "text", "held"), 1010).size() == 0);
        g.noteType("message.updated");
        List<String> out = g.drain(1015, false);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("held"));
    }

    @Test public void governor_sessionIdle_andError_forceFlush() {
        PartGovernor g = new PartGovernor();
        assertTrue(g.offer(frame("s", "m", "prt_a", "text", "a1"), 1000).size() == 1);
        assertTrue(g.offer(frame("s", "m", "prt_a", "text", "a2"), 1010).size() == 0);
        g.noteType("session.idle");
        List<String> out = g.drain(1011, false);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("a2"));

        assertTrue(g.offer(frame("s", "m", "prt_b", "text", "b1"), 2000).size() == 1);
        assertTrue(g.offer(frame("s", "m", "prt_b", "text", "b2"), 2010).size() == 0);
        g.noteType("session.error");
        out = g.drain(2011, false);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("b2"));
    }

    @Test public void governor_unknownAndHugeFrames_passThrough() {
        PartGovernor g = new PartGovernor();
        // unknown envelope: applied no matter how tightly packed
        assertEquals(1, g.offer("{\"type\":\"message.updated\"}", 1000).size());
        // oversized part frame: bypasses holding (memory bound)
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300 * 1024; i++) big.append('x');
        String raw = frame("s", "m", "prt_big", "text", big.toString());
        assertTrue(raw.length() > PartGovernor.MAX_HOLD_BYTES);
        assertEquals(1, g.offer(raw, 1000).size());
    }

    @Test public void governor_pendingOverflow_flushesOldest() {
        PartGovernor g = new PartGovernor();
        // saturate one interval window with MAX_PENDING_KEYS+1 distinct parts
        for (int i = 0; i < PartGovernor.MAX_PENDING_KEYS + 1; i++) {
            g.offer(frame("s", "m", "prt_" + i, "text", "p" + i), 1000 + i);
        }
        // oldest pending was flushed to stay bounded; newest still held
        assertTrue(g.pendingCount() <= PartGovernor.MAX_PENDING_KEYS);
        assertTrue(g.appliedCount() >= 1);
    }

    @Test public void governor_stormCostsLinearWork_andNeverLosesFinalState() {
        // 300 deltas, 20 ms apart (a real thinking-model token storm):
        // 60 s of stream. Quadratic parse work becomes ≤4 applies/second.
        PartGovernor g = new PartGovernor();
        int applied = 0;
        String last = null;
        for (int i = 1; i <= 300; i++) {
            String raw = frame("s", "m", "prt_a", "text", "chunk" + i);
            for (String c : g.offer(raw, (i - 1) * 20L)) { last = c; applied++; }
            for (String c : g.drain((i - 1) * 20L, false)) { last = c; applied++; }
        }
        // final flush (stream end) — the last snapshot MUST land
        for (String c : g.drain(6000, true)) { last = c; applied++; }
        assertNotNull(last);
        assertTrue(last.contains("chunk300"));
        // 300 frames → far fewer applies (throttled), but never zero and
        // never more than one per interval window plus the held tail
        assertTrue("governor must throttle (applied=" + applied + ")",
                applied <= 300 / (PartGovernor.MIN_INTERVAL_MS / 20) + 2);
        assertTrue(applied >= 2);
    }

    // ================================== 3. Shims: FUSE rule + git shim

    @Test public void fusePath_matchesSharedStorageOnly() {
        assertTrue(Shims.fusePath("/sdcard"));
        assertTrue(Shims.fusePath("/sdcard/projects/x"));
        assertTrue(Shims.fusePath("/storage/emulated/0/repo"));
        assertTrue(Shims.fusePath("/mnt/sdcard/Android/data/x"));
        // private app storage and everything else: NOT fuse
        assertFalse(Shims.fusePath("/data/data/ai.opencode.app/files/projects/x"));
        assertFalse(Shims.fusePath("/tmp"));
        assertFalse(Shims.fusePath(null));
        assertFalse(Shims.fusePath("/"));
    }

    @Test public void gitShim_injectsSeparateGitDir_onFuseDest() throws Exception {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        Shims.ensure(c);
        java.io.File f = new java.io.File(Shims.shimsDir(c), "git");
        assertTrue("git shim must exist", f.isFile());
        String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        // the FUSE rule is embedded verbatim — one source of truth
        assertTrue(s.contains(Shims.FUSE_CASE));
        // clone+init with --separate-git-dir into private storage
        assertTrue(s.contains("--separate-git-dir=\"$GD\""));
        assertTrue(s.contains(".sep-git"));
        // explicit user intent is honored, never double-injected
        assertTrue(s.contains("--separate-git-dir*"));
        assertTrue(s.contains("--bare"));
        // non-clone/init traffic execs straight through
        assertTrue(s.contains("exec \"$REAL\" \"$@\""));
        // P42 TLS pin survives the rewrite
        assertTrue(s.contains("GIT_SSL_CAINFO"));
        // mksh safety (same rule ShimTest enforces for every shim)
        for (String line : s.split("\n")) {
            String t = line.trim();
            assertFalse("mksh-unsafe line: " + line,
                    t.startsWith("&&") || t.startsWith("||"));
        }
    }

    // ================================== 4. Private project storage

    @Test public void version_isP46() {
        assertEquals("0.46.0-p46", SettingsActivity.VERSION_TAG);
    }
}
