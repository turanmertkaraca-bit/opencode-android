package ai.opencode.app;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P30 — pure pins for the field report:
 *
 *   • TerseMode — the LIVE setting injection: the mid-conversation toggle
 *     flip must reach the model on the NEXT message as a one-line
 *     <system-reminder> (the P29 AGENTS.md block only ever applied to a
 *     NEW session — the field called the feature broken, and it leaked
 *     into git diffs). Truth table, wrap/passthrough, one-line notes,
 *     and the upgrade migration (strip removes OUR block, user content
 *     byte-survives, second boot is a no-op).
 *
 *   • CostMath — the P30 hint format: the field read the right-aligned,
 *     hard-clipped line as "clipping to the other side of the UI". The
 *     layout now left-aligns + ellipsizes; the format is shortened and
 *     PINNED — including a worst-case length bound so the ellipsis can
 *     never fire in practice. The Σ pill format is untouched by design
 *     (separate function, Resilience.contextMeter, unchanged).
 *
 *   • ProjectDelete — long-press → Delete: the guards ARE the feature.
 *     Roots/mount points/app dir/ancestors refused, real project paths
 *     allowed, count-first abort on oversized trees, symlinks unlinked
 *     but never followed, exact entry counts.
 *
 * Plain JUnit — every subject here is framework-free by design.
 */
public class P30Test {

    // ---- TerseMode: the live-injection state machine --------------------

    @Test public void inject_truthTable() {
        // ON: announce until the session is confirmed ON
        assertTrue(TerseMode.needsInject(true, null));
        assertFalse(TerseMode.needsInject(true, Boolean.TRUE));
        assertTrue(TerseMode.needsInject(true, Boolean.FALSE));
        // OFF: silence by default — but a live ON→OFF flip MUST be
        // announced, or the model stays terse forever
        assertFalse(TerseMode.needsInject(false, null));
        assertFalse(TerseMode.needsInject(false, Boolean.FALSE));
        assertTrue(TerseMode.needsInject(false, Boolean.TRUE));
    }

    @Test public void wrap_ridesTheNote_whenNeeded_passthroughWhenInSync() {
        String msg = "fix the login bug";
        // fresh session, terse ON → note + message, in that order
        String w = TerseMode.wrap(msg, true, null);
        assertTrue(w.startsWith("<system-reminder>"));
        assertTrue(w.contains(TerseMode.NOTE_ON));
        assertTrue(w.endsWith(msg));
        assertTrue(w.indexOf(msg) == w.length() - msg.length());
        // the same session, already told → untouched, no double note
        assertEquals(msg, TerseMode.wrap(msg, true, Boolean.TRUE));
        // the user flips OFF mid-conversation → the OFF note rides once
        String w2 = TerseMode.wrap(msg, false, Boolean.TRUE);
        assertTrue(w2.contains(TerseMode.NOTE_OFF));
        assertTrue(w2.endsWith(msg));
        // after that, quiet again
        assertEquals(msg, TerseMode.wrap(msg, false, Boolean.FALSE));
        // never-told + OFF → silence (OFF is the default state; there is
        // nothing to retract in a session that never heard "terse")
        assertEquals(msg, TerseMode.wrap(msg, false, null));
    }

    @Test public void notes_areOneLine_andCompact() {
        // the note rides the user's OWN message — it costs like a
        // sentence, not like the P29 document block
        assertFalse(TerseMode.NOTE_ON.contains("\n"));
        assertFalse(TerseMode.NOTE_OFF.contains("\n"));
        assertTrue(TerseMode.NOTE_ON.contains("TERSE"));
        assertTrue(TerseMode.NOTE_OFF.contains("OFF"));
        assertTrue(TerseMode.NOTE_ON.length() < 400);
        assertTrue(TerseMode.NOTE_OFF.length() < 400);
        // and the two states are distinguishable at a glance
        assertFalse(TerseMode.NOTE_ON.equals(TerseMode.NOTE_OFF));
    }

    @Test public void migration_stripRemovesP29Block_userContentSurvives() {
        String user = "# My rules\n\n- keep the vibe\n";
        String dirty = TerseMode.merge(user, true);   // what a P29 device has
        assertTrue(TerseMode.isOn(dirty));
        String clean = TerseMode.strip(dirty);
        assertFalse(TerseMode.isOn(clean));
        assertTrue(clean.contains("# My rules"));
        assertTrue(clean.contains("- keep the vibe"));
        assertFalse(clean.contains(TerseMode.START));
        assertFalse(clean.contains(TerseMode.TEXT));
        // idempotent: the next boot's migration pass strips nothing new
        assertEquals(clean, TerseMode.strip(clean));
        // and a file that never had the block is never rewritten
        assertEquals(user, TerseMode.strip(user));
    }

    // ---- CostMath: the P30 hint format (the clipping fix) ---------------

    @Test public void hintLine_p30Format_priced() {
        assertEquals("≈ 6 new · next $0.0006 · ctx 8k",
                CostMath.hintLine(6, 8000, 0.0006, true, 1_000_000));
    }

