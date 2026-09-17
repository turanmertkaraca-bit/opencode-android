package ai.opencode.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P46 — the stream governor. Fixes the field report "real-time token
 * streaming doesn't work — I wait ~15 s, then get bursts".
 *
 * THE DISEASE. opencode's /event feed sends message.part.updated frames
 * whose payload is the WHOLE part so far — every thinking/text delta
 * re-sends the full cumulative text. On a desktop that is invisible; on
 * a phone the oc-sse thread pays for every byte twice (socket read +
 * full JSON parse + map churn), and the cost grows QUADRATICALLY with
 * the answer length: a 5k-token thinking block alone is ~25k token-
 * payloads of JSON. The thread falls behind real time, frames queue in
 * the socket buffer, and the UI answers in bursts exactly as reported.
 *
 * THE CURE. Because every frame is a full snapshot, dropping
 * INTERMEDIATE frames of the same part loses nothing — the next applied
 * frame carries everything. The governor:
 *
 *   1. pre-screens each raw SSE frame with a plain indexOf scan (orders
 *      of magnitude cheaper than Json parse) and keys text/reasoning
 *      part frames by sessionID|messageID|part-id;
 *   2. applies at most one frame per key per MIN_INTERVAL_MS — the rest
 *      are held as the latest raw payload only;
 *   3. force-flushes on the honest end markers (message.updated,
 *      session.idle, session.error), on any other part key, when the
 *      SSE stream drops, and on a bounded-key overflow — so the final
 *      state of every part ALWAYS lands, at worst ~MIN_INTERVAL late,
 *      hidden by the chat's smoother.
 *
 * Everything hot is pure and JVM-testable: {@link #envelopeType},
 * {@link #partKey}, and the offer/drain state machine (see P46Test —
 * it drives the governor through a synthetic token storm and proves
 * the quadratic work becomes linear while no final state is lost).
 */
public final class PartGovernor {

    /** Min interval between APPLIED frames of one part (ms). 250 ms is
     *  under the eye's fusion threshold for text growth and 8-20x above
     *  the real token rate of thinking models on mobile networks. */
    static final long MIN_INTERVAL_MS = 250;

    /** Max keys held as pending-before-flush. Overflow flushes the
     *  oldest pending frame immediately (never drops final state). */
    static final int MAX_PENDING_KEYS = 64;

    /** Frames larger than this bypass holding (bound memory; the parse
     *  of one huge frame is unavoidable anyway). */
    static final int MAX_HOLD_BYTES = 256 * 1024;

    /** lastApplied cap — part ids are unique per part, so the map grows
     *  by one entry per streamed part; cleared when over the cap, which
     *  merely means the next frame of an old part applies immediately. */
    static final int MAX_LAST_APPLIED = 512;

    /** Event types that mark a boundary — holding past one would be
     *  wrong (idle/error means the stream for this turn IS the end). */
    static final String[] FORCE_TYPES = {
            "message.updated", "session.idle", "session.error"};

    private final Map<String, Long> lastApplied = new HashMap<>();
    /** pending[key] = latest held raw frame (insertion-ordered for the
     *  oldest-first overflow rule). */
    private final LinkedHashMap<String, String> pending = new LinkedHashMap<>();
    private boolean forceAll;

    /** Offer one raw SSE data frame. Returns the frames to parse + ingest
     *  NOW (usually 0 or 1). Held frames come back from {@link #drain}. */
    public synchronized List<String> offer(String raw, long now) {
        List<String> out = new ArrayList<>(1);
        if (raw == null || raw.isEmpty()) return out;

        String type = envelopeType(raw);
        String key = null;
        if ("message.part.updated".equals(type)) key = partKey(raw, type.length());
        if (key == null || raw.length() > MAX_HOLD_BYTES) {
            out.add(raw);                       // unknown shape / huge → pass through
            return out;
        }

        Long last = lastApplied.get(key);
        if (last == null || now - last >= MIN_INTERVAL_MS) {
            // the applied frame supersedes anything held — a stale held
            // snapshot must never flush AFTER its own newer state
            pending.remove(key);
            apply(key, now);
            out.add(raw);
            return out;
        }
        pending.put(key, raw);                  // overwrite: latest snapshot wins
        if (pending.size() > MAX_PENDING_KEYS) flushOldest(out, now);
        return out;
    }

    /** Emit held frames whose interval has elapsed — or ALL of them when
     *  {@code force} is set or a boundary event armed the flush. */
    public synchronized List<String> drain(long now, boolean force) {
        boolean f = force || forceAll;
        forceAll = false;
        List<String> out = new ArrayList<>();
        Iterator<Map.Entry<String, String>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            Long last = lastApplied.get(e.getKey());
            if (f || last == null || now - last >= MIN_INTERVAL_MS) {
                apply(e.getKey(), now);
                out.add(e.getValue());
                it.remove();
            }
        }
        return out;
    }

    /** Boundary signal from the caller after ingesting a frame: the next
     *  {@link #drain} flushes everything held. */
    public synchronized void noteType(String type) {
        if (type == null) return;
        for (String t : FORCE_TYPES) {
            if (t.equals(type)) { forceAll = true; return; }
        }
    }

    /** Frames currently held (test + diagnostics). */
    public synchronized int pendingCount() { return pending.size(); }

    /** Test/diag: how many frames were applied so far. */
    public synchronized int appliedCount() { return applied; }

    private int applied;

    /** Caller's timestamp wins everywhere — production passes
     *  System.currentTimeMillis, tests pass synthetic clocks; either way
     *  the interval math is internally consistent. */
    private void apply(String key, long now) {
        if (lastApplied.size() > MAX_LAST_APPLIED) lastApplied.clear();
        lastApplied.put(key, now);
        applied++;
    }

    /** Overflow rule: flush the OLDEST held frame immediately so the
     *  pending set stays bounded without ever silently dropping the
     *  final state of a part (the newest snapshot stays held). */
    private void flushOldest(List<String> out, long now) {
        Iterator<Map.Entry<String, String>> it = pending.entrySet().iterator();
        if (!it.hasNext()) return;
        Map.Entry<String, String> oldest = it.next();
        it.remove();
        apply(oldest.getKey(), now);
        out.add(oldest.getValue());
    }

    // ------------------------------------------------------------ scan

    /** Cheap envelope-type extraction: the FIRST "type":"…" in the frame.
     *  Returns null when absent (frame is then passed through untouched). */
    static String envelopeType(String raw) {
        if (raw == null) return null;
        int i = raw.indexOf("\"type\":\"");
        if (i < 0) return null;
        int s = i + 8;
        int e = raw.indexOf('"', s);
        return e < 0 ? null : raw.substring(s, e);
    }

    /**
     * Cheap part key for message.part.updated frames: the FIRST
     * sessionID, messageID and prt_ id occurrences after the envelope
     * type. Returns null when the frame does not look like a throttle-
     * candidate (missing ids, non-text/reasoning part) — the caller then
     * passes it through unthrottled, so a scan miss can never lose data.
     *
     * @param typeLen length of the envelope type token — scanning starts
     *                after it, so "type" here means the PART's type field
     */
    static String partKey(String raw, int typeLen) {
        String sid = scanStr(raw, typeLen, "\"sessionID\":\"");
        if (sid == null) return null;
        String mid = scanStr(raw, typeLen, "\"messageID\":\"");
        if (mid == null) return null;
        // only text/reasoning flood; prt_ prefix pins the part id field
        String pt = scanStr(raw, typeLen, "\"type\":\"");
        if (!"text".equals(pt) && !"reasoning".equals(pt)) return null;
        String pid = scanStr(raw, typeLen, "\"id\":\"prt_");
        if (pid == null) return sid + "|" + mid + "|-";
        return sid + "|" + mid + "|prt_" + pid;
    }

    /** indexOf + bounded value scan for a JSON string field; no map, no
     *  allocs beyond the returned substring. Null when absent. */
    private static String scanStr(String raw, int from, String token) {
        int i = raw.indexOf(token, from);
        if (i < 0) return null;
        int s = i + token.length();
        int e = s;
        while (e < raw.length()) {
            char c = raw.charAt(e);
            if (c == '"') break;
            if (c == '\\') return null;         // escaped content: not worth
            e++;                                // hand-rolling — pass through
        }
        if (e >= raw.length() || e == s) return null;
        return raw.substring(s, e);
    }
}
