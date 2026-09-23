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
 * THE WIRE (corrected in P47 by a timestamped probe against the real
 * server — mock streaming provider, opencode v1.18.25): /event carries
 * BOTH shapes — message.part.delta frames holding each tiny increment
 * in real time, AND message.part.updated frames holding the WHOLE
 * cumulative part text, but only at part BOUNDARIES (created-empty,
 * phase ends, metadata churn, final state). The P46 diagnosis ("every
 * delta re-sends the full text") was wrong about the delta channel but
 * right that unbounded full-snapshot parses can flood the oc-sse thread
 * — thinking-model runs churn part.updated frames with metadata, and
 * every one of them re-parses the full text accumulated so far.
 *
 * THE CURE. Snapshots are full state, so dropping INTERMEDIATE frames
 * of the same part loses nothing — the next applied frame carries
 * everything. The governor:
 *
 *   1. pre-screens each raw SSE frame with a plain indexOf scan (orders
 *      of magnitude cheaper than Json parse) and keys TEXT/REASONING
 *      SNAPSHOT frames by sessionID|messageID|part-id;
 *   2. applies at most one SNAPSHOT frame per key per MIN_INTERVAL_MS —
 *      the rest are held as the latest raw payload only. Delta frames
 *      never match the scan and pass through instantly (they ARE the
 *      live token — throttling them would throttle the stream itself;
 *      P47's RunHub.applyDelta consumes them directly);
 *   3. force-flushes on the honest end markers (message.updated,
 *      session.idle, session.error), on any other part key, when the
 *      SSE stream drops, and on a bounded-key overflow — so the final
 *      state of every part ALWAYS lands, at worst ~MIN_INTERVAL late,
 *      hidden by the chat's smoother.
 *
 * Everything hot is pure and JVM-testable: {@link #envelopeType},
 * {@link #partKey}, and the offer/drain state machine (see P46Test —
 * it drives the governor through a synthetic token storm and proves
 * the quadratic work becomes linear while no final state is lost; see
 * P47Test — delta frames pass through untouched, zero latency added).
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
        if ("message.part.updated".equals(type)) {
            // P51: scan MUST start just past the envelope type token, not
            // type.length() chars in (a longer frame with keys before
            // "type" could re-read the envelope's own type as the part's).
            int from = envelopeTypeEnd(raw);
            key = partKey(raw, from < 0 ? type.length() : from);
        }
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

    /** Index just past the FIRST "type":"…" token (its closing quote + 1),
     *  or -1 when absent — the correct scan anchor for {@link #partKey}. */
    private static int envelopeTypeEnd(String raw) {
        if (raw == null) return -1;
        int i = raw.indexOf("\"type\":\"");
        if (i < 0) return -1;
        int s = i + 8;
        int e = raw.indexOf('"', s);
        return e < 0 ? -1 : e + 1;
    }

    /**
     * Cheap part key for message.part.updated frames: the FIRST
     * sessionID, messageID and prt_ id occurrences after the envelope
     * type. Returns null when the frame does not look like a throttle-
     * candidate (missing ids, non-text/reasoning part) — the caller then
     * passes it through unthrottled, so a scan miss can never lose data.
     *
     * @param from   index just past the envelope type token (see
     *               {@link #envelopeTypeEnd}) — scanning starts there, so
     *               the next "type" found is the PART's type field
     */
    static String partKey(String raw, int from) {
        String sid = scanStr(raw, from, "\"sessionID\":\"");
        if (sid == null) return null;
        String mid = scanStr(raw, from, "\"messageID\":\"");
        if (mid == null) return null;
        // only text/reasoning flood; prt_ prefix pins the part id field
        String pt = scanStr(raw, from, "\"type\":\"");
        if (!"text".equals(pt) && !"reasoning".equals(pt)) return null;
        String pid = scanStr(raw, from, "\"id\":\"prt_");
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
