package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * P31 — the parallel-run rules, the credit limit, model favorites,
 * the environment reset plan, hibernation, resume parsing, the canvas
 * prompt and the palette table. Every new pure function, pinned.
 */
@RunWith(RobolectricTestRunner.class)
public class P31Test {

    // ---------------------------------------------------- RunBook.claim

    @Test
    public void claim_emptyRoom_ok() {
        assertEquals(RunBook.CLAIM_OK, RunBook.claim(new ArrayList<>(), "s1", 3));
    }

    @Test
    public void claim_sameSessionTwice_busy() {
        List<String> running = new ArrayList<>(Arrays.asList("s1"));
        assertEquals(RunBook.CLAIM_BUSY, RunBook.claim(running, "s1", 3));
    }

    @Test
    public void claim_capRefusesNewSession_butNotItsOwnMembers() {
        List<String> running = new ArrayList<>(Arrays.asList("s1", "s2", "s3"));
        assertEquals(RunBook.CLAIM_CAP, RunBook.claim(running, "s4", 3));
        assertEquals(RunBook.CLAIM_BUSY, RunBook.claim(running, "s2", 3));
    }

    @Test
    public void claim_nullOrEmptySession_refused() {
        assertEquals(RunBook.CLAIM_BUSY, RunBook.claim(new ArrayList<>(), null, 3));
        assertEquals(RunBook.CLAIM_BUSY, RunBook.claim(new ArrayList<>(), "", 3));
    }

    @Test
    public void claim_aSecondChatStaysFree_underTheCap() {
        List<String> running = new ArrayList<>(Arrays.asList("s1"));
        assertEquals(RunBook.CLAIM_OK, RunBook.claim(running, "s2", 3));
    }

    // ------------------------------------------------ RunBook.shouldRearm

    private static Map<String, Long> m(String k, long v) {
        Map<String, Long> map = new HashMap<>();
        map.put(k, v);
        return map;
    }

    @Test
    public void rearm_neverSent_neverRearms() {
        assertFalse(RunBook.shouldRearm(new HashMap<>(), new HashMap<>(),
                "s1", 1000, RunBook.REARM_WINDOW_MS));
    }

    @Test
    public void rearm_sentRecentlyWithoutIdle_reams() {
        Map<String, Long> sent = m("s1", 900);
        assertTrue(RunBook.shouldRearm(sent, new HashMap<>(), "s1",
                1000, RunBook.REARM_WINDOW_MS));
    }

    @Test
    public void rearm_cleanIdleSinceSend_killsThePhantom() {
        Map<String, Long> sent = m("s1", 900);
        Map<String, Long> idle = m("s1", 950);
        assertFalse(RunBook.shouldRearm(sent, idle, "s1",
                1000, RunBook.REARM_WINDOW_MS));
    }

    @Test
    public void rearm_sendOlderThanWindow_distrusted() {
        Map<String, Long> sent = m("s1", 0);
        long now = RunBook.REARM_WINDOW_MS + 5000;
        assertFalse(RunBook.shouldRearm(sent, new HashMap<>(), "s1",
                now, RunBook.REARM_WINDOW_MS));
    }

    @Test
    public void rearm_idleBeforeSend_stillEligible() {
        // idled long ago, then sent again — the idle is stale, not a stop
        Map<String, Long> sent = m("s1", 900);
        Map<String, Long> idle = m("s1", 100);
        assertTrue(RunBook.shouldRearm(sent, idle, "s1",
                1000, RunBook.REARM_WINDOW_MS));
    }

    @Test
    public void rearm_nullSid_never() {
        assertFalse(RunBook.shouldRearm(m("s1", 900), new HashMap<>(),
                null, 1000, RunBook.REARM_WINDOW_MS));
    }

    // --------------------------------------------------- stop scope note

    @Test
    public void stopNote_singleRun_isQuiet() {
        assertEquals("", RunBook.stopScopeNote(1, true));
    }

