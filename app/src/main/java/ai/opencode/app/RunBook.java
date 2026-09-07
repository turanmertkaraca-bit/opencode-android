package ai.opencode.app;

import java.util.List;
import java.util.Map;

/**
 * P31 — the run book. Pure decision rules for PARALLEL RUNS: more than
 * one opencode session can stream at once (run a script in one chat and
 * keep working in another). The mutable state (which sessions are
 * running, when each was last sent to / idled) lives in RunHub; every
 * DECISION about it lives here so the JVM suite can pin the rules.
 */
public final class RunBook {

    private RunBook() {}

    /** Hard parallel cap — the sandbox is one process; 3 concurrent
     *  agent turns is the honest ceiling before answers degrade. */
    public static final int MAX_PARALLEL = 3;

    /** A part arriving for a session that was sent to within this window
     *  and never cleanly idled re-arms its run (the P19 self-heal,
     *  generalized to N sessions). Generous on purpose: a long tool loop
     *  on a slow model can legitimately outlive 30 minutes. */
    public static final long REARM_WINDOW_MS = 2L * 60 * 60 * 1000;

    public static final int CLAIM_OK = 0, CLAIM_BUSY = 1, CLAIM_CAP = 2;

    /**
     * May a send claim a run for {@code sid}?
     *   CLAIM_OK    → claim it (caller adds the session to the running set)
     *   CLAIM_BUSY  → this exact session already has a live run
     *   CLAIM_CAP   → a different session is at the parallel cap
     */
    public static int claim(List<String> running, String sid, int cap) {
        if (sid == null || sid.isEmpty()) return CLAIM_BUSY;
        if (running.contains(sid)) return CLAIM_BUSY;
        if (running.size() >= cap) return CLAIM_CAP;
        return CLAIM_OK;
    }

    /**
     * The P19 self-heal, generalized: a part arrived for {@code sid}
     * which we do NOT believe is running. Re-arm only if we actually SENT
     * to it recently (within the window) and it has not cleanly idled
     * SINCE that send — idle is the truth that a run ended; without the
     * idle guard, a stale replayed part after a finished run would park a
     * phantom "running" state forever.
     */
    public static boolean shouldRearm(Map<String, Long> sentAt,
                                      Map<String, Long> idledAt,
                                      String sid, long now, long windowMs) {
        if (sid == null || sid.isEmpty()) return false;
        Long sent = sentAt.get(sid);
        if (sent == null || sent <= 0) return false;   // never sent here
        if (now - sent > windowMs) return false;       // too old to trust
        Long idle = idledAt.get(sid);
        return idle == null || idle < sent;            // no clean idle since
    }

    /** The stop button's honest scope note when other chats still run. */
    public static String stopScopeNote(int runningCount, boolean displayedRunning) {
        if (runningCount <= 1) return "";
        int others = displayedRunning ? runningCount - 1 : runningCount;
        if (others <= 0) return "";
        return " — " + others + " other chat" + (others == 1 ? "" : "s")
                + " still running (Sessions → long-press → Stop)";
    }
}
