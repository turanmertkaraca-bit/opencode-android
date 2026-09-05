package ai.opencode.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * P27 — pure-logic pins for the four phases:
 *
 *   phase 1a  live-tree pin semantics (via EditPulse hot/cold + hub rules
 *             that are pure enough to pin here: pin/unpin/expansion math
 *             lives in RunHub behind Android singletons, so its VIEW-side
 *             decision inputs — EditPulse.hot, summary, tree flatten — are
 *             pinned here on real feeds);
 *   phase 2   DebianTrim: every trim rule, every keep rule, hygiene targets;
 *   phase 3   middle-ellipsis for long paths (the mention/tree rendering);
 *   phase 4   Mentions: extraction (fenced inert, inline code linkable,
 *             bare relative, absolute, URL rejection), resolution
 *             (relative/absolute/sandbox forms, escape rejection),
 *             tool-file paths.
 */
public class P27Test {

    // ================================================== phase 1a inputs

    @Test
    public void editPulse_hotWindow_drivesAutoExpansion() {
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        long now = System.currentTimeMillis();
        EditPulse.record(feed, "/proj", "/proj/src/App.tsx", "mod", now - 1000);
        assertTrue("fresh edit → hot → auto-expanded",
                EditPulse.hot(feed, now));
        assertTrue("quiet past ACTIVE_MS → cold → auto-collapsed",
                !EditPulse.hot(feed, now + EditPulse.ACTIVE_MS + 1));
    }

    @Test
    public void editPulse_tree_keysStayStableAcrossUpdates() {
        // the in-place reconcile diffs by key (abs for files, dir for dirs)
        // — pin that the keys the view uses are stable as events repeat
        Map<String, EditPulse.Ev> feed = new HashMap<>();
        long now = System.currentTimeMillis();
        EditPulse.record(feed, "/proj", "/proj/src/a.ts", "mod", now);
        EditPulse.record(feed, "/proj", "/proj/src/a.ts", "mod", now + 10);
        EditPulse.record(feed, "/proj", "/proj/src/b.ts", "new", now + 20);
        List<EditPulse.TNode> tree = EditPulse.tree(feed, EditPulse.MAX_PATHS);
        assertEquals(1, tree.size());                 // one "src" dir
        assertEquals("src", tree.get(0).dir);
        assertEquals(2, tree.get(0).kids.size());     // a.ts, b.ts
        assertEquals(3, tree.get(0).hits);            // 2 + 1 edits collapsed
    }

    // ================================================== phase 2: DebianTrim

    @Test
    public void trim_dropsDocsManInfoGroff() {
        assertTrue(DebianTrim.trimmed("usr/share/doc/git/manual.html"));
        assertTrue(DebianTrim.trimmed("/usr/share/doc"));
        assertTrue(DebianTrim.trimmed("usr/share/man/man1/git.1.gz"));
        assertTrue(DebianTrim.trimmed("usr/share/info/gawk.info.gz"));
        assertTrue(DebianTrim.trimmed("usr/share/groff/font/devps/DESC"));
    }

    @Test
    public void trim_zoneinfo_keepsAHandfulDropsLegacy() {
        assertTrue(DebianTrim.trimmed("usr/share/zoneinfo/America/Argentina/Ushuaia"));
        assertTrue(DebianTrim.trimmed("usr/share/zoneinfo/posix/America/New_York"));
        assertTrue(DebianTrim.trimmed("usr/share/zoneinfo/right/UTC"));
        assertFalse("kept zone survives", DebianTrim.trimmed("usr/share/zoneinfo/Europe/Istanbul"));
        assertFalse("UTC survives", DebianTrim.trimmed("usr/share/zoneinfo/UTC"));
    }

    @Test
    public void trim_perlGoes_aptAndGitStay() {
        assertTrue(DebianTrim.trimmed("usr/bin/perl"));
        assertTrue(DebianTrim.trimmed("usr/bin/perl5.36.0"));
        assertTrue(DebianTrim.trimmed("usr/share/perl/5.36.0/strict.pm"));
        assertTrue(DebianTrim.trimmed("usr/share/perl5/Debconf/ConfModule.pm"));
        assertTrue(DebianTrim.trimmed("usr/lib/x86_64-linux-gnu/libperl.so.5.36.0"));
        assertTrue(DebianTrim.trimmed("usr/lib/aarch64-linux-gnu/perl/5.36.0/CORE/libperl.so"));
        // the escape hatch stays usable
        assertFalse("apt must survive", DebianTrim.trimmed("usr/bin/apt-get"));
        assertFalse("dpkg must survive", DebianTrim.trimmed("usr/bin/dpkg"));
        assertFalse("git must survive", DebianTrim.trimmed("usr/bin/git"));
        assertFalse("bash must survive", DebianTrim.trimmed("bin/bash"));
        assertFalse("curl must survive", DebianTrim.trimmed("usr/bin/curl"));
        assertFalse("jq must survive", DebianTrim.trimmed("usr/bin/jq"));
        assertFalse("ca certs must survive", DebianTrim.trimmed("usr/share/ca-certificates/mozilla/ISRG_Root_X1.crt"));
    }

    @Test
    public void trim_localeArchiveGoes_defaultLocaleStays() {
        assertTrue(DebianTrim.trimmed("usr/lib/locale/locale-archive"));
        assertTrue(DebianTrim.trimmed("usr/lib/x86_64-linux-gnu/locale/locale-archive"));
        assertFalse("C.UTF-8 builtin stays", DebianTrim.trimmed("usr/lib/locale/C.UTF-8/LC_CTYPE"));
    }