    @Test
    public void stopNote_parallelRuns_namesTheOthers() {
        String s = RunBook.stopScopeNote(2, true);
        assertTrue(s.contains("1 other chat"));
        assertTrue(s.contains("Sessions"));
    }

    // ------------------------------------------------------- CreditLimit

    @Test
    public void cap_parse_emptyMeansNoLimit() {
        assertEquals(0, CreditLimit.parseCap(null), 1e-9);
        assertEquals(0, CreditLimit.parseCap(""), 1e-9);
        assertEquals(0, CreditLimit.parseCap("   "), 1e-9);
        assertEquals(0, CreditLimit.parseCap("0"), 1e-9);
    }

    @Test
    public void cap_parse_dollars_commas_and_garbage() {
        assertEquals(5, CreditLimit.parseCap("$5"), 1e-9);
        assertEquals(5.5, CreditLimit.parseCap("5.50"), 1e-9);
        assertEquals(5.5, CreditLimit.parseCap("5,50"), 1e-9);
        assertEquals(12, CreditLimit.parseCap(" 12 "), 1e-9);
        assertEquals(-1, CreditLimit.parseCap("abc"), 1e-9);
        assertEquals(-1, CreditLimit.parseCap("-3"), 1e-9);
        assertEquals(-1, CreditLimit.parseCap("999999"), 1e-9);
    }

    @Test
    public void cap_verdict_ladder() {
        assertEquals(CreditLimit.OK, CreditLimit.verdict(3.0, 0));       // no cap
        assertEquals(CreditLimit.OK, CreditLimit.verdict(1.0, 5));
        assertEquals(CreditLimit.WARN, CreditLimit.verdict(4.1, 5));     // ≥80%
        assertEquals(CreditLimit.BLOCK, CreditLimit.verdict(5.0, 5));
        assertEquals(CreditLimit.BLOCK, CreditLimit.verdict(9.9, 5));
    }

    @Test
    public void cap_blockLine_names_the_way_out() {
        String s = CreditLimit.blockLine(5.2, 5);
        assertTrue(s, s.contains("credit limit"));
        assertTrue(s, s.contains("Settings"));
        assertTrue(s, s.contains("nothing was sent"));
    }

    @Test
    public void cap_stateLine_readsLikeAMeter() {
        assertTrue(CreditLimit.stateLine(3.42, 10).contains("34%"));
        assertTrue(CreditLimit.stateLine(2, 0).isEmpty()
                || CreditLimit.stateLine(2, 0).contains("spent"));
    }

    // ---------------------------------------------------- Models.favorites

    @Test
    public void favs_jsonRoundTrip_keepsOrder() {
        List<String[]> in = new ArrayList<>();
        in.add(new String[]{"opencode", "gpt-5"});
        in.add(new String[]{"opencode", "claude-x"});
        String j = Models.favsToJson(in);
        List<String[]> out = Models.favsFromJson(j);
        assertEquals(2, out.size());
        assertEquals("opencode", out.get(0)[0]);
        assertEquals("gpt-5", out.get(0)[1]);
        assertEquals("claude-x", out.get(1)[1]);
    }

    @Test
    public void favs_parse_dropsMalformed_andDupes() {
        List<String[]> out = Models.favsFromJson(
                "[\"good/model\",\"bad\",\"/nope\",\"good/model\",\"a/b\"]");
        assertEquals(2, out.size());
        assertEquals("good", out.get(0)[0]);
        assertEquals("a", out.get(1)[0]);
    }

    @Test
    public void favs_parse_garbage_isEmpty_neverNull() {
        assertTrue(Models.favsFromJson(null).isEmpty());
        assertTrue(Models.favsFromJson("not json").isEmpty());
        assertTrue(Models.favsFromJson("{}").isEmpty());
    }

