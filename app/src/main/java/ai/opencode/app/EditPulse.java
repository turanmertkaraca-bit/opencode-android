package ai.opencode.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P17 — the live-edit engine behind the chat's "edit shower" card.
 *
 * The user: "live directory changes on the chat app itself … animated and
 * fluid, doesn't take too much space in chat, only expands when it's
 * currently being worked on, and clicking a file shows the exact line
 * it's editing — not the full file."
 *
 * Design, in one breath: ONE slim card lives in the transcript while the
 * agent works. A DirWatcher (P16, event-driven — zero polling) feeds it.
 * Collapsed it is a single line ("● LIVE · 3 files · src/App.tsx"). While
 * edits are FRESH (≤ ACTIVE_MS) it auto-expands to the newest MAX_SHOWN
 * touched files with a staggered slide-in, then collapses itself again —
 * no timers polling, just one scheduled collapse after the last burst.
 * Tapping a file row opens a PEEK: ≤ PEEK_LINES numbered lines around the
 * edit point (located via the edit tool's own new-content snippet, tail
 * fallback), never the whole file — so a huge rewrite can't flood the
 * chat. When the run ends the card settles into a quiet
 * "✎ 6 edits · 3 files" record row.
 *
 * Everything geometry/decision-shaped is a pure static here so the JVM
 * suite can pin it without a device.
 */
public final class EditPulse {

    /** Expanded only while edits are this fresh (ms since last change). */
    public static final long ACTIVE_MS = 4000;
    /** File rows visible when expanded — a shower, not a flood. */
    public static final int MAX_SHOWN = 5;
    /** Feed memory cap (oldest evicted). */
    public static final int MAX_PATHS = 40;
    /** Peek window size (lines) and context around the focused line. */
    public static final int PEEK_LINES = 11;
    public static final int PEEK_CTX = 3;
    /** Hard cap per peek line — a minified bundle can't blow the layout. */
    public static final int PEEK_LINE_CAP = 120;

    /** One watched path's latest state. */
    public static final class Ev {
        public String rel;      // project-relative display path
        public String abs;      // absolute path
        public String action;   // new | mod | del
        public long ts;         // last change, ms
        public int hits;        // changes collapsed into this row
        public boolean seen;    // entrance animation already played?
    }

    private EditPulse() {}

    // ------------------------------------------------------------- feed

    /** Project-relative display path, or null when outside the root. */
    public static String relativize(String root, String path) {
        if (root == null || path == null) return null;
        String r = root.endsWith("/") ? root : root + "/";
        if (!path.startsWith(r)) return null;
        String rel = path.substring(r.length());
        return rel.isEmpty() ? null : rel;
    }

    /** Record one fs change. Bursts on the same path collapse (hits++). */
    public static void record(Map<String, EditPulse.Ev> feed, String root,
                              String path, String action, long now) {
        if (feed == null || relativize(root, path) == null) return;
        EditPulse.Ev e = feed.get(path);
        boolean isNew = e == null;
        if (isNew) {
            e = new EditPulse.Ev();
            e.abs = path;
            e.rel = relativize(root, path);
            e.hits = 1;
        } else {
            e.hits++;
        }
        // Stamp BEFORE the put: eviction compares ts, and a just-created
        // entry must never sit in the map with ts=0 — the P17Test cap test
        // caught exactly that (the new file got evicted as "oldest").
        e.action = action;
        e.ts = now;
        e.seen = false;
        if (isNew) {
            feed.put(path, e);
            if (feed.size() > MAX_PATHS) evictOldest(feed);
        }
    }

    private static void evictOldest(Map<String, EditPulse.Ev> feed) {
        String oldest = null;
        long ots = Long.MAX_VALUE;
        for (Map.Entry<String, EditPulse.Ev> en : feed.entrySet()) {
            if (en.getValue().ts < ots) { ots = en.getValue().ts; oldest = en.getKey(); }
        }
        if (oldest != null) feed.remove(oldest);
    }

    /** Newest-first picks, capped at {@code cap}. */
    public static List<EditPulse.Ev> picks(Map<String, EditPulse.Ev> feed, int cap) {
        List<EditPulse.Ev> out = new ArrayList<>(feed.values());
        Collections.sort(out, (a, b) -> Long.compare(b.ts, a.ts));
        if (cap > 0 && out.size() > cap) return new ArrayList<>(out.subList(0, cap));
        return out;
    }

    /** True while any event is fresher than ACTIVE_MS — the auto-expand window. */
    public static boolean hot(Map<String, EditPulse.Ev> feed, long now) {
        for (EditPulse.Ev e : feed.values()) {
            if (now - e.ts <= ACTIVE_MS) return true;
        }
        return false;
    }

    /** Settled-state summary: "6 edits · 3 files". */
    public static String summary(Map<String, EditPulse.Ev> feed) {
        int edits = 0;
        for (EditPulse.Ev e : feed.values()) edits += e.hits;
        return edits + " edit" + (edits == 1 ? "" : "s") + " · "
                + feed.size() + " file" + (feed.size() == 1 ? "" : "s");
    }

    /** "✎" / "＋" / "−" glyph per action. */
    public static String glyph(String action) {
        if ("new".equals(action)) return "＋";
        if ("del".equals(action)) return "−";
        return "✎";
    }

    // ------------------------------------------------------------- tree

    /** P25: one node of the compact live tree — either a DIRECTORY
     *  (ev == null, kids = its sub-dirs + touched files) or a FILE leaf
     *  (ev != null). Dirs only exist for paths that actually contain
     *  touched files — untouched directories are pure noise and never
     *  rendered. ts = newest descendant change (recency sort key). */
    public static final class TNode {
        /** Directory path relative to the project root ("" = root);
         *  null for file leaves. */
        public String dir;
        public EditPulse.Ev ev;                 // null for dir nodes
        public final List<TNode> kids = new ArrayList<>();
        public long ts;                         // dir: newest descendant
        public int hits;                        // dir: sum of descendant edits
    }

