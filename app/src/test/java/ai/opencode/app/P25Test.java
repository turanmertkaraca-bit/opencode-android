package ai.opencode.app;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P25 — the runs-outlive-the-chat release, pinned on the host JVM:
 * the context-depth meter, the model context-window parse, and the edit
 * shower's compact live tree. Pure statics only — no device, no Robolectric
 * (RunHub pipeline pins live in P25HubTest).
 */
public class P25Test {

    // ------------------------------------------------- Resilience.contextMeter

    @Test
    public void contextMeter_formatsDepthVsWindow() {
        assertEquals("48k / 200k · 24%", Resilience.contextMeter(48_000, 200_000));
        assertEquals("50k / 200k · 25%", Resilience.contextMeter(50_000, 200_000));
        assertEquals("1.2k / 8k · 15%", Resilience.contextMeter(1_200, 8_192 - 192));
    }

    @Test
    public void contextMeter_roundsPercentToNearest() {
        // 48740/200000 = 24.37% → 24
        assertEquals("48.7k / 200k · 24%", Resilience.contextMeter(48_740, 200_000));
        // 24999/200000 = 12.4995% → 12
        assertEquals("25k / 200k · 12%", Resilience.contextMeter(24_999, 200_000));
    }

    @Test
    public void contextMeter_overWindowClampsTo99Plus() {
        assertEquals("250k / 200k · 99%+", Resilience.contextMeter(250_000, 200_000));
        assertEquals("200k / 200k · 99%+", Resilience.contextMeter(200_000, 200_000));
    }

    @Test
    public void contextMeter_unknownLimitShowsDepthOnly() {
        assertEquals("48k", Resilience.contextMeter(48_000, 0));
        assertEquals("48k", Resilience.contextMeter(48_000, -5));
    }

    @Test
    public void contextMeter_zeroTokensIsEmpty() {
        assertEquals("", Resilience.contextMeter(0, 200_000));
        assertEquals("", Resilience.contextMeter(-1, 200_000));
    }

    @Test
    public void contextMeter_largeWindowsUseMFormat() {
        assertEquals("1.5M / 2M · 75%", Resilience.contextMeter(1_500_000, 2_000_000));
    }

    // ------------------------------------------------- Models context window

    @Test
    public void parseCtx_readsModelsDevLimitShape() {
        Map<String, Object> m = new HashMap<>();
        Map<String, Object> limit = new HashMap<>();
        limit.put("context", 200000);
        limit.put("output", 8192);
        m.put("limit", limit);
        assertEquals(200_000L, Models.parseCtx(m));
    }

    @Test
    public void parseCtx_fallsBackToTopLevelFields() {
        Map<String, Object> m = new HashMap<>();
        m.put("context_window", 8192);
        assertEquals(8_192L, Models.parseCtx(m));
        Map<String, Object> m2 = new HashMap<>();
        m2.put("context", 131072);
        assertEquals(131_072L, Models.parseCtx(m2));
    }

    @Test
    public void parseCtx_rejectsJunkAndAbsents() {
        assertEquals(0L, Models.parseCtx(null));
        assertEquals(0L, Models.parseCtx(new HashMap<>()));
        Map<String, Object> small = new HashMap<>();
        small.put("context", 100);              // under 1k = junk
        assertEquals(0L, Models.parseCtx(small));
        Map<String, Object> huge = new HashMap<>();
        huge.put("context", 999_999_999_999L);  // over 100M = junk
        assertEquals(0L, Models.parseCtx(huge));
    }

    @Test
    public void contextLimitFor_findsThePickedModel() {
        Models.Prov p = new Models.Prov();
        p.id = "zen"; p.name = "Zen";
        Models.Mdl a = new Models.Mdl();
        a.id = "free-a"; a.ctx = 200_000;
        Models.Mdl b = new Models.Mdl();
        b.id = "free-b";                        // ctx 0 = unknown
        p.models.add(a);
        p.models.add(b);
        java.util.List<Models.Prov> provs = new java.util.ArrayList<>();
        provs.add(p);
        assertEquals(200_000L, Models.contextLimitFor(provs, "zen", "free-a"));
        assertEquals(0L, Models.contextLimitFor(provs, "zen", "free-b"));
        assertEquals(0L, Models.contextLimitFor(provs, "nope", "free-a"));
        assertEquals(0L, Models.contextLimitFor(null, "zen", "free-a"));
    }