    @Test
    public void favs_cap_limitsTheShelf() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) b.append(',');
            b.append("\"p/m").append(i).append('"');
        }
        b.append(']');
        assertEquals(Models.FAV_CAP, Models.favsFromJson(b.toString()).size());
    }

    // -------------------------------------------------- EnvironmentReset

    @Test
    public void env_targets_coverTheTooling_andNeverTheKeys() {
        File files = new File("/data/data/x/files");
        File cache = new File("/data/data/x/cache");
        List<File> targets = EnvironmentReset.targets(files, cache);
        List<String> names = new ArrayList<>();
        for (File f : targets) names.add(f.getName());
        for (String must : new String[]{"debian", "alpine", "wrappers",
                "shims", "bin", "busybox-applets.txt", "models-cache.json"}) {
            assertTrue("missing target " + must, names.contains(must));
        }
        for (File f : targets) {
            assertFalse("keys live in home/ — must never be a target",
                    f.getName().equals("home"));
            assertTrue("target escapes the app dirs: " + f,
                    f.getPath().startsWith("/data/data/x/"));
        }
    }

    @Test
    public void env_inside_guardsAgainstCanonicalEscapes() throws Exception {
        File root = java.nio.file.Files.createTempDirectory("envreset").toFile();
        File kid = new File(root, "debian");
        kid.mkdirs();
        assertTrue(EnvironmentReset.inside(kid, root));
        assertFalse(EnvironmentReset.inside(root, root));          // not strictly inside
        assertFalse(EnvironmentReset.inside(new File("/etc"), root));
        assertFalse(EnvironmentReset.inside(null, root));
        // a symlink pointing OUT is refused after canonicalization
        File link = new File(root, "sneaky");
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(),
                    java.nio.file.Paths.get("/etc"));
            assertFalse(EnvironmentReset.inside(link, root));
        } catch (UnsupportedOperationException ignored) {
            // no symlink support on this fs — the guard stays verified above
        }
    }

    @Test
    public void env_wipe_removesTheTree_andCounts() throws Exception {
        File root = java.nio.file.Files.createTempDirectory("envwipe").toFile();
        File debian = new File(root, "debian");
        new File(debian, "usr/bin").mkdirs();
        new File(debian, "usr/bin/ls").createNewFile();
        int n = EnvironmentReset.wipe(EnvironmentReset.targets(root,
                new File(root, "cache")), root, new File(root, "cache"));
        assertTrue("should have wiped entries, got " + n, n >= 2);
        assertFalse(new File(root, "debian").exists());
    }

    // -------------------------------------------------------- Hibernate

    @Test
    public void hibernate_neverWhileForegroundOrBusy() {
        long now = 1_000_000;
        assertFalse(Hibernate.due(0, now, Hibernate.minutesToMs(10), false, false));
        assertTrue(Hibernate.due(now - Hibernate.minutesToMs(11), now,
                Hibernate.minutesToMs(10), false, false));
        assertFalse("a run in ANY chat must block hibernation",
                Hibernate.due(now - Hibernate.minutesToMs(30), now,
                        Hibernate.minutesToMs(10), true, false));
        assertFalse("a pending approval must block hibernation",
                Hibernate.due(now - Hibernate.minutesToMs(30), now,
                        Hibernate.minutesToMs(10), false, true));
        assertFalse(Hibernate.due(now - 1, now, Hibernate.minutesToMs(10), false, false));
    }

    @Test
    public void hibernate_minutesScale() {
        assertEquals(0, Hibernate.minutesToMs(0));
        assertEquals(0, Hibernate.minutesToMs(-5));
        assertEquals(600_000L, Hibernate.minutesToMs(10));
    }

    // ----------------------------------------------------------- Resume

    @Test
    public void resume_chatRoundTrip() {
        String v = Resume.chatValue("Playground", "/sdcard/opencode-projects/playground");
        String[] p = Resume.parseLastScreen(v);
        assertEquals("chat", p[0]);
        assertEquals("Playground", p[1]);
        assertEquals("/sdcard/opencode-projects/playground", p[2]);
    }

    @Test
    public void resume_garbage_fallsBackToDeck() {
        assertEquals(Resume.DECK, Resume.parseLastScreen(null)[0]);
        assertEquals(Resume.DECK, Resume.parseLastScreen("deck")[0]);
        assertEquals(Resume.DECK, Resume.parseLastScreen("chat|no path")[0]);
        assertEquals(Resume.DECK, Resume.parseLastScreen("what")[0]);
        assertEquals(Resume.DECK, Resume.parseLastScreen("chat|a|")[0]);
    }

    @Test
    public void resume_nameMayBeEmpty_butPathDecides() {
        String[] p = Resume.parseLastScreen(Resume.chatValue(null, "/tmp/x"));
        assertEquals("chat", p[0]);
        assertEquals("", p[1]);
        assertEquals("/tmp/x", p[2]);
    }

    // -------------------------------------------------------- CanvasDoc

    @Test
    public void canvas_prompt_carriesTheContract() {
        String p = CanvasDoc.prompt("loops in javascript");
        assertTrue(p, p.contains("loops in javascript"));
        assertTrue(p, p.contains(CanvasDoc.FILE_NAME));
        assertTrue(p, p.contains("no external resources"));
        assertTrue(p, p.contains("inline"));
    }

    @Test
    public void canvas_prompt_emptyTopic_getsAHonestPlaceholder() {
        assertTrue(CanvasDoc.prompt("").contains("the topic we were discussing"));
        assertTrue(CanvasDoc.prompt(null).contains("the topic we were discussing"));
    }

    @Test
    public void canvas_renderable_htmlOnly() {
        assertTrue(CanvasDoc.isRenderable("canvas.html"));
        assertTrue(CanvasDoc.isRenderable("/a/b/Page.HTML"));
        assertTrue(CanvasDoc.isRenderable("x.htm"));
        assertFalse(CanvasDoc.isRenderable("canvas.html.bak"));
        assertFalse(CanvasDoc.isRenderable("main.js"));
        assertFalse(CanvasDoc.isRenderable(null));
    }

    @Test
    public void canvas_readGuard_missingAndUnreadable() throws Exception {
        assertNotNull(CanvasDoc.readGuard(new File("/nonexistent/page.html")));
        File f = File.createTempFile("canvas", ".html");
        java.io.FileWriter w = new java.io.FileWriter(f);
        w.write("<html><body>hi</body></html>");
        w.close();
        assertNull("a small readable page passes the guard",
                CanvasDoc.readGuard(f));
        f.delete();
    }

    // ----------------------------------------------------------- Theme

    @Test
    public void theme_sixPalettes_uniqueIds_allNamed() {
        assertEquals(6, Theme.PALETTES.length);
        for (int i = 0; i < Theme.PALETTES.length; i++)
            for (int j = i + 1; j < Theme.PALETTES.length; j++)
                assertNotEquals(Theme.PALETTES[i], Theme.PALETTES[j]);
        for (String id : Theme.PALETTES)
            assertFalse(Theme.paletteName(id).isEmpty());
    }

    @Test
    public void theme_unknownId_fallsBackToOled() {
        assertEquals(0, Theme.paletteIndex("nope"));
        assertEquals(0, Theme.paletteIndex(null));
        assertEquals(2, Theme.paletteIndex("graphite"));
        assertEquals(5, Theme.paletteIndex("paper"));
    }

    @Test
    public void theme_everyPaletteHasSixGradientPairs() {
        for (String id : Theme.PALETTES) {
            int[][] g = Theme.gradTable(id);
            assertEquals(id + " pairs", 6, g.length);
            for (int[] pair : g) assertEquals(id + " pair width", 2, pair.length);
        }
    }

    @Test
    public void theme_oledStaysPureBlack() {
        // the default the user set — pinned so a palette edit never
        // silently brightens the base
        int[] oled = Theme.PALETTE_DATA[0];
        assertEquals(0xFF000000, oled[0]);   // BG
        assertEquals(0xFF0B0E16, oled[1]);   // SURFACE
    }
}
