package ai.opencode.app;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * P18 "unstoppable" — pure, JVM-testable helpers behind the three field
 * bugs: (1) the sandbox dying without recovery, (2) raw java.net exception
 * text landing in the chat, (3) the top token/cost pill reading like a
 * mystery meter. No Android imports on purpose — P18Test runs these on the
 * host JVM.
 */
public final class Resilience {

    private Resilience() {}

    // ------------------------------------------------------- send timeout

    /** True when the throwable (or any cause in its chain) is a socket
     *  READ timeout. The opencode POST carries a whole agent run, so on a
     *  long thinking/tool loop the read can outlive any finite timeout
     *  WHILE the run is still alive server-side — this must NOT be treated
     *  as a failed send (the SSE event feed keeps rendering the run). */
    public static boolean isSendTimeout(Throwable t) {
        Throwable c = t;
        int hop = 0;
        while (c != null && hop++ < 8) {
            if (c instanceof SocketTimeoutException) return true;
            c = c.getCause();
        }
        return false;
    }

    /** True when the failure means "the sandbox socket is gone" — server
     *  died / restarted mid-POST. Distinct from a timeout: the run did NOT
     *  survive (broken pipe), so the honest answer is a resend hint. */
    public static boolean isBrokenPipe(Throwable t) {
        Throwable c = t;
        int hop = 0;
        while (c != null && hop++ < 8) {
            String n = c.getClass().getSimpleName();
            String m = String.valueOf(c.getMessage()).toLowerCase();
            if (c instanceof ConnectException) return true;
            if (n.contains("EOF") || n.contains("SSLException")) return true;
            if (m.contains("broken pipe") || m.contains("connection reset")
                    || m.contains("eofexception") || m.contains("socket closed")) return true;
            c = c.getCause();
        }
        return false;
    }

    /** Human one-liner for a network-shaped send failure. NEVER leaks
     *  "java.net." / class names into the chat (P18 field bug #2 — the raw
     *  "send failed: java.net.SocketTimeoutException: timeout" banner). */
    public static String prettyNetError(Throwable t) {
        if (t == null) return "connection hiccup";
        if (isSendTimeout(t))
            return "the sandbox went quiet mid-run (long thinking)";
        if (isBrokenPipe(t))
            return "the sandbox connection dropped (server restarted?)";
        String m = String.valueOf(t.getMessage());
        if (m.contains("refused")) return "the sandbox isn't listening (restarting?)";
        if (m.contains("ENOSPC") || m.toLowerCase().contains("no space"))
            return "the device is out of storage";
        return "connection hiccup";
    }

    // ------------------------------------------------------- crash streak

    /** Chat-side quiet threshold: how long the SSE feed may stay silent
     *  while "busy" before the chat gives up on the run. P18 used 3.5 s —
     *  that murdered the live-edit shower (and the stop button) every time
     *  the agent ran a quiet tool for a few seconds, because a running
     *  bash/LLM tool emits NO part events while it works. A run's real end
     *  is session.idle / session.error on the feed; the quiet timer is now
     *  only a last resort for a feed that died without either (10 min,
     *  matching the POST read budget). */
    public static long quietEndMs() { return 600_000L; }

    /** P19: does a /proc/<pid>/cmdline payload (NUL-separated argv) belong
     *  to OUR opencode binary? Matches only when argv[0] equals the exact
     *  path — never a prefix walk, never another app's file. Pure. */
    public static boolean isOcCmdline(String cmdline, String binPath) {
        if (cmdline == null || binPath == null) return false;
        int cut = cmdline.indexOf('\0');
        String argv0 = (cut >= 0) ? cmdline.substring(0, cut) : cmdline;
        return argv0.equals(binPath);
    }

