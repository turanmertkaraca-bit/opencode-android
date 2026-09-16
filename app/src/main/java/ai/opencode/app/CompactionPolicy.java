package ai.opencode.app;

import java.util.Map;

/**
 * P41 — the compaction governance release, pinned at the source.
 *
 * WHAT THE FIELD SHOWED. On P40 the in-app agent kept re-exploring the
 * same empty project folder, burning real money, while the Σ pill read
 * "16% — light, nothing to worry about". The bundled server's own source
 * (opencode 1.18.25) explains every part of it: when a turn's billed
 * tokens reach the window THE SERVER believes (its own model metadata,
 * not our catalog), it silently summarizes the whole thread and keeps
 * only {@code preserve_recent_tokens} of recent turns VERBATIM — a
 * default clamp of just 2k–15k tokens. The on-screen store keeps the
 * full history, so the human sees everything while the model is left
 * with a paragraph and a shallow tail; a fast model writes a shallow
 * summary, loses its map of the project, re-explores, the payload
 * refills, and the cycle repeats — each cycle also invalidating the
 * provider prompt cache, so the whole payload re-bills at full price.
 *
 * THE SOURCE FIX (no detectors, no ride-along patches):
 *  1. RAISE THE MEMORY FLOOR — the app now pins
 *     compaction.preserve_recent_tokens in opencode.json (the file the
 *     server reads at boot) scaled from the model's window. After any
 *     compaction the model keeps tens of thousands of tokens of real
 *     recent turns instead of ≤15k. Write-if-absent: a value the user
 *     (or a future release) already wrote always wins; small windows
 *     keep the server default, where the clamp is harmless.
 *  2. ONE TRUTH FOR THE WINDOW — the Σ meter's denominator now prefers
 *     the LIVE server's limit (the number that actually triggers
 *     compaction) over the catalog's advertised one; the Σ popover
 *     names both when they disagree and warns before compaction is
 *     imminent (Models.resolveLimit, ChatActivity.spendPopover).
 *  3. COMPACTION BECOMES VISIBLE — when a summary lands, the chat says
 *     one honest line instead of painting it as an ordinary message
 *     (RunHub.applyMessageInfo).
 *
 * THIS CLASS IS PURE: numbers in, strings out, map in place. The JVM
 * suite pins every branch; nothing here touches Android or the network.
 */
public final class CompactionPolicy {

    private CompactionPolicy() {}

    /** Never write a floor below this — under it the server default
     *  (2k–15k clamp) is already fine and a big tail would be noise. */
    public static final long FLOOR = 8_000;

    /** Never write a floor above this — even a 1.3M-window model does
     *  not need more verbatim tail, and a smaller real window must
     *  never end up with a preserve bigger than its usable space
     *  (that would make every compaction a no-op or a full wipe). */
    public static final long CAP = 60_000;

    /** The server reserves ~20k tokens of compaction buffer before the
     *  window is usable (overflow.ts COMPACTION_BUFFER, capped by the
     *  model's max output). Our ratio applies to the usable part. */
    public static final long RESERVE = 20_000;

    /** Windows below this keep the server default entirely — tiny-window
     *  models have no memory worth a floor and every token counts. */
    public static final long MIN_WINDOW = 64_000;

    /** The share of the usable window kept verbatim after a compaction.
     *  30% of a 128k window ≈ 32k tokens of real recent turns — versus
     *  the server default's 15k worst case — while never exceeding the
     *  usable space for any window at or above the catalog claim. */
    public static final double RATIO = 0.30;

    /**
     * The preserve_recent_tokens value to pin for a model whose context
     * window is {@code limit} tokens; 0 = do not write (window unknown
     * or too small — the server default stands). The result is always
     * ≤ CAP and, for any window ≥ MIN_WINDOW, comfortably below the
     * usable space (limit − RESERVE), so a compaction always frees
     * something and the tail stays verbatim history, never the whole
     * payload. Pure; the suite pins the table.
     */
    public static long preserveRecentTokensFor(long limit) {
        if (limit < MIN_WINDOW) return 0;
        long v = (long) Math.floor(RATIO * (limit - RESERVE));
        if (v < FLOOR) v = FLOOR;
        if (v > CAP) v = CAP;
        return v;
    }

