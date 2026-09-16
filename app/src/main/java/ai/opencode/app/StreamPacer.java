package ai.opencode.app;

/**
 * P43 — the burst smoother. The field report: "the token stream doesn't
 * look realtime … it all just flashes so fast — I think it thinks it's
 * getting realtime token streaming but it's just a burst".
 *
 * The old reveal rule (P9) was step = 3 + (len-shown)/8 per 24 ms tick.
 * That is a CATCH-UP rule, not a pacing rule: the providers (and the
 * background-optimized SSE reader) deliver text in multi-kilobyte
 * bursts, so the old rule emptied each burst in ~0.5 s of rapid-fire
 * repaints and then sat frozen until the next burst — flash, stall,
 * flash. The feed is bursty; the display must not be.
 *
 * THE FIX — pace by the ARRIVAL RATE, not by the backlog: the pacer
 * estimates how fast bytes are arriving (an EWMA over the deltas it
 * sees) and reveals at a small multiple of that rate, so the text
 * glides continuously for as long as the model keeps producing. Two
 * safety rails keep it honest:
 *   - a floor rate (short replies must still land promptly), and
 *   - a hard lag ceiling: whatever the rate, the backlog may never
 *     represent more than MAX_LAG_MS of future reveal time, so every
 *     burst finishes revealing within ~2 s of its last byte even if
 *     the stream then goes silent.
 * Pure: feed (now, targetLen), read back the new shown count. No
 * Android imports — the JVM suite drives the whole contract.
 */
public final class StreamPacer {

    /** Reveal slightly faster than arrival — the display stays just
     *  behind the model without ever feeling laggy. */
    public static final double CATCHUP = 1.2;

    /** Chars/second floor. At 24 ms ticks this is ~6 chars/tick: short
     *  replies still land inside a breath even when arrival stalls. */
    public static final double MIN_RATE = 260.0;

    /** The backlog may never exceed this much future reveal time. A
     *  20 kB burst therefore finishes revealing ~2 s after it lands —
     *  a glide, not a flash. */
    public static final long MAX_LAG_MS = 2200;

    /** Arrival-rate EWMA weight for the fresh sample. */
    static final double ALPHA = 0.35;

    private long lastNow = -1;
    private long lastTarget = -1;
    private long shown = 0;
    private double rate = 0;            // chars per second, EWMA
    private boolean started;

    /** Reset for a new part / a rebuilt row. */
    public void reset() {
        lastNow = -1;
        lastTarget = -1;
        shown = 0;
        rate = 0;
        started = false;
    }

    public long shown() { return shown; }

    /**
     * Advance to {@code now}. {@code target} is the full text length
     * currently available (it only ever grows while a part streams).
     * Returns the new shown count (never negative, never beyond
     * target). Pure w.r.t. the inputs — time comes from the caller.
     */
    public long tick(long now, long target) {
        if (target < 0) target = 0;
        if (!started) {
            // First sight of this part: paint an opening bite (enough to
            // see what the model is up to) and glide from there.
            started = true;
            lastNow = now;
            lastTarget = target;
            shown = Math.min(target, 40);
            return shown;
        }
        long dt = now - lastNow;
        if (dt <= 0) return Math.min(shown, target);   // same tick: no time passed
        if (dt > 1000) dt = 1000;                      // app was asleep: don't blow up
        lastNow = now;

        long grew = target - lastTarget;
        if (grew < 0) grew = 0;                        // rebuilds can shrink; ignore
        lastTarget = target;

        // Arrival estimate (chars/sec). Bytes arriving NOW are the truth
        // about the model's pace; the EWMA just smooths the jaggedness.
        double inst = grew * 1000.0 / dt;
        rate = rate <= 0 ? inst : (1 - ALPHA) * rate + ALPHA * inst;

        long allowed = target - shown;
        if (allowed <= 0) { shown = target; return shown; }

        // The reveal rate is whichever is largest of:
        //   • the floor (short replies land promptly),
        //   • a small multiple of the measured arrival rate (glide just
        //     behind the model), and
        //   • the drain rate that empties the backlog inside the lag
        //     ceiling (a huge burst becomes a fast-but-readable glide —
        //     4 kB over ~2 s, never a one-frame flash).
        double required = allowed * 1000.0 / MAX_LAG_MS;
        double eff = Math.max(MIN_RATE, Math.max(rate * CATCHUP, required));

        long step = (long) Math.ceil(eff * dt / 1000.0);
        if (step < 1) step = 1;
        shown += Math.min(step, allowed);
        if (shown > target) shown = target;
        return shown;
    }
}
