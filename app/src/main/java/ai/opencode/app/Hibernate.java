package ai.opencode.app;

/**
 * P31 — auto-hibernate. When the app sits in the BACKGROUND with no run
 * active and no permission waiting, the sandbox stops itself after a
 * quiet stretch: RAM and battery come back to the phone. Reopening the
 * app boots the sandbox and drops the user straight into the chat they
 * left (sessions live on disk server-side — "appears to be still
 * working" without paying for it).
 *
 * NEVER fires while a run is active — runs outlive everything else, and
 * a hibernation must never cost the user a turn. Pure rule, JVM-pinned.
 */
public final class Hibernate {

    private Hibernate() {}

    /** Default quiet stretch before the sandbox stops itself. */
    public static final int DEFAULT_MINUTES = 10;
    /** The choices Settings offers. */
    public static final int[] MINUTE_CHOICES = {5, 10, 15, 30};

    /**
     * True when the service should hibernate NOW.
     *
     * @param bgSince      when the last activity paused (0 = app foreground)
     * @param now          current time
     * @param minMs        the quiet threshold in ms
     * @param anyRun       any agent run active (ANY session)
     * @param permsPending a tool approval is waiting on the user
     */
    public static boolean due(long bgSince, long now, long minMs,
                              boolean anyRun, boolean permsPending) {
        if (bgSince <= 0) return false;        // user is IN the app — never
        if (anyRun || permsPending) return false;   // work in flight — never
        if (minMs <= 0) return false;          // disabled / nonsensical
        return now - bgSince >= minMs;
    }

    /** minutes → ms (guard: ≤0 → 0 = off). */
    public static long minutesToMs(int minutes) {
        return minutes <= 0 ? 0 : minutes * 60_000L;
    }
}
