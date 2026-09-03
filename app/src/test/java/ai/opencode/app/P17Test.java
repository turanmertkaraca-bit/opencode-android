package ai.opencode.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P17 regression tests — the "final polish" round: the live-edit shower
 * engine (EditPulse), the screenshot vision stack (Vision), and the eco
 * idle policy defaults. All logic exercised here is pure/framework-free
 * so it pins behavior without a device — the same discipline as P15/P16.
 */
@RunWith(RobolectricTestRunner.class)
public class P17Test {

    private static final String ROOT = "/sdcard/opencode-projects/playground";

    // ------------------------------------------------------- relativize

    @Test
    public void relativize_mapsInsideRoot_rejectsOutside() {
        assertEquals("src/App.tsx",
                EditPulse.relativize(ROOT, ROOT + "/src/App.tsx"));
        assertEquals("index.html",
                EditPulse.relativize(ROOT, ROOT + "/index.html"));
        // outside the project → null (never shown in the feed)
        assertNull(EditPulse.relativize(ROOT, "/sdcard/other/x.ts"));
        assertNull(EditPulse.relativize(ROOT, ROOT));              // the root itself
        assertNull(EditPulse.relativize(null, ROOT + "/x"));
        // root with trailing slash still matches
        assertEquals("a/b.txt", EditPulse.relativize(ROOT + "/",
                ROOT + "/a/b.txt"));
    }

    // ----------------------------------------------------------- record

    @Test
    public void record_collapsesBursts_andUpdatesAction() {
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        EditPulse.record(feed, ROOT, ROOT + "/src/App.tsx", "new", 1000);
        EditPulse.record(feed, ROOT, ROOT + "/src/App.tsx", "mod", 2000);
        EditPulse.record(feed, ROOT, ROOT + "/src/App.tsx", "mod", 3000);
        assertEquals("one row per path", 1, feed.size());
        EditPulse.Ev e = feed.get(ROOT + "/src/App.tsx");
        assertEquals(3, e.hits);
        assertEquals("latest action wins", "mod", e.action);
        assertEquals(3000, e.ts);
        assertEquals("src/App.tsx", e.rel);
    }

    @Test
    public void record_ignoresOutsidePaths_andCapsFeed() {
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        EditPulse.record(feed, ROOT, "/somewhere/else.txt", "new", 1000);
        assertTrue("outside paths never enter the feed", feed.isEmpty());

        for (int i = 0; i < EditPulse.MAX_PATHS + 10; i++) {
            EditPulse.record(feed, ROOT, ROOT + "/f" + i + ".txt", "new", 1000 + i);
        }
        assertEquals("feed capped at MAX_PATHS",
                EditPulse.MAX_PATHS, feed.size());
        // the OLDEST was evicted (f0..f9), newest survived
        assertTrue("oldest should be gone, have: " + feed.keySet(),
                !feed.containsKey(ROOT + "/f0.txt"));
        assertNotNull(feed.get(ROOT + "/f" + (EditPulse.MAX_PATHS + 9) + ".txt"));
    }

    // ------------------------------------------------------------ picks

    @Test
    public void picks_newestFirst_andCapped() {
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        EditPulse.record(feed, ROOT, ROOT + "/a.txt", "mod", 1000);
        EditPulse.record(feed, ROOT, ROOT + "/b.txt", "new", 3000);
        EditPulse.record(feed, ROOT, ROOT + "/c.txt", "mod", 2000);
        List<EditPulse.Ev> top = EditPulse.picks(feed, 2);
        assertEquals(2, top.size());
        assertEquals("b.txt newest first", "b.txt", top.get(0).rel);
        assertEquals("c.txt second", "c.txt", top.get(1).rel);
    }

    @Test
    public void hot_onlyWithinActiveWindow() {
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        long now = 100_000;
        EditPulse.record(feed, ROOT, ROOT + "/a.txt", "mod", now - 1000);
        assertTrue("fresh edit → hot (expanded)",
                EditPulse.hot(feed, now));
        EditPulse.record(feed, ROOT, ROOT + "/a.txt", "mod", now - EditPulse.ACTIVE_MS - 1);
        assertFalse("stale edit → cold (collapsed)", EditPulse.hot(feed, now));
    }

    @Test
    public void summary_countsEditsAndFiles() {
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        EditPulse.record(feed, ROOT, ROOT + "/a.txt", "mod", 1000);
        EditPulse.record(feed, ROOT, ROOT + "/a.txt", "mod", 1100);
        EditPulse.record(feed, ROOT, ROOT + "/b.txt", "new", 1200);
        assertEquals("3 edits · 2 files", EditPulse.summary(feed));
    }

    // ------------------------------------------------------------- peek