    /**
     * The window the compaction floor should assume. P42-check: the
     * user's window cap (ContextPolicy) shrinks what the server actually
     * enforces — a floor pinned to the model's full window on a capped
     * model clamps to CAP and makes every compaction a no-op (the exact
     * hazard the floor exists to prevent). The smaller of the two wins;
     * either alone stands; both unknown = 0 (do not write). Pure.
     */
    public static long effectiveWindow(long modelWindow, long userCap) {
        boolean m = modelWindow > 0, c = userCap > 0;
        if (m && c) return Math.min(modelWindow, userCap);
        return m ? modelWindow : (c ? userCap : 0);
    }

    /**
     * Merge the floor into an opencode.json root map IN PLACE. The rule
     * is hands-off by construction: an existing compaction block that
     * already carries a preserve_recent_tokens key — ours, the user's,
     * or even a malformed one — is never touched, and a window under
     * MIN_WINDOW (or unknown) writes nothing. True when this call added
     * the key. Pure; the suite pins every branch.
     */
    static boolean mergePreserve(Map<String, Object> root, long limit) {
        if (root == null) return false;
        Object cur = root.get("compaction");
        // a compaction block that is not even an object is user-owned
        // junk — the server ignores it, and so do we
        if (cur != null && !(cur instanceof Map)) return false;
        Map<String, Object> comp = Json.obj(cur);
        if (comp != null && comp.containsKey("preserve_recent_tokens"))
            return false;
        long v = preserveRecentTokensFor(limit);
        if (v <= 0) return false;
        if (comp == null) {
            comp = new java.util.LinkedHashMap<>();
            root.put("compaction", comp);
        }
        comp.put("preserve_recent_tokens", v);
        return true;
    }

    /**
     * P42: merge-or-move. The floor is a FUNCTION OF THE WINDOW, so a
     * model switch must be able to MOVE an app-written value (a 60k
     * floor riding along to a newly-picked 64k-window model would make
     * every compaction a no-op — the exact hazard mergePreserve's
     * hands-off rule created). Ownership rule: an existing value is
     * updated ONLY when it is exactly the value the app previously
     * wrote ({@code appWritten}, tracked by AuthStore in prefs); a
     * user-edited value — anything else, even a malformed one — is
     * never touched. Returns the value written this call (0 = nothing
     * changed). Pure; the suite pins the table.
     */
    static long mergeOrMovePreserve(Map<String, Object> root, long limit,
                                    long appWritten) {
        if (root == null) return 0;
        long v = preserveRecentTokensFor(limit);
        Object cur = root.get("compaction");
        if (cur != null && !(cur instanceof Map)) return 0;
        Map<String, Object> comp = Json.obj(cur);
        if (comp == null) {
            if (v <= 0) return 0;
            comp = new java.util.LinkedHashMap<>();
            root.put("compaction", comp);
            comp.put("preserve_recent_tokens", v);
            return v;
        }
        if (!comp.containsKey("preserve_recent_tokens")) {
            if (v <= 0) return 0;
            comp.put("preserve_recent_tokens", v);
            return v;
        }
        Object curV = comp.get("preserve_recent_tokens");
        if (v > 0 && appWritten > 0 && curV instanceof Number
                && ((Number) curV).longValue() == appWritten
                && ((Number) curV).longValue() != v) {
            comp.put("preserve_recent_tokens", v);
            return v;
        }
        return 0;
    }

    // ------------------------------------------------------------ notes

    /** The one honest chat line when a compaction summary lands. Says
     *  what happened, what it costs the model, and that the screen
     *  shows more than the model remembers. Never a token-shaped
     *  string; the suite pins the shape. */
    public static String summaryNote() {
        return "◈ the model's memory was just summarized — the conversation "
                + "outgrew the model's window, so the sandbox kept the summary "
                + "plus the most recent turns and the rest now lives only in "
                + "that summary. Your screen still shows the whole chat; the "
                + "model does not. If it loses the thread, start a fresh chat "
                + "for the next task";
    }