    /**
     * P25: the edit shower's tree. Replaces the flat "newest 5 files"
     * list: touched files group under their (only-touched) directories,
     * every level sorted newest-first so the freshest activity floats to
     * the top of its branch. Renders at most {@code fileCap} FILE leaves
     * (the newest win — the same picks() semantics the flat list had);
     * directories beyond that disappear with their (untouched) files.
     * Pure; JVM-pinned in P25Test.
     */
    public static List<TNode> tree(Map<String, Ev> feed, int fileCap) {
        List<Ev> top = picks(feed, fileCap > 0 ? fileCap : MAX_PATHS);
        // dir path (rel, "" = root) → node
        Map<String, TNode> dirs = new LinkedHashMap<>();
        List<TNode> leaves = new ArrayList<>();
        for (Ev e : top) {
            TNode leaf = new TNode();
            leaf.ev = e;
            leaf.ts = e.ts;
            leaves.add(leaf);
            // materialize the ancestor dir chain: "a/b/c.tsx" → "a", "a/b"
            String rel = e.rel == null ? "" : e.rel;
            int slash = rel.lastIndexOf('/');
            if (slash < 0) continue;                    // root file
            String dir = rel.substring(0, slash);
            while (true) {
                TNode d = dirs.get(dir);
                if (d == null) {
                    d = new TNode();
                    d.dir = dir;
                    d.ts = e.ts;
                    dirs.put(dir, d);
                }
                if (e.ts > d.ts) d.ts = e.ts;
                d.hits += e.hits;
                int up = dir.lastIndexOf('/');
                if (up < 0) break;                      // reached root level
                dir = dir.substring(0, up);
            }
        }
        // hang leaves + dirs under their parents
        List<TNode> root = new ArrayList<>();
        for (TNode leaf : leaves) {
            String rel = leaf.ev.rel == null ? "" : leaf.ev.rel;
            int slash = rel.lastIndexOf('/');
            if (slash < 0) {
                root.add(leaf);
                continue;
            }
            TNode parent = dirs.get(rel.substring(0, slash));
            if (parent != null) parent.kids.add(leaf);
            else root.add(leaf);                        // defensive: never lose a file
        }
        for (TNode d : dirs.values()) {
            int slash = d.dir.lastIndexOf('/');
            if (slash < 0) root.add(d);
            else {
                TNode parent = dirs.get(d.dir.substring(0, slash));
                if (parent != null) parent.kids.add(d);
                else root.add(d);
            }
        }
        // every level sorts newest-first (dirs carry their newest child ts)
        sortNodes(root);
        for (TNode d : dirs.values()) sortNodes(d.kids);
        return root;
    }

    private static void sortNodes(List<TNode> nodes) {
        Collections.sort(nodes, (a, b) -> Long.compare(b.ts, a.ts));
    }

    /** P25: the ancestor dir chain of a rel path, outermost first:
     *  "a/b/c.tsx" → ["a", "a/b"]. Root files → empty list. Pure. */
    public static List<String> ancestors(String rel) {
        List<String> out = new ArrayList<>();
        if (rel == null) return out;
        int i = rel.indexOf('/');
        while (i > 0) {
            out.add(rel.substring(0, i));
            i = rel.indexOf('/', i + 1);
        }
        return out;
    }

    // ------------------------------------------------------------- peek

    /**
     * The line-precise peek. Locates {@code locator}'s first meaningful
     * line inside {@code content} and returns a numbered window of at most
     * {@code maxLines} around it (focus marked with "▸"); falls back to
     * the file tail when the locator can't be found (shell-written files).
     * Pure; deliberately returns a plain String so the chat renders it in
     * one cheap TextView — never a per-line view flood.
     */
    public static String peek(String content, String locator, int maxLines) {
        if (maxLines <= 0) maxLines = PEEK_LINES;
        if (content == null || content.isEmpty()) return "";
        String[] all = content.split("\n", -1);
        // a POSIX-final newline is not a 51st line — drop the phantom
        int n = all.length;
        if (n > 1 && all[n - 1].isEmpty()) n--;
        String[] lines = all;

        int focus = -1;
        String needle = firstNeedle(locator);
        if (needle != null) {
            for (int i = 0; i < n; i++) {
                if (lines[i].contains(needle)) { focus = i; break; }
            }
        }
        if (focus < 0) focus = Math.max(0, n - 1);   // tail fallback

        int from = Math.max(0, focus - PEEK_CTX);
        int to = Math.min(n, from + maxLines);
        from = Math.max(0, Math.min(from, to - 1));

        StringBuilder b = new StringBuilder();
        if (from > 0) b.append("  … ").append(from)
                .append(from == 1 ? " line" : " lines").append(" above\n");
        for (int i = from; i < to; i++) {
            String t = lines[i];
            if (t.length() > PEEK_LINE_CAP) t = t.substring(0, PEEK_LINE_CAP) + "…";
            b.append(i == focus ? "▸ " : "  ")
                    .append(i + 1).append("│ ").append(t);
            if (i < to - 1) b.append('\n');
        }
        if (to < n) b.append('\n').append("  … +").append(n - to).append(" more");
        return b.toString();
    }

    /** First non-blank line of the locator, trimmed and capped — the needle. */
    static String firstNeedle(String locator) {
        if (locator == null) return null;
        for (String l : locator.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) return t.length() > 80 ? t.substring(0, 80) : t;
        }
        return null;
    }
}
