package ai.opencode.app;

import java.util.List;

/**
 * P39 — the context-integrity guard. The field's worst report, made
 * observable and non-fatal.
 *
 * THE SIGNATURE. The September field screenshot settled it: a 37-turn
 * session whose every turn cost a flat ~44k tokens ($0.0006 each, Σ
 * $0.0226) while the model answered "This is a fresh chat — I have no
 * prior conversation history". When conversation history reaches the
 * model, every turn's input GROWS by at least the new content; a run of
 * turns whose totals stay level means the turns are being answered
 * WITHOUT the thread — the model is re-reading only the system prompt.
 * The live rig (real opencode 1.18.25 + a logging mock provider) proved
 * the server logic itself is correct, so this is a device-side failure
 * the app cannot prevent — but it CAN see it, and it CAN work around it
 * by re-feeding the thread with each send until the totals grow again.
 *
 * DETECTOR (pure): over the session's assistant messages in order
 * (per-message TOTAL tokens, the same numbers the footers show):
 *   • a compaction summary resets the run — compacting legitimately
 *     shrinks the payload, that is not amnesia;
 *   • five consecutive summary-free assistant turns whose totals grew by
 *     1% or less from the first to the last (base at least 10k tokens —
 *     small payloads are all noise) → confirmed;
 *   • a single collapse steeper than 35% between substantial turns with
 *     no summary between → confirmed immediately (history vanished).
 *   • messages without usage yet (tok<=0) and error-partial turns
 *     (tiny totals) are skipped, never judged.
 *
 * REPAIR (pure builder): while confirmed, each send carries a compacted
 * digest of the recent thread inside a system-reminder block — the same
 * ride-along path the terse/env/render notes use. The block stops riding
 * by itself the moment the totals grow again (healthy history → the
 * detector clears), so a repaired session costs nothing extra.
 */
public final class AmnesiaGuard {

    private AmnesiaGuard() {}

    /** One assistant message's detector input. */
    public static final class Obs {
        public final long tok;          // per-message total tokens
        public final boolean summary;   // compaction summary message
        public Obs(long tok, boolean summary) {
            this.tok = tok;
            this.summary = summary;
        }
    }

    /** Window length: this many consecutive summary-free assistant turns
     *  with a level payload count as amnesia. Five turns of true history
     *  always add at least the five exchanges' worth of tokens. */
    public static final int WINDOW = 5;

    /** Max total-token growth across the window that still reads as flat. */
    static final double FLAT_GROWTH = 0.01;

    /** Windows below this base are never judged — a small payload has no
     *  history worth losing and the measurement is all noise. Also the
     *  floor for both sides of a collapse judgment. */
    public static final long MIN_BASE = 10_000L;

    /** A single-turn drop steeper than this (no summary between, both
     *  turns substantial) is a collapse — confirmed immediately. */
    static final double COLLAPSE = 0.35;

    /** True when the sequence reads as "turns answered without history".
     *  Pure; the JVM suite pins every branch. The collapse rule needs
     *  only two substantial turns; the flat-window rule needs WINDOW. */
    public static boolean confirmed(List<Obs> seq) {
        if (seq == null) return false;
        long[] ring = new long[WINDOW];
        int n = 0;                       // qualifying msgs in this summary-free run
        long prev = 0;
        for (Obs o : seq) {
            if (o == null || o.tok <= 0) continue;      // no usage yet — skip
            if (o.summary) { n = 0; prev = o.tok; continue; }
            if (prev >= MIN_BASE && o.tok >= MIN_BASE) {
                long drop = prev - o.tok;
                if (drop > (long) (prev * COLLAPSE)) return true;
            }
            ring[n % WINDOW] = o.tok;
            n++;
            if (n >= WINDOW) {
                long first = ring[(n - WINDOW) % WINDOW];
                long last = ring[(n - 1) % WINDOW];
                if (first >= MIN_BASE
                        && (last - first) <= (long) (first * FLAT_GROWTH))
                    return true;
            }
            prev = o.tok;
        }
        return false;
    }

    // ---------------------------------------------------- repair builder

    /** The recap block's opening signature — NoteStrip strips the whole
     *  block from the user's bubble by exactly this prefix (pinned
     *  cross-class in P39Test, same pattern as the three P38 notes). */
    public static final String SIGNATURE = "Context repair";
    public static final int RECAP_MAX_CHARS = 20_000;
    /** Per-turn cap inside the digest — one giant paste must not eat the
     *  whole budget; recent turns win because the digest is built from
     *  the tail. */
    static final int TURN_MAX_CHARS = 1_200;

    /** The system-reminder block carried while the guard is confirmed.
     *  {@code digest} = the ordered transcript lines from {@link
     * #digestLine}; capped to {@link #RECAP_MAX_CHARS} from the TAIL —
     *  the model needs the recent thread, not the oldest turns. Pure. */
    public static String recapBlock(String digest) {
        String d = digest == null ? "" : digest.trim();
        if (d.length() > RECAP_MAX_CHARS) {
            d = "…\n" + d.substring(d.length() - RECAP_MAX_CHARS);
        }
        return "<system-reminder>\n"
                + SIGNATURE + ": the previous turns of this conversation did "
                + "not reach you — your context held only the current message. "
                + "The transcript below is this session so far; treat it as "
                + "the prior conversation and continue seamlessly.\n\n"
                + d + "\n</system-reminder>";
    }

    /** One transcript row → one digest line. {@code kind} is "user" or
     *  "assistant"; the text should already be display-form (notes
     *  stripped, tail trimmed by the caller). Whitespace is flattened so
     *  one row stays one line and the per-turn cap stays honest. Pure. */
    public static String digestLine(String kind, String text) {
        if (text == null) return null;
        String t = text.replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (t.isEmpty()) return null;
        if (t.length() > TURN_MAX_CHARS)
            t = t.substring(0, TURN_MAX_CHARS - 1) + "…";
        return "[" + kind + "] " + t;
    }
}
