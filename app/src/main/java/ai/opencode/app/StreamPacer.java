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
 *
 * P48 — two PROFILES, because the field came back with the other
 * complaint: the glide was real but VIOLENT ("tokens come and go so
 * fast it glitches the ui, it goes up and down"). The lag ceiling
 * allows unbounded drain rates (a 4 kB burst ⇒ ~1800 chars/s), and
 * fast providers arrive faster still. So the pacer now carries a hard
 * MAX_RATE per profile:
 *   - ANSWER  (forAnswer): the P43 behavior with a 900 chars/s cap —
 *     still clearly faster than reading, never a strobe.
 *   - THINKING (forThinking): the collapsed thought card is a window,
 *     not a transcript — it reveals at a calm ≤170 chars/s (faster
 *     than reading speed, slow enough to follow), with a long lag
 *     ceiling instead of the aggressive 2.2 s drain. The backlog can
 *     grow freely while the model thinks; when the answer row arrives
 *     the ticker snaps the thought row settled in one paint — the
 *     thinking text never races the answer.
 * The no-arg constructor keeps the exact legacy (P43) behavior — the
 * P43 pins run against it unchanged.
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

    /** P48: hard ceiling for the ANSWER profile. The lag ceiling alone
     *  allows arbitrary drain rates; this keeps the reveal human. 0 =
     *  uncapped (the legacy constructor). */
    public static final double ANSWER_MAX_RATE = 900.0;

    /** P48: the THINKING profile — a calm crawl, not a strobe. At 24 ms
     *  ticks that is ≤5 chars/tick; faster than reading speed (~20
     *  chars/s), so a long thought streams like a live feed without
     *  shaking the list. */
    public static final double THINK_MAX_RATE = 170.0;
    public static final double THINK_MIN_RATE = 60.0;
    /** Thinking has no deadline — the answer's arrival settles the row.
     *  The ceiling only exists to bound the required-drain term. */
    public static final long THINK_LAG_MS = 30_000;

    /** Arrival-rate EWMA weight for the fresh sample. */
    static final double ALPHA = 0.35;

    private final double minRate;
    private final double catchup;
    private final long maxLagMs;
    private final double maxRate;

    /** P48: which profile this pacer serves (the ChatActivity cache
     *  re-creates a pacer if a row's kind flips). */
    public final boolean thinkingRow;

    /** Legacy P43 profile — uncapped drain, the exact pinned behavior. */
    public StreamPacer() {
        this(MIN_RATE, CATCHUP, MAX_LAG_MS, 0, false);
    }

    private StreamPacer(double minRate, double catchup, long maxLagMs,
                        double maxRate, boolean thinkingRow) {
        this.minRate = minRate;
        this.catchup = catchup;
        this.maxLagMs = maxLagMs;
        this.maxRate = maxRate;
        this.thinkingRow = thinkingRow;
    }

    /** P48: the reply-glide profile — P43 pacing with a 900 chars/s
     *  hard cap so a fast provider can never strobe the screen. */
    public static StreamPacer forAnswer() {
        return new StreamPacer(MIN_RATE, CATCHUP, MAX_LAG_MS,
                ANSWER_MAX_RATE, false);
    }

    /** P48: the thought-window profile — ≤170 chars/s, gentle floor,
     *  no aggressive drain. The answer's arrival snaps the row. */
    public static StreamPacer forThinking() {
        return new StreamPacer(THINK_MIN_RATE, 1.0, THINK_LAG_MS,
                THINK_MAX_RATE, true);
    }

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
        // P48: then capped by the profile's MAX_RATE — the drain term
        // may DEMAND speed, but the profile decides how fast is human.
        double required = allowed * 1000.0 / maxLagMs;
        double eff = Math.max(minRate, Math.max(rate * catchup, required));
        if (maxRate > 0 && eff > maxRate) eff = maxRate;

        long step = (long) Math.ceil(eff * dt / 1000.0);
        if (step < 1) step = 1;
        shown += Math.min(step, allowed);
        if (shown > target) shown = target;
        return shown;
    }
}
