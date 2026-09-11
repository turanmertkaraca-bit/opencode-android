package ai.opencode.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P40 — the poisoned-compaction detector and repair planner.
 *
 * THE MECHANISM (proven on the rig against the real opencode 1.18.25,
 * September field bug found and fixed at its source). The server keeps
 * every message in a SQLite store, and GET /session/{id}/message hands
 * the whole thread back — the app paints it, so the user always SEES
 * their history. But the payload the model receives is assembled from a
 * different starting point: the LAST compaction root (an assistant
 * message with agent "compaction"), written whenever a summarize/compact
 * runs. Everything before that root is deliberately excluded. When the
 * compacting model returns an EMPTY reply (free models do, especially on
 * flaky mobile networks), the root is still written — with no text. From
 * then on every payload is system prompt + a dangling prompt + the
 * current turn: flat token cost, and the model answers every message as
 * if the chat were fresh. The field saw exactly this: dozens of turns at
 * a flat ~44k tokens each while the UI kept showing the full history.
 *
 * WHY NOT THE API: re-summarizing inherits the poisoned root — the
 * summarizer itself only sees post-poison turns (rig-proven). Kills and
 * restarts do NOT cause or cure this. The only cure is removing the
 * poisoned root rows from the store while the server is stopped, which
 * this app can do: it owns the server lifecycle and the store file.
 *
 * THIS CLASS IS PURE: it parses the raw message array the hub already
 * pulls, decides poison, lists the victim ids, and emits the exact SQL
 * the device executor runs. The JVM suite pins every branch with
 * real-payload shapes, including the trap that USER messages carry a
 * `summary` metadata key of their own (git diffs) that must never be
 * mistaken for a compaction root — the marker is agent "compaction"
 * on an ASSISTANT message, nothing else.
 */
public final class CompactionPoison {

    private CompactionPoison() {}

    /** The agent value that marks a compaction root. */
    public static final String ROOT_AGENT = "compaction";

    /** One message, viewed through the lens the detector needs. */
    public static final class Msg {
        public final String id, parentID, role, agent, text;
        public final boolean synthetic;

        Msg(String id, String parentID, String role, String agent,
            String text, boolean synthetic) {
            this.id = id;
            this.parentID = parentID;
            this.role = role;
            this.agent = agent;
            this.text = text == null ? "" : text;
            this.synthetic = synthetic;
        }

        /** True for the assistant row a summarize run leaves behind. */
        public boolean isRoot() {
            return ROOT_AGENT.equals(agent)
                    && !"user".equals(role);
        }

        /** A real conversation row: user or assistant turn content. */
        public boolean isRealTurn() {
            if (synthetic || isRoot()) return false;
            return "user".equals(role) || "assistant".equals(role);
        }
    }

