package ai.opencode.app;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P51 — the bounded walk over GET /session/{id}/message, the payload
 * the P50-and-before replay paths parsed WHOLE into memory (see
 * JsonStream: that parse was the heap killer). This class never holds
 * the store: it consumes the top-level array one message at a time and
 * keeps only
 *
 *   - a bounded RING of the LAST {@code keep} messages (render window,
 *     count-capped AND byte-capped — a handful of multi-megabyte tool
 *     outputs can no longer smuggle a whole store through the window),
 *   - running stats (message count, how many messages the ring evicted).
 *
 * Every parsed message is handed to {@link Sink#message} exactly once,
 * IN STREAM ORDER, before any ring decision — so accounting and the
 * poison walk see the FULL history at bounded cost. At end of stream
 * the ring survivors reach {@link Sink#renderEnd} oldest-first, which
 * reproduces the old "render the last N" semantics.
 *
 * Strings come through {@link JsonStream}'s caps, so even one absurd
 * part cannot wedge the walk; {@code abort()} lets the caller stop the
 * stream the moment the user switches sessions mid-replay.
 */
public final class StoreWalk {

    private StoreWalk() {}

    /** Render window = the replay render cap it replaces (P43). */
    public static final int KEEP_DEFAULT = 600;

    /** Ring byte budget: the sum of the KEPT messages' string chars —
     *  24M chars ≈ 48 MB UTF-16 worst case, and eviction starts long
     *  before that in every real session. */
    public static final long KEEP_BYTES_DEFAULT = 24_000_000L;

    /** Per-message parts trim: if one message's parts alone measure past
     *  this, the OLDEST parts of that message are dropped from the copy
     *  kept for rendering (accounting already ran on the info). */
    public static final long ITEM_PARTS_BUDGET = 8_000_000L;

    /** What one message's parts roughly cost, in chars. */
    public interface Sink {
        /** Called once per message, in stream order. */
        void message(Map<String, Object> item, int index);
        /** End of stream: the ring survivors, oldest first. */
        void renderEnd(List<Map<String, Object>> items, Stats stats);
        /** Return true to stop the walk cleanly (session switched). */
        boolean abort();
    }

    /** Counters for one walk. */
    public static final class Stats {
        public int messages;        // parsed top-level objects
        public int hidden;          // messages evicted from the ring
        public int partsTrimmed;    // parts dropped by the per-message budget
        public long chars;          // total string chars consumed
    }

    /**
     * Walk the message array. Shape-tolerant like every replay path:
     * items may be {info, parts} wrappers or flat messages, parts may
     * sit on the item or inside info — the sink gets the raw item map
     * and decides.
     */
    public static Stats walk(Reader in, int keep, long keepBytes, Sink sink)
            throws IOException {
        Stats st = new Stats();
        ArrayDeque<Object[]> ring = new ArrayDeque<>();   // {item, chars}
        long ringChars = 0;
        // element-at-a-time — the whole point: the store NEVER lands as
        // one value; only the ring holds history
        JsonStream js = new JsonStream(in);
        if (!js.arrayStart()) throw new IOException("store is not an array");
        int index = 0;
        while (js.arrayHasNext()) {
            if (sink != null && sink.abort()) return st;
            Object o = js.value();                        // ONE message
            Map<String, Object> item = Json.obj(o);
            if (item == null) continue;
            if (sink != null) sink.message(item, index);
            index++;
            st.messages++;
            long chars = sizeChars(item);
            st.chars += chars;              // full consumed size, pre-trim
            // per-message parts trim — the copy kept for rendering must
            // not carry one absurd message whole
            List<Object> parts = partsOf(item);
            if (parts != null) {
                long pchars = sizeChars(parts);
                long removed = 0;
                while (pchars > ITEM_PARTS_BUDGET && !parts.isEmpty()) {
                    Object victim = parts.remove(0);
                    long vc = sizeChars(victim);
                    pchars -= vc;
                    removed += vc;
                    st.partsTrimmed++;
                }
                // P51: the ring budget must measure the TRIMMED copy, not
                // the pre-trim message — over-counting evicted the window
                // too aggressively. sizeChars is additive, so the removed
                // parts' chars are exactly the reduction.
                chars -= removed;
            }
            if (keep <= 0) {
                // P51: some callers (verifyCured) pass keep=0 and ignore
                // the ring; retain nothing rather than the old always-one.
                st.hidden++;
            } else {
                ring.addLast(new Object[]{item, chars});
                ringChars += chars;
                while (ring.size() > 1
                        && (ring.size() > keep || ringChars > keepBytes)) {
                    Object[] eldest = ring.removeFirst();
                    ringChars -= (Long) eldest[1];
                    st.hidden++;
                }
            }
        }
        if (sink != null) {
            List<Map<String, Object>> survivors = new ArrayList<>(ring.size());
            for (Object[] e : ring) survivors.add((Map<String, Object>) e[0]);
            sink.renderEnd(survivors, st);
        }
        return st;
    }

    /** parts on the item or inside info — the same rule the replay
     *  loops always used. */
    public static List<Object> partsOf(Map<String, Object> item) {
        List<Object> parts = Json.list(item, "parts");
        if (parts != null) return parts;
        Map<String, Object> info = Json.map(item, "info");
        return info == null ? null : Json.list(info, "parts");
    }

    /** Total string chars inside an arbitrary decoded value — the cheap
     *  size proxy the ring budget and the parts trim run on. */
    public static long sizeChars(Object o) {
        if (o instanceof String) return ((String) o).length();
        if (o instanceof Map) {
            long n = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                n += String.valueOf(e.getKey()).length() + sizeChars(e.getValue());
            }
            return n;
        }
        if (o instanceof List) {
            long n = 0;
            for (Object v : (List<?>) o) n += sizeChars(v);
            return n;
        }
        return 8;   // number/bool/null: negligible, non-zero
    }
}