    /**
     * P44: the one honest chat line when a compaction summary lands,
     * with the window cap named when one is active — the slider
     * override is the one compaction trigger a user can set without
     * realizing it, and "the context randomly collapses" is exactly
     * what an unnamed active cap reads like from the outside. Pure;
     * the suite pins both branches.
     */
    public static String summaryNote(long userCap) {
        String base = summaryNote();
        if (userCap <= 0) return base;
        return base + ". A window cap of " + Resilience.fmtTok(userCap)
                + " tokens is active on this model (set from the Σ "
                + "popover) — summarizing will keep firing near that "
                + "size until the cap is raised or cleared";
    }

    /**
     * P44 — the post-compaction anchor. When a summary lands, the model
     * keeps the summary plus a verbatim tail; the OLDEST part of the
     * thread (the original task) is exactly what got thinned into that
     * summary, and the sandbox ground rules (the P42 session-start note)
     * can be summarized away with it. That is the field loop, end to
     * end: summarize → forget the rules → re-probe the sandbox →
     * refill → summarize again, each cycle re-billing the payload at
     * full price. The anchor rides the FIRST real user send after the
     * summary: it re-names the thread (opening request, latest real
     * user message, last assistant reply), re-states the ground rules,
     * names an active window cap, and instructs continuation — never
     * restart. Same NoteStrip signature as the greeting recap, so the
     * user's bubble stays clean. Pure; the suite pins the branches.
     */
    public static String anchorBlock(int turns, String firstUser,
                                     String lastUser, String lastAssistant,
                                     long userCap) {
        StringBuilder b = new StringBuilder();
        b.append("<system-reminder>\n").append(GreetGuard.SIG)
         .append(": your memory of this chat was just summarized — it is ")
         .append(Math.max(0, turns))
         .append(" messages deep and the older turns now exist only in ")
         .append("the summary above");
        String first = GreetGuard.clip(firstUser, 140);
        if (first != null)
            b.append("; the task opened with: \"").append(first).append("\"");
        String last = GreetGuard.clip(lastUser, 140);
        if (last != null)
            b.append("; the user's latest real request was: \"")
             .append(last).append("\"");
        String asst = GreetGuard.clip(lastAssistant, 160);
        if (asst != null)
            b.append("; you last said: \"").append(asst).append("\"");
        if (userCap > 0)
            b.append("; a window cap of ").append(Resilience.fmtTok(userCap))
             .append(" tokens is set for this model (Σ popover), so this ")
             .append("summarizing repeats near that size");
        b.append(". The sandbox ground rules stand unchanged: TLS is ")
         .append("provisioned and working, raw DNS cannot work by design, ")
         .append("and a probe that fails twice is reported and dropped — ")
         .append("never re-probed, never re-explored. Continue this ")
         .append("exact task from the recap; do not restart it unless ")
         .append("the user asks. If the message below is only a ")
         .append("greeting, acknowledge it briefly and offer to ")
         .append("continue.\n</system-reminder>");
        return b.toString();
    }

    /**
     * The Σ popover line while compaction is imminent: within 30% of
     * the window the server actually enforces, the next big turn can
     * summarize memory without warning. Null while there is room.
     * Pure; the suite pins the threshold and the null cases.
     */
    public static String riskNote(long depth, long limit) {
        if (limit <= 0 || depth <= 0) return null;
        long pct = depth * 100 / limit;
        if (pct < 70) return null;
        return "compaction is close: near " + Resilience.fmtTok(limit)
                + " tokens the sandbox summarizes this chat's memory "
                + "automatically. Finish the task or start a fresh chat to "
                + "keep the details";
    }

    /**
     * The Σ popover line when the two window sources disagree beyond
     * ~20%: the catalog advertises more room than the sandbox enforces.
     * The SMALLER number is the one that decides compaction, so the
     * meter already shows it — this line explains why. Null when the
     * sources agree, either is missing, or the catalog is the smaller
     * one (the meter then shows the catalog number, which errs safe).
     * Pure; the suite pins the branches.
     */
    public static String mismatchNote(long serverLimit, long catalogLimit) {
        if (serverLimit <= 0 || catalogLimit <= 0) return null;
        if (catalogLimit <= serverLimit + serverLimit / 5) return null;
        return "two sources disagree about this model's window: the sandbox "
                + "compacts at " + Resilience.fmtTok(serverLimit)
                + " tokens while the online catalog claims "
                + Resilience.fmtTok(catalogLimit)
                + " — the meter shows the smaller number, because that is "
                + "the one the sandbox enforces";
    }
}