    @Test public void hintLine_freeModel_stillSaysSo() {
        assertEquals("≈ 6 new · free model · ctx 8k",
                CostMath.hintLine(6, 8000, 0, true, 1_000_000));
    }

    @Test public void hintLine_priceUnknown_moneyStaysSilent() {
        assertEquals("≈ 6 new · ctx 8k",
                CostMath.hintLine(6, 8000, 0, false, 1_000_000));
    }

    @Test public void hintLine_compactNudge_namesTheButton() {
        String line = CostMath.hintLine(12500, 520000, 0.142, true, 1_000_000);
        assertTrue(line, line.endsWith(" · /compact saves"));
        // just below the 50% gate → no nudge
        String below = CostMath.hintLine(12500, 480000, 0.142, true, 1_000_000);
        assertFalse(below, below.contains("compact"));
    }

    @Test public void hintLine_emptyWhenNothingToSend() {
        assertEquals("", CostMath.hintLine(0, 8000, 0.0006, true, 1_000_000));
    }

    @Test public void hintLine_neverOverflowsTheComposerWidth() {
        // the field's "clipping": at 10sp mono ≈ 6dp/char, 56 chars ≈
        // 336dp — inside a 360dp screen minus the composer's 24dp padding.
        // The worst realistic combination must stay under it.
        String worst = CostMath.hintLine(12500, 520000, 0.1420, true, 1_000_000);
        assertTrue("len=" + worst.length() + " line=" + worst,
                worst.length() <= 56);
    }

    // ---- ProjectDelete: the guards ARE the feature -----------------------

    @Test public void safety_refusesEveryRoot() {
        String app = "/data/user/0/ai.opencode.app/files";
        String[] bad = {null, "", "  ", "/", "/storage", "/sdcard",
                "/data", "/mnt", "/system", "/proc", app};
        for (String b : bad) {
            assertNotNull("must be refused: " + b,
                    ProjectDelete.safetyCheck(b, app));
        }
    }

    @Test public void safety_refusesAncestorsOfTheAppDir() {
        String app = "/data/user/0/ai.opencode.app/files";
        assertNotNull(ProjectDelete.safetyCheck("/data/user/0", app));
        assertNotNull(ProjectDelete.safetyCheck("/data/user", app));
        assertNotNull(ProjectDelete.safetyCheck("/data", app));
    }

    @Test public void safety_allowsRealProjectPaths() {
        String app = "/data/user/0/ai.opencode.app/files";
        assertNull(ProjectDelete.safetyCheck(
                "/storage/emulated/0/opencode-projects/fn", app));
        assertNull(ProjectDelete.safetyCheck(
                "/sdcard/opencode-projects/playground", app));
    }

    /** root + src/ + src/main/ + src/main/java/ + 3 files = 7 entries
     *  (deleteTree counts the root; countTree counts children only). */
    private File tmpTree() throws Exception {
        File root = File.createTempFile("p30del", "").getParentFile();
        File dir = new File(root, "p30del" + System.nanoTime());
        File a = new File(dir, "src/main/java");
        assertTrue(a.mkdirs());
        assertTrue(new File(a, "Main.java").createNewFile());
        assertTrue(new File(dir, "README.md").createNewFile());
        assertTrue(new File(dir, "src/App.kt").createNewFile());
        return dir;
    }

    @Test public void count_countsTheWholeTree_andSignalsOverflow() throws Exception {
        File root = tmpTree();
        assertEquals(6, ProjectDelete.countTree(root, 10_000));
        // past the cap the count EXCEEDS it — the abort signal
        assertEquals(4, ProjectDelete.countTree(root, 3));
    }

    @Test public void delete_removesEverything_andCounts() throws Exception {
        File root = tmpTree();
        int n = ProjectDelete.deleteTree(root);
        assertEquals(7, n);
        assertFalse(root.exists());
    }

    @Test public void delete_abortsBeforeTouchingAnOversizedTree() throws Exception {
        File root = tmpTree();
        try {
            ProjectDelete.deleteTree(root, 3);
            fail("expected the size abort");
        } catch (IllegalStateException expected) { }
        // count-first: nothing was deleted
        assertTrue(root.exists());
        assertTrue(new File(root, "README.md").exists());
        assertTrue(new File(root, "src/main/java/Main.java").exists());
    }

    @Test public void delete_unlinksSymlinks_neverWalksThem() throws Exception {
        File root = tmpTree();
        File outside = java.nio.file.Files.createTempDirectory(
                "p30safe").toFile();
        File treasure = new File(outside, "treasure.txt");
        assertTrue(treasure.createNewFile());
        File link = new File(root, "link-dir");
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(),
                    outside.toPath());
        } catch (UnsupportedOperationException | java.io.IOException noLinks) {
            org.junit.Assume.assumeTrue("filesystem without symlinks", false);
            return;
        }
        ProjectDelete.deleteTree(root);
        assertFalse(root.exists());          // the whole project is gone…
        assertTrue(treasure.exists());       // …and the link target SURVIVED
    }

    @Test public void delete_nullSafe() {
        assertEquals(0, ProjectDelete.deleteTree(null));
        assertEquals(0, ProjectDelete.deleteTree(null, 5));
    }
}
