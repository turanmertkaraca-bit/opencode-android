package ai.opencode.app;

/**
 * P43 — cache beats, the in-app take on the cachebeat idea (a community
 * Claude Code skill the field pointed at): a session that sits IDLE past
 * the provider's prompt-cache TTL loses its cache, and the next real
 * message re-reads the entire conversation at full input price — roughly
 * 10x what a warm cache read bills. One tiny automated "beat" message
 * before the TTL dies re-reads the prefix at the cached rate and restarts
 * the TTL clock; going cold ONCE costs about what TEN beats cost.
 *
 * House rules (this is the user's money, so the rails come first):
 *   • only beats when there is a cache worth keeping (context below
 *     MIN_CONTEXT_TOK re-reads for pennies anyway — never beat);
 *   • never while a run is streaming (beats queue behind real work and
 *     would only add latency);
 *   • one beat per idle window — the beat's own completion is activity,
 *     so the next beat needs another full quiet stretch;
 *   • a hard cap of beats per idle stretch (default 6) and the counter
 *     resets the moment REAL activity (any event, any send) shows up;
 *   • a failed beat backs off instead of hammering a sick server;
 *   • a Settings switch owns the whole feature.
 * Pure decision core here; RunHub owns the clocks and the send.
 */
public final class CacheBeat {

    private CacheBeat() {}

    /** Fire after this much silence (Anthropic-style 5-minute cache TTLs
     *  leave a safe margin; longer-TTL providers simply beat less often
     *  because real activity keeps resetting the clock). */
    public static final long IDLE_MS = 4 * 60_000L;

    /** Beats per idle stretch, max. After this the stretch stays cold —
     *  an abandoned chat must not drip-bill for hours. */
    public static final int MAX_BEATS = 6;

    /** Below this context a cold re-read is cheap enough to ignore. */
    public static final long MIN_CONTEXT_TOK = 8_000L;

    /** Backoff after a failed beat (doubled per consecutive failure). */
    public static final long FAIL_BACKOFF_MS = 2 * 60_000L;

    /** The wire text. A system-reminder so the model never mistakes it
     *  for the user speaking (NoteStrip knows the signature and keeps
     *  the bubble clean); the instruction pins the reply to two words so
     *  a beat costs output pennies and never runs tools. */
    public static String beatBlock() {
        return "<system-reminder>\n"
                + "Cache beat: this is an automatic keepalive ping, not "
                + "the user. Do not run tools, do not continue any task, "
                + "do not ask questions. Reply with exactly: still here\n"
                + "</system-reminder>";
    }

    /** The one-line user-bubble label a beat renders as. */
    public static String rowLabel() { return "♡ cache beat"; }

    /**
     * The pure fire decision. {@code now}/{@code lastActivity} are ms,
     * {@code beats} is how many beats already fired in this stretch,
     * {@code failStreak} consecutive failed beats (0 = healthy),
     * {@code ctxTok} the session's context depth, {@code busy} TRUE when
     * a run is streaming, {@code enabled} the Settings switch.
     */
    public static boolean shouldFire(long now, long lastActivity, int beats,
                                     long failStreak, long ctxTok, boolean busy,
                                     boolean enabled) {
        if (!enabled || busy) return false;
        if (beats >= MAX_BEATS) return false;
        if (ctxTok > 0 && ctxTok < MIN_CONTEXT_TOK) return false;
        long quiet = now - lastActivity;
        if (quiet < IDLE_MS) return false;
        if (failStreak > 0) {
            long backoff = FAIL_BACKOFF_MS << Math.min(failStreak, 4);
            if (quiet - IDLE_MS < backoff) return false;
        }
        return true;
    }

    /** The Σ-popover line — the user must be able to SEE the feature is
     *  armed (and what it costs when it fires). Empty when disabled. */
    public static String popoverLine(boolean enabled, long cachedTok) {
        if (!enabled) return "";
        StringBuilder b = new StringBuilder();
        b.append("Cache beats: armed — after 4 quiet minutes a tiny ")
         .append("automatic ping keeps the provider's prompt cache warm, ")
         .append("so the next real message reads this chat at the ")
         .append("discounted rate instead of re-billing it cold (max ")
         .append(MAX_BEATS).append(" per quiet stretch).\n");
        if (cachedTok > 0) {
            b.append("Cache health this session: ")
             .append(Resilience.fmtTok(cachedTok))
             .append(" input tokens were served from the provider's ")
             .append("cache so far.\n");
        }
        return b.toString();
    }
}