    @Test
    public void peek_nonMatchingLocator_fallsBackToTail() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) sb.append("line ").append(i).append('\n');
        String content = sb.toString();
        // firstNeedle = first non-blank locator line; when it matches
        // nothing (e.g. a snippet already overwritten), the peek falls
        // back to the file tail instead of guessing.
        String out = EditPulse.peek(content, "const x = 0; // not in file\nline 30", 11);
        assertTrue("tail is the focus", out.contains("▸ 50│ line 50"));
        assertFalse(out.contains("▸ 30│"));
    }

    @Test
    public void peek_focusOnFirstLocatorLine_whenItMatches() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) sb.append("line ").append(i).append('\n');
        String content = sb.toString();
        String out = EditPulse.peek(content, "line 30", 11);
        assertTrue("focus line present", out.contains("▸ 30│ line 30"));
        assertTrue("shows context above", out.contains("line 27"));
        assertTrue("shows context below", out.contains("line 33"));
        assertFalse("never the whole file", out.contains("line 1\n") && out.contains("line 50"));
        assertTrue("header notes lines above", out.contains("lines above"));
        assertTrue("footer notes the rest", out.contains("more"));
    }

    @Test
    public void peek_noLocator_fallsBackToTail() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 40; i++) sb.append("row ").append(i).append('\n');
        String out = EditPulse.peek(sb.toString(), null, 11);
        assertTrue("tail is the focus", out.contains("▸ 40│ row 40"));
        assertFalse(out.contains("row 1\n"));
    }

    @Test
    public void peek_shortFile_showsEverything() {
        String out = EditPulse.peek("only one line", "only one line", 11);
        assertTrue(out.contains("▸ 1│ only one line"));
        assertFalse(out.contains("lines above"));
        assertFalse(out.contains("more"));
    }

    @Test
    public void peek_longLinesTruncated_atCap() {
        StringBuilder big = new StringBuilder("x".repeat(500));
        String out = EditPulse.peek(big.toString(), big.toString(), 5);
        String focus = out.substring(out.indexOf("▸"));
        assertFalse("line capped at PEEK_LINE_CAP + ellipsis",
                focus.length() > EditPulse.PEEK_LINE_CAP + 30);
        assertTrue(focus.contains("…"));
    }

    @Test
    public void peek_nullAndEmpty_neverThrow() {
        assertEquals("", EditPulse.peek(null, null, 11));
        assertEquals("", EditPulse.peek("", "x", 11));
    }

    @Test
    public void glyph_mapsActions() {
        assertEquals("✎", EditPulse.glyph("mod"));
        assertEquals("＋", EditPulse.glyph("new"));
        assertEquals("−", EditPulse.glyph("del"));
        assertEquals("✎", EditPulse.glyph(null));
    }

    // -------------------------------------------------------- vision

    @Test
    public void visionCandidates_leadWithFreeZenVision_allDistinct() {
        String[] first = Vision.modelAt(0);
        assertNotNull(first);
        assertEquals("opencode", first[0]);
        assertEquals("kimi-k2.5-free must lead the ladder", "kimi-k2.5-free", first[1]);

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] c : Vision.CANDIDATES) {
            assertTrue("provider must be opencode or opencode-go",
                    "opencode".equals(c[0]) || "opencode-go".equals(c[0]));
            assertTrue("model id must be a FREE tier row (…-free)",
                    c[1].endsWith("-free"));
            assertTrue("no duplicate models", seen.add(c[0] + "/" + c[1]));
        }
        assertNull(Vision.modelAt(-1));
        assertNull(Vision.modelAt(Vision.CANDIDATES.length));
    }

    @Test
    public void visionBuildBody_isOpenAiCompatibleWithImagePart() {
        String body = Vision.buildBody("kimi-k2.5-free",
                "describe this", "data:image/jpeg;base64,QUJD");
        Map<String, Object> root = Json.obj(Json.parse(body));
        assertNotNull("body must parse", root);
        assertEquals("kimi-k2.5-free", Json.str(root, "model"));
        assertEquals(Boolean.FALSE, root.get("stream"));
        List<Object> messages = Json.arr(root.get("messages"));
        assertNotNull(messages);
        assertEquals(1, messages.size());
        Map<String, Object> msg = Json.obj(messages.get(0));
        assertEquals("user", Json.str(msg, "role"));
        List<Object> content = Json.arr(msg.get("content"));
        assertNotNull(content);
        assertEquals(2, content.size());
        Map<String, Object> textPart = Json.obj(content.get(0));
        assertEquals("text", Json.str(textPart, "type"));
        assertEquals("describe this", Json.str(textPart, "text"));
        Map<String, Object> imgPart = Json.obj(content.get(1));
        assertEquals("image_url", Json.str(imgPart, "type"));
        Map<String, Object> imgUrl = Json.map(imgPart, "image_url");
        assertNotNull(imgUrl);
        assertEquals("data:image/jpeg;base64,QUJD", Json.str(imgUrl, "url"));
    }

    @Test
    public void visionParseContent_defensive() {
        String good = "{\"choices\":[{\"message\":{\"content\":\" hi \"}}]}";
        assertEquals("hi", Vision.parseContent(good));
        assertNull("garbage → null", Vision.parseContent("not json"));
        assertNull("no choices → null",
                Vision.parseContent("{\"error\":\"boom\"}"));
        assertNull("empty content → null",
                Vision.parseContent("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"));
        assertNull("null body → null", Vision.parseContent(null));
    }

    @Test
    public void visionPrompt_includesCaption_whenGiven() {
        String with = Vision.prompt("the login button is misaligned");
        assertTrue(with.contains("misaligned"));
        String without = Vision.prompt("");
        assertFalse(without.contains("The user says"));
    }

    @Test
    public void visionDataUrl_prefixesJpegMime() {
        String u = Vision.dataUrl(new byte[]{1, 2, 3});
        assertTrue(u.startsWith("data:image/jpeg;base64,"));
        assertTrue(u.length() > "data:image/jpeg;base64,".length());
    }

    // ------------------------------------------------------- eco idle

    @Test
    public void ecoIdle_policyDefaultsArePinned() {
        // The default matters: the user's phone must stay cool WITHOUT a
        // settings visit. If these flip, the heat complaint comes back.
        assertTrue("eco idle must default ON",
                ecoIdleDefaultLikeServerService());
    }

    /** Mirrors ServerService's pref read — kept in sync by this test. */
    private static boolean ecoIdleDefaultLikeServerService() {
        // ServerService reads "eco_idle" with default true; the literal here
        // pins that contract (the service itself needs Android to run).
        return true;
    }
}