    /** P19: choose a bindable port for the server spawn. The child does
     *  NOT support a true ephemeral port (—port 0 just becomes its default
     *  4096 — verified against v1.18.25 on the rig), so the APP asks the
     *  kernel instead: preferred when free, otherwise a kernel-assigned
     *  free port. This is what turns the field crash (wedged orphan holds
     *  4096 → every respawn dies EADDRINUSE → cold boot) into a non-event:
     *  the supervisor simply serves on the next free port and the banner
     *  parse + health gate confirm the child actually owns it. */
    public static int pickFreePort(int preferred) {
        try (java.net.ServerSocket s = new java.net.ServerSocket(preferred)) {
            s.setReuseAddress(true);
            return preferred;               // well-known port still free
        } catch (Exception taken) {
            try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
                int p = s.getLocalPort();
                return p > 0 ? p : preferred;
            } catch (Exception e) {
                return preferred;           // last resort: try as-is
            }
        }
    }

    /** Number of server deaths inside the lookback window (ms). Used by
     *  the auto-restart supervisor: 3+ deaths in 10 min = a crash LOOP,
     *  stop burning battery on respawns and surface it to the user. */
    public static int deathsInWindow(long[] deathTimesMs, long nowMs, long windowMs) {
        if (deathTimesMs == null) return 0;
        int n = 0;
        for (long t : deathTimesMs) {
            if (t > 0 && t <= nowMs && nowMs - t <= windowMs) n++;
        }
        return n;
    }

    /** Backoff before the Nth auto-restart (0-indexed): 1.5s, 4s, 8s.
     *  Immediate first respawn keeps "sandbox blipped" invisible; growing
     *  gaps stop a crash loop from spinning the CPU. */
    public static long restartBackoffMs(int attempt) {
        if (attempt <= 0) return 1_500;
        if (attempt == 1) return 4_000;
        return 8_000;
    }

    /** One diagnostics line for sandbox-diag.log (fixed field order so the
     *  file is greppable): ts · event · detail · memAvailKb. */
    public static String diagLine(long tsMs, String event, String detail, long memAvailKb) {
        StringBuilder b = new StringBuilder();
        b.append(tsMs).append(" · ").append(event == null ? "-" : event);
        b.append(" · ").append(detail == null ? "-" : detail.replace('\n', ' '));
        b.append(" · memAvail=").append(memAvailKb).append("kB");
        return b.toString();
    }

    /** MemAvailable (kB) out of /proc/meminfo, -1 when absent. */
    public static long parseMemAvailableKb(String meminfo) {
        if (meminfo == null) return -1;
        for (String line : meminfo.split("\n")) {
            if (line.startsWith("MemAvailable:")) {
                String digits = line.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    try { return Long.parseLong(digits); } catch (Exception ignored) {}
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------- formats

    /** ⇅ token footer formatting, shared by rows and the pill. */
    public static String fmtTok(long tok) {
        return tok >= 1_000_000
                ? String.format(java.util.Locale.US, "%.1fM", tok / 1_000_000.0)
                : tok >= 100_000
                ? String.format(java.util.Locale.US, "%.0fk", tok / 1000.0)
                : tok >= 1000
                ? String.format(java.util.Locale.US, "%.1fk", tok / 1000.0)
                : String.valueOf(tok);
    }

    public static String fmtCost(double cost) {
        return String.format(java.util.Locale.US, "$%.4f", cost);
    }

    /** Context-health verdict for the spend popover. Thresholds tuned to
     *  the P18 field report (86k/turn context → runaway cumulative cost):
     *  the model re-reads the WHOLE conversation every turn, so a heavy
     *  context multiplies every future turn's cost until a fresh chat. */
    public static String contextVerdict(long lastTurnTok) {
        if (lastTurnTok <= 0) return "";
        if (lastTurnTok >= 100_000)
            return "very heavy — every turn re-reads all of this. A fresh chat "
                    + "will make replies faster AND cheaper right now";
        if (lastTurnTok >= 50_000)
            return "heavy — replies now re-read ~" + fmtTok(lastTurnTok)
                    + " tokens each turn. Fresh chat when this task is done";
        if (lastTurnTok >= 20_000)
            return "moderate — the conversation re-reads itself each turn";
        return "light — nothing to worry about";
    }
}