    @Test
    public void trim_trimmedDir_fastPrune() {
        assertTrue(DebianTrim.trimmedDir("usr/share/doc"));
        assertTrue(DebianTrim.trimmedDir("usr/share/zoneinfo/posix"));
        assertFalse("zoneinfo itself must be WALKED (files decided individually)",
                DebianTrim.trimmedDir("usr/share/zoneinfo"));
    }

    @Test
    public void hygiene_targetsArePureCaches() {
        String[] t = DebianTrim.bootHygieneTargets();
        boolean npm = false, apt = false;
        for (String s : t) {
            if (s.equals("root/.npm")) npm = true;
            if (s.equals("var/lib/apt/lists")) apt = true;
            assertFalse("never the project or the clone", s.equals("root/project")
                    || s.equals("root/opencode-android"));
        }
        assertTrue(npm && apt);
        assertTrue(DebianTrim.scratchFile("probe-rss2.log"));
        assertFalse("directories are NEVER scratch", DebianTrim.scratchFile("opencode-android"));
        assertFalse(DebianTrim.scratchFile("something.important"));
    }

    // ================================================== phase 3: ellipsis

    @Test
    public void middleEllipsize_keepsFilenameTail() {
        String p = "/storage/emulated/0/opencode-projects/playground/"
                + "src/deep/nested/module/KubernetesClientFactoryImpl.java";
        String out = Mentions.middleEllipsize(p, 40);
        assertTrue("within budget", out.length() <= 40);
        assertTrue("filename tail visible", out.endsWith("KubernetesClientFactoryImpl.java")
                || out.contains("Kubernetes"));
        assertTrue("middle ellipsis present", out.contains("…"));
        assertEquals("short path untouched",
                "/a/b.txt", Mentions.middleEllipsize("/a/b.txt", 40));
    }

    // ================================================== phase 4: mentions

    private static List<String> paths(String text) {
        List<String> out = new ArrayList<>();
        for (Mentions.Hit h : Mentions.extract(text)) out.add(h.path);
        return out;
    }

    @Test
    public void extract_backtickedAndBare_andAbsolute() {
        List<String> p = paths("Fixed `src/App.tsx` and also touched src/lib/util.go.\n"
                + "The absolute one: /proj/docs/readme.md is project-absolute.");
        assertTrue(p.contains("src/App.tsx"));
        assertTrue(p.contains("src/lib/util.go"));
        assertTrue(p.contains("/proj/docs/readme.md"));
    }

    @Test
    public void extract_fencedCodeIsInert() {
        List<String> p = paths("See src/App.tsx\n"
                + "```bash\n"
                + "cat src/inside/fence.rs\n"
                + "```\n"
                + "done `src/after.md`");
        assertTrue("outside mention found", p.contains("src/App.tsx"));
        assertFalse("fenced code NEVER linked", p.contains("src/inside/fence.rs"));
        assertTrue("inline code after the fence linkable", p.contains("src/after.md"));
    }

    @Test
    public void extract_rejectsUrlsJunkAndSingleWords() {
        List<String> p = paths("Visit https://example.com/a/b/c.png or "
                + "ftp://x/y.txt — but `README.md` is fine, as is just word.");
        assertFalse("URLs never become file links", p.contains("https://example.com/a/b/c.png"));
        assertTrue("backticked filename with extension is linkable", p.contains("README.md"));
        assertFalse("bare single word stays text", p.contains("word"));
    }

    @Test
    public void resolve_relativeDottedAndAbsolute() {
        String root = "/proj";
        assertEquals("/proj/src/App.tsx", Mentions.resolve(root, "src/App.tsx"));
        assertEquals("/proj/src/App.tsx", Mentions.resolve(root, "./src/App.tsx"));
        assertEquals("/proj/src/App.tsx", Mentions.resolve(root + "/", "src/App.tsx"));
        assertEquals("/proj/x", Mentions.resolve(root, "/proj/x"));
        assertNull("outside-project absolute is not ours", Mentions.resolve(root, "/etc/passwd"));
        assertNull("never climb out", Mentions.resolve(root, "../escape.txt"));
        assertNull("rootless absolute rejected", Mentions.resolve(null, "/proj/x"));
    }

    @Test
    public void plausible_shapeFilter() {
        assertTrue(Mentions.plausible("src/App.tsx"));
        assertTrue(Mentions.plausible("README.md"));
        assertFalse(Mentions.plausible("src/"));
        assertFalse(Mentions.plausible("/x"));
        assertFalse(Mentions.plausible(".."));
        assertFalse(Mentions.plausible("ab"));
    }

    @Test
    public void toolFilePath_parsesTitles() {
        assertEquals("/proj/src/App.tsx", Mentions.toolFilePath("edit", "/proj/src/App.tsx"));
        assertEquals("/proj/a b.txt", Mentions.toolFilePath("write", "/proj/a b.txt + 2 more"));
        assertEquals("src/App.tsx", Mentions.toolFilePath("read", "src/App.tsx"));
        assertNull("bash cards carry no file", Mentions.toolFilePath("bash", "ls -la"));
        assertNull("not a path", Mentions.toolFilePath("edit", "did the thing"));
    }
}