    /** Parse the raw GET /session/{id}/message array (info wrapper or
     *  flat item, parts on either). Pure; never throws. */
    public static List<Msg> parse(List<Object> raw) {
        List<Msg> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            Map<String, Object> item = Json.obj(o);
            if (item == null) continue;
            Map<String, Object> info = Json.map(item, "info");
            if (info == null) info = item;
            String id = Json.str(info, "id");
            if (id == null) continue;
            String role = Json.str(info, "role");
            String agent = Json.str(info, "agent");
            String parent = Json.str(info, "parentID");
            boolean synthetic = Boolean.TRUE.equals(info.get("synthetic"));
            StringBuilder text = new StringBuilder();
            List<Object> parts = Json.list(item, "parts");
            if (parts == null) parts = Json.list(info, "parts");
            if (parts != null) for (Object p : parts) {
                Map<String, Object> pm = Json.obj(p);
                if (pm == null) continue;
                if (!"text".equals(Json.str(pm, "type"))) continue;
                String t = Json.str(pm, "text");
                if (t != null && !t.isEmpty()) {
                    if (text.length() > 0) text.append('\n');
                    text.append(t);
                }
            }
            out.add(new Msg(id, parent, role, agent, text.toString(), synthetic));
        }
        return out;
    }

    /** The session's LAST compaction root, or null when none exists. */
    public static Msg lastRoot(List<Msg> msgs) {
        if (msgs == null) return null;
        Msg last = null;
        for (Msg m : msgs) if (m.isRoot()) last = m;
        return last;
    }

    /** A root whose text never landed — the poisoning condition. */
    public static boolean emptyRoot(Msg root) {
        return root != null && root.text.trim().isEmpty();
    }

    /**
     * True when the model's visible context starts at an EMPTY summary
     * while real turns exist before it — the amnesia state. A healthy
     * (non-empty) root is a legitimate compaction and never poisons; an
     * empty root with nothing before it loses nothing and is left alone.
     * The root's own trigger prompt (the summarizer's question, its
     * parent) is part of the compaction apparatus, not a conversation
     * turn — it rides INTO the payload even when the root is empty, so
     * it never counts as history worth keeping.
     */
    public static boolean poisoned(List<Msg> msgs) {
        Msg root = lastRoot(msgs);
        if (!emptyRoot(root)) return false;
        boolean sawRealTurn = false;
        for (Msg m : msgs) {
            if (root.id.equals(m.id)) break;
            if (m.id.equals(root.parentID)) continue;
            if (m.isRealTurn()) sawRealTurn = true;
        }
        return sawRealTurn;
    }

    /**
     * The ids to delete (server stopped): every EMPTY root plus its
     * trigger prompt. Healthy roots, real turns, and system rows are
     * never touched. Empty but superseded roots are cleaned too — the
     * last root is what the model sees, the older empty ones are dead
     * weight from repeated compactions.
     */
    public static List<String> victims(List<Msg> msgs) {
        List<String> out = new ArrayList<>();
        if (msgs == null) return out;
        for (Msg m : msgs) {
            if (!m.isRoot()) continue;
            if (!emptyRoot(m)) continue;
            if (!out.contains(m.id)) out.add(m.id);
            if (m.parentID != null && !m.parentID.isEmpty()
                    && !out.contains(m.parentID)) out.add(m.parentID);
        }
        return out;
    }

    /** Batch size for the IN (...) delete — well under the 999 SQLite
     *  bind-parameter wall, so a pathological session still repairs. */
    public static final int SQL_BATCH = 400;

    /** One bound-parameter DELETE for `table` where `column` IN (n
     *  placeholders). Package-private: the device executor walks the same
     *  batching the planner emits; the suite pins the shape. */
    static String sqlFor(String table, String column, int n) {
        StringBuilder b = new StringBuilder("DELETE FROM `").append(table)
                .append("` WHERE `").append(column).append("` IN (");
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(',');
            b.append('?');
        }
        return b.append(')').toString();
    }

    /**
     * The exact SQL the device executor runs, in order: parts first
     * (they reference their message), then the messages themselves.
     * Each statement is "?"-bound — the executor supplies the ids, so a
     * crafted id can never turn into SQL. Pure; the suite pins the
     * shape, the order, and the batching.
     */
    public static List<String> deleteSql(List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return out;
        for (int from = 0; from < ids.size(); from += SQL_BATCH) {
            int to = Math.min(ids.size(), from + SQL_BATCH);
            out.add(sqlFor("part", "message_id", to - from));
        }
        for (int from = 0; from < ids.size(); from += SQL_BATCH) {
            int to = Math.min(ids.size(), from + SQL_BATCH);
            out.add(sqlFor("message", "id", to - from));
        }
        return out;
    }

    /** The one honest chat line when poison is found. */
    public static String note() {
        return "this chat's memory broke: a compact ran with a model that "
                + "returned an empty summary, so the model has been seeing "
                + "only the latest message. Repairing — the full history "
                + "stays on screen and goes back to the model in a moment";
    }

    /** The one honest chat line when the repair verified clean. */
    public static String curedNote() {
        return "context repaired — the full conversation reaches the model "
                + "again; nothing was lost";
    }

    /** The honest line when the repair could not be verified. */
    public static String failedNote(String detail) {
        return "context repair could not be verified (" + detail
                + ") — a Context repair note keeps riding sends until this "
                + "holds; try ⌘ → Restart server and reopen the chat";
    }
}
