package ai.opencode.app;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

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

    /** P25: the token pill now reads as CONTEXT DEPTH, not a cumulative
     *  sum — "how full is the model's window right now". Computed from
     *  the last turn's reported token count (what every new turn re-reads)
     *  against the model's context window. Pure; formats:
     *    48000 tok + 200000 limit → "48k / 200k · 24%"
     *    limit <= 0 (unknown)     → "48k"          (depth only, honest)
     *    lastTurnTok <= 0         → ""            (nothing measured yet)
     *  Percent rounds to nearest whole; >100% clamps to 99+% wording. */
    public static String contextMeter(long lastTurnTok, long limit) {
        if (lastTurnTok <= 0) return "";
        // compact form: "48.0k" reads worse than "48k" in a ratio — strip
        // whole-number tails only (48.7k stays 48.7k)
        String depth = compact(fmtTok(lastTurnTok));
        if (limit <= 0) return depth;
        int pct = (int) Math.round(lastTurnTok * 100.0 / limit);
        String pctTxt;
        if (pct >= 100) {
            // over the window: the provider truncates or errors — say so
            pctTxt = "99%+";
        } else {
            pctTxt = pct + "%";
        }
        return depth + " / " + compact(fmtTok(limit)) + " · " + pctTxt;
    }

    /** "48.0k" → "48k", "2.0M" → "2M"; fractional tails untouched. */
    private static String compact(String v) {
        return v != null ? v.replace(".0k", "k").replace(".0M", "M") : null;
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

    // ------------------------------------------------- P20 resume + settle

    /** P20: the field bug — "i let the app work in background when i came
     *  back i couldnt look into a tought buble it looked empty". The chat
     *  unsubscribes from the event feed in onPause; every part that fired
     *  while away was lost FOREVER (onResume only refetched an empty list),
     *  so a thinking card born right before the pause never received a
     *  single delta. P20 replays the session from the server's message
     *  store on every resume. Parts with an id have a deterministic row
     *  key (messageID|partID) and upsert safely; a part WITHOUT an id is
     *  keyed with a session-local counter live, so a replay can NOT rebuild
     *  that key — it must be skipped for messages already on screen or it
     *  would duplicate the row. */
    public static boolean stablePartKey(String partId) {
        return partId != null && !partId.isEmpty();
    }

    /** P20: the collapsed thinking card's live preview — the FRESHEST
     *  slice of the reasoning text so far (≤ max chars, cut to a clean
     *  line start so the single-line ticker never shows a dangling
     *  half-line, trailing newline stripped). Empty/immature input → "". */
    public static String thinkWindow(String grown, int upto, int max) {
        if (grown == null || grown.isEmpty()) return "";
        if (max <= 0) return "";
        int end = Math.min(upto, grown.length());
        if (end <= 0) return "";
        int from = Math.max(0, end - max);
        String win = grown.substring(from, end);
        int nl = win.indexOf('\n');
        if (nl >= 0) win = win.substring(nl + 1);
        while (win.endsWith("\n")) win = win.substring(0, win.length() - 1);
        if (win.isEmpty()) return from > 0 ? "…" : "";
        return win;
    }

    /** P20: settle the chat after a resume replay ONLY when the last
     *  fetched message is an assistant message that FINISHED (time
     *  completed) — a run still going, or a trailing user message, must
     *  never stop the "working" state (P19's self-heal re-arms it). */
    public static boolean shouldSettle(boolean busy, boolean lastIsAssistant,
                                       boolean hasCompletedTs) {
        return busy && lastIsAssistant && hasCompletedTs;
    }

    // ------------------------------------------------- P21 exit forensics

    /** P21: human name for an ApplicationExitInfo reason code. Values
     *  PINNED against the API-34 android.jar (javap -constants), not from
     *  memory: 0 UNKNOWN, 1 EXIT_SELF, 2 SIGNALED, 3 LOW_MEMORY, 4 CRASH,
     *  5 CRASH_NATIVE, 6 ANR, 7 INITIALIZATION_FAILURE, 8
     *  PERMISSION_CHANGE, 9 EXCESSIVE_RESOURCE_USAGE, 10 USER_REQUESTED,
     *  11 USER_STOPPED, 12 DEPENDENCY_DIED, 13 OTHER, 14 FREEZER, 15
     *  PACKAGE_STATE_CHANGE, 16 PACKAGE_UPDATED. This is the instrument
     *  that finally NAMES the field killer: the app process died on P19
     *  AND P20 with no Java crash file — SIGKILL-class death — and the
     *  exit history answers WHO did it (LOW_MEMORY = LMKD, ANR =
     *  watchdog, CRASH_NATIVE = our own bug, FREEZER/SIGNALED = external),
     *  from the device, with no adb. Pure, JVM-pinned. */
    public static String exitReasonName(int reason) {
        switch (reason) {
            case 0:  return "unknown";
            case 1:  return "exited by itself";
            case 2:  return "killed by a signal";
            case 3:  return "LOW MEMORY — the system killed it to free RAM";
            case 4:  return "crash (unhandled Java exception)";
            case 5:  return "NATIVE crash (SIGSEGV/SIGABRT…)";
            case 6:  return "ANR — the UI froze and the system killed it";
            case 7:  return "initialization failure";
            case 8:  return "killed over a permission change";
            case 9:  return "excessive resource usage";
            case 10: return "stopped on user request";
            case 11: return "stopped by the user (swipe away)";
            case 12: return "a service it depended on died";
            case 13: return "other";
            case 14: return "released from the cached-app freezer";
            case 15: return "package state change";
            case 16: return "package updated";
            default: return "unknown reason " + reason;
        }
    }

    /** P21: one normalized diagnostics line for an exit record — fixed
     *  field order (ts · reason · signal · detail) so the log is
     *  greppable, same convention as diagLine. Pure. */
    public static String formatExitLine(long tsMs, int reason, int status,
                                        String desc) {
        StringBuilder b = new StringBuilder();
        if (tsMs > 0) {
            b.append(String.format(java.util.Locale.US, "%tF %<tR",
                    new java.util.Date(tsMs)));
        } else {
            b.append("time unknown");
        }
        b.append(" · ").append(exitReasonName(reason));
        if (reason == 2 || reason == 5) {
            b.append(" (signal ").append(status).append(")");
        }
        if (desc != null && !desc.isEmpty()) {
            String d = desc.replace('\n', ' ').trim();
            if (d.length() > 90) d = d.substring(0, 90) + "…";
            b.append(" · ").append(d);
        }
        return b.toString();
    }

    // ------------------------------------------------- P21 replay parsing

    /** P20/P21: did the LAST message of a /session/{id}/message array say
     *  the assistant run FINISHED (role=assistant + time.completed set)?
     *  Extracted from ChatActivity.reconcileAfterPause so the REAL server
     *  payloads can be replayed through it on the host JVM — the settle
     *  rule must be tested against reality, not against hope. Accepts
     *  both {info:{…}} wrapped and inline message shapes. A synthetic
     *  trailing message never settles (mirrors the replay loop, which
     *  skips synthetic entries). Pure. */
    public static boolean lastAssistantDoneFrom(List<?> messages) {
        if (messages == null || messages.isEmpty()) return false;
        Object lastObj = messages.get(messages.size() - 1);
        if (!(lastObj instanceof Map)) return false;
        Map<?, ?> item = (Map<?, ?>) lastObj;
        Object infoO = item.get("info");
        Map<?, ?> info = (infoO instanceof Map) ? (Map<?, ?>) infoO : item;
        if (Boolean.TRUE.equals(info.get("synthetic"))) return false;
        if (!"assistant".equals(info.get("role"))) return false;
        Object timeO = info.get("time");
        if (!(timeO instanceof Map)) return false;
        return ((Map<?, ?>) timeO).get("completed") != null;
    }

    // ------------------------------------------------- P23 blast-radius zero

    /**
     * P23 — THE on-send crash answer. The field device died on message
     * send with an unhandled Java exception (ApplicationExitInfo reason 4,
     * crash file written) while every audited send-path stage had a
     * catch(Exception). The hole: catch(Exception) does NOT stop Errors —
     * OutOfMemoryError, linkage failures, verifier throws — and the app's
     * many thread boundaries (worker pool, feed, SSE, drain, posted UI
     * runnables) let ANY of them kill the whole process.
     *
     * Contract from P23 on: nothing on the send/chat/feed paths dies from
     * a Throwable. It is CONTAINED — logged to the guard trail (⌘ → Logs
     * & shell), surfaced as one honest chat line — and the run degrades.
     * Pure + JVM-testable.
     */
    public static Throwable guard(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * P23: one bounded, greppable line for a contained failure — same
     * convention as diagLine: ts · what · class[: message] · top app
     * frame. The top frame is the first ai.opencode.app frame (framework
     * frames above it are noise). Pure.
     */
    public static String guardLine(long tsMs, String what, Throwable t) {
        StringBuilder b = new StringBuilder();
        b.append(tsMs).append(" · ").append(what == null ? "guarded" : what);
        b.append(" · ").append(t == null ? "-" : t.getClass().getName());
        if (t != null) {
            String m = t.getMessage();
            if (m != null && !m.isEmpty()) {
                m = m.replace('\n', ' ').replace('\r', ' ');
                if (m.length() > 160) m = m.substring(0, 160) + "…";
                b.append(": ").append(m);
            }
            StackTraceElement[] st = t.getStackTrace();
            if (st != null) {
                for (StackTraceElement e : st) {
                    String cn = e.getClassName();
                    if (cn != null && cn.startsWith("ai.opencode.app.")) {
                        b.append(" · ")
                         .append(cn.substring("ai.opencode.app.".length()))
                         .append('.').append(e.getMethodName())
                         .append(':').append(e.getLineNumber());
                        break;
                    }
                }
            }
        }
        return b.toString();
    }

    // ------------------------------------------------- P24 flush surgeon

    /**
     * P24 — the field device ran a build where the transcript FROZE while
     * the run stayed healthy ("doesn't crash but it still won't work"):
     * P23's one guard around the whole paint batch meant a single row
     * that could not paint aborted every row behind it, and the feed
     * re-dirtied that row on every delta — so every flush re-failed.
     * P24's contract: a row fails ALONE. After this many failed paint
     * attempts the row is QUARANTINED — its content is swapped for the
     * can't-fail {@link #quarantineLine()} — and the flush never touches
     * it again. One transient failure still gets a second chance; a
     * second failure means the row is poison. Pure.
     */
    public static int paintFailQuarantineAfter() { return 2; }

    /**
     * P24: the fallback line a quarantined row displays. Bounded, plain,
     * points at the trail — the part degrades visibly, the chat flows.
     * Pure.
     */
    public static String quarantineLine() {
        return "· this part could not be displayed — ⌘ → Logs & shell has the trace ·";
    }

    /**
     * P24: bounded one-line identity of a Throwable for IN-CHAT display —
     * class: message · top ai.opencode.app frame (same convention as
     * {@link #guardLine}, shorter cap). Repeated containment notes carry
     * this, so one screenshot from the field is a diagnosis. Pure.
     */
    public static String traceLine(Throwable t) {
        if (t == null) return "-";
        StringBuilder b = new StringBuilder();
        b.append(t.getClass().getName());
        String m = t.getMessage();
        if (m != null && !m.isEmpty()) {
            m = m.replace('\n', ' ').replace('\r', ' ');
            if (m.length() > 110) m = m.substring(0, 110) + "…";
            b.append(": ").append(m);
        }
        StackTraceElement[] st = t.getStackTrace();
        if (st != null) {
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn != null && cn.startsWith("ai.opencode.app.")) {
                    b.append(" · ")
                     .append(cn.substring("ai.opencode.app.".length()))
                     .append('.').append(e.getMethodName())
                     .append(':').append(e.getLineNumber());
                    break;
                }
            }
        }
        String s = b.toString();
        if (s.length() > 200) s = s.substring(0, 200) + "…";
        return s;
    }

    /** P21 replay-keying contract: how many parts in a fetched session
     *  LACK a stable part id. The resume replay updates known messages by
     *  the deterministic messageID|partID key; parts WITHOUT an id can
     *  not be re-keyed and are skipped for already-known messages (they
     *  would duplicate). If real payloads carry ids on every renderable
     *  part, nothing can be missed; this counter is how the contract test
     *  proves it against the actual v1.18.25 payloads. Pure. */
    public static int partsWithoutId(List<?> messages) {
        int n = 0;
        if (messages == null) return 0;
        for (Object o : messages) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> item = (Map<?, ?>) o;
            Object parts = item.get("parts");
            if (parts == null) {
                Object info = item.get("info");
                parts = (info instanceof Map) ? ((Map<?, ?>) info).get("parts") : null;
            }
            if (!(parts instanceof List)) continue;
            for (Object p : (List<?>) parts) {
                if (!(p instanceof Map)) continue;
                Object id = ((Map<?, ?>) p).get("id");
                if (id == null || String.valueOf(id).isEmpty()) n++;
            }
        }
        return n;
    }
}