    // ------------------------------------------------- EditPulse.tree

    private static EditPulse.Ev ev(String rel, String abs, String action, long ts) {
        EditPulse.Ev e = new EditPulse.Ev();
        e.rel = rel;
        e.abs = abs;
        e.action = action;
        e.ts = ts;
        e.hits = 1;
        return e;
    }

    @Test
    public void tree_groupsTouchedFilesUnderTheirDirs() {
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        feed.put("/r/a/b/c.tsx", ev("a/b/c.tsx", "/r/a/b/c.tsx", "mod", 100));
        feed.put("/r/a/d.tsx", ev("a/d.tsx", "/r/a/d.tsx", "new", 200));
        feed.put("/r/root.txt", ev("root.txt", "/r/root.txt", "mod", 300));

        List<EditPulse.TNode> root = EditPulse.tree(feed, 40);
        // root level: dir "a" + file root.txt, newest first → root.txt(300), a(200)
        assertEquals(2, root.size());
        assertEquals("newest first at root level",
                "root.txt", root.get(0).ev.rel);
        assertEquals("a", root.get(1).dir);

        // dir "a" holds dir "a/b" + file a/d.tsx
        EditPulse.TNode a = root.get(1);
        assertEquals(2, a.kids.size());
        assertEquals("a/d.tsx", a.kids.get(0).ev.rel);      // ts 200 > 100
        assertEquals("a/b", a.kids.get(1).dir);
        assertEquals("dir ts = newest descendant", 200L, a.ts);
        assertEquals("dir hits = sum of descendants", 2, a.hits);

        // dir "a/b" holds the nested file
        assertEquals(1, a.kids.get(1).kids.size());
        assertEquals("a/b/c.tsx", a.kids.get(1).kids.get(0).ev.rel);
    }

    @Test
    public void tree_neverRendersUntouchedDirectories() {
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        feed.put("/r/x/y/z.tsx", ev("x/y/z.tsx", "/r/x/y/z.tsx", "mod", 100));
        List<EditPulse.TNode> root = EditPulse.tree(feed, 40);
        assertEquals(1, root.size());
        // node.dir carries the FULL rel dir path; the renderer shows the
        // last segment — the tree here is x → y → z.tsx
        assertEquals("x", root.get(0).dir);
        assertEquals(1, root.get(0).kids.size());
        assertEquals("x/y", root.get(0).kids.get(0).dir);
        assertEquals("only the touched leaf is inside", 1,
                root.get(0).kids.get(0).kids.size());
    }

    @Test
    public void tree_capKeepsTheNewestFilesAndDropsEmptyDirs() {
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        feed.put("/r/old.txt", ev("old.txt", "/r/old.txt", "mod", 1));
        feed.put("/r/mid.txt", ev("mid.txt", "/r/mid.txt", "mod", 2));
        feed.put("/r/new.txt", ev("new.txt", "/r/new.txt", "mod", 3));
        List<EditPulse.TNode> root = EditPulse.tree(feed, 2);
        assertEquals(2, root.size());
        assertEquals("new.txt", root.get(0).ev.rel);
        assertEquals("mid.txt", root.get(1).ev.rel);
        assertFalse("the evicted file must not survive",
                "old.txt".equals(root.get(0).ev.rel)
                        || "old.txt".equals(root.get(1).ev.rel));
    }

    @Test
    public void tree_emptyFeedIsEmpty() {
        assertTrue(EditPulse.tree(new HashMap<>(), 40).isEmpty());
    }

    @Test
    public void ancestors_walksOutermostFirst() {
        assertEquals(java.util.Arrays.asList("a", "a/b"),
                EditPulse.ancestors("a/b/c.tsx"));
        assertTrue(EditPulse.ancestors("root.txt").isEmpty());
        assertTrue(EditPulse.ancestors(null).isEmpty());
        // a leading slash yields no ancestor (defensive, never happens for rel)
        assertTrue(EditPulse.ancestors("/abs.txt").isEmpty());
    }
}
