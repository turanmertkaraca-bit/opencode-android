package ai.opencode.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P50 — the question tool, ANSWERABLE.
 *
 * What the field hit: the agent's `question` tool (and the plan-mode
 * approval ask) renders as an ordinary tool card stuck on "running…"
 * forever. The server blocks the whole run waiting for an answer the
 * app could never give — no UI, no route wired, the agent simply hangs
 * until the user gives up. The card was display-only for tool calls
 * and dead weight for everything interactive.
 *
 * Contract verified against the SHIPPED v1.18.25 binary (strings +
 * embedded API client):
 *
 *   GET  /api/question/request                          — all pending asks
 *   GET  /api/session/{sid}/question                    — pending for one session
 *   POST /api/session/{sid}/question/{rid}/reply        — body {"answers":[[label,...],...]} → 204
 *   POST /api/session/{sid}/question/{rid}/reject       — skip → 204
 *   SSE  question.asked      {id, sessionID, questions, tool:{messageID,callID}}
 *   SSE  question.replied    {sessionID, requestID}
 *   SSE  question.rejected   {sessionID, requestID}
 *   (v2 aliases question.v2.asked/.replied/.rejected carry the same
 *    properties — handled identically.)
 *
 * Each answer is the array of selected labels for the question at the
 * same index; the server's own echo joins labels with ", " and renders
 * an empty array as "Unanswered" — so an unanswered question ships as
 * [] rather than being dropped (indexes must stay aligned).
 *
 * Pure, dependency-free, JVM-pinned — no Android imports on purpose.
 */
public final class Questions {

    private Questions() {}

    // ------------------------------------------------------- event names

    /** SSE types that ADD or REPLACE a pending ask. */
    public static boolean isAskedType(String t) {
        return "question.asked".equals(t)
                || "question.v2.asked".equals(t);
    }

    /** SSE types that REMOVE a pending ask. */
    public static boolean isGoneType(String t) {
        return "question.replied".equals(t) || "question.rejected".equals(t)
                || "question.v2.replied".equals(t) || "question.v2.rejected".equals(t);
    }

    // ------------------------------------------------------------ model

    /** One selectable option (label + human description). */
    public static final class Opt {
        public final String label, description;
        Opt(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    /** One question: prompt text, optional header, options, multi flag. */
    public static final class Prompt {
        public final String question, header;
        public final boolean multiple;
        public final List<Opt> options;
        Prompt(String question, String header, boolean multiple, List<Opt> options) {
            this.question = question;
            this.header = header;
            this.multiple = multiple;
            this.options = options;
        }
    }

    /** One pending request, normalized. */
    public static final class Req {
        public final String id, sessionID;
        public final List<Prompt> prompts;
        Req(String id, String sessionID, List<Prompt> prompts) {
            this.id = id;
            this.sessionID = sessionID;
            this.prompts = prompts;
        }
    }

    // ------------------------------------------------------------ parse

    /**
     * Parse a question.asked properties payload → Req (null when unusable).
     * Defensive: a malformed frame degrades to null, never a throw — the
     * hub's event loop must survive anything the server emits.
     */
    public static Req parse(Map<String, Object> props) {
        if (props == null) return null;
        try {
            String id = Json.str(props, "id");
            if (id == null || id.isEmpty()) id = Json.str(props, "requestID");
            String sid = Json.str(props, "sessionID");
            if (id == null || id.isEmpty() || sid == null || sid.isEmpty())
                return null;
            List<Object> qs = Json.list(props, "questions");
            if (qs == null || qs.isEmpty()) return null;
            List<Prompt> prompts = new ArrayList<>();
            for (Object o : qs) {
                if (!(o instanceof Map)) return null;
                @SuppressWarnings("unchecked")
                Map<String, Object> q = (Map<String, Object>) o;
                String text = Json.str(q, "question");
                if (text == null || text.isEmpty()) text = Json.str(q, "text");
                if (text == null) text = "";
                String header = Json.str(q, "header");
                boolean multiple = Boolean.TRUE.equals(q.get("multiple"));
                List<Opt> opts = new ArrayList<>();
                List<Object> os = Json.list(q, "options");
                if (os != null) {
                    for (Object oo : os) {
                        if (!(oo instanceof Map)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> om = (Map<String, Object>) oo;
                        String label = Json.str(om, "label");
                        if (label == null) continue;
                        opts.add(new Opt(label, Json.str(om, "description")));
                    }
                }
                prompts.add(new Prompt(text, header, multiple, opts));
            }
            if (prompts.isEmpty()) return null;
            return new Req(id, sid, prompts);
        } catch (Throwable t) {
            return null;
        }
    }

    // ----------------------------------------------------------- reply

    /**
     * The reply body for POST .../question/{rid}/reply — one label array
     * per question, in order, so indexes stay aligned with the request:
     * {"answers":[["Phone hosts everything"],["1.21.x"]]}
     */
    public static String answersJson(List<List<String>> picked) {
        StringBuilder b = new StringBuilder("{\"answers\":[");
        if (picked != null) {
            for (int i = 0; i < picked.size(); i++) {
                if (i > 0) b.append(',');
                List<String> labels = picked.get(i);
                b.append('[');
                if (labels != null) {
                    for (int j = 0; j < labels.size(); j++) {
                        if (j > 0) b.append(',');
                        b.append(Json.quote(labels.get(j) == null
                                ? "" : labels.get(j)));
                    }
                }
                b.append(']');
            }
        }
        return b.append("]}").toString();
    }

    /** The server's own echo rule: labels joined with ", " (empty → "Unanswered"). */
    public static String echo(List<String> labels) {
        if (labels == null || labels.isEmpty()) return "Unanswered";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(labels.get(i));
        }
        return b.toString();
    }
}
