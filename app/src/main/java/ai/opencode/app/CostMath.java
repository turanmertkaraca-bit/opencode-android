package ai.opencode.app;

/**
 * P29 — the cost-prediction math, PURE so the JVM suite pins every number.
 *
 * The user's ask: "the first input prediction is easy — the user would at
 * least know what they are paying for so they can clear the context to
 * make it cheaper." This class turns (typed text + pending images + the
 * session's current context depth + the picked model's input price) into
 * the one quiet line above the composer.
 *
 * Honesty rules:
 *   • Token counts are ESTIMATES — a documented heuristic, never a claim.
 *   • The cost shown is the WORST-CASE next input: providers re-read the
 *     whole context every turn and price the full prompt as input. Prompt
 *     caching (which the app already surfaces in the Σ popover) can only
 *     make the real bill SMALLER, never bigger — so the estimate errs
 *     pessimistic on purpose.
 *   • Output tokens are unknowable before the send → never pretended.
 *
 * Image token estimate: the OpenAI-style pixels/750 heuristic, clamped to
 * a sane band. The app's vision pipeline downscales to a ≤1024 px long
 * edge (Vision.downscale), so real attaches land at ~800-1400 tokens.
 */
public final class CostMath {

    private CostMath() {}

    /** Messenger-style tray cap — past this the message body itself is the
     *  bigger cost driver and the tray stops being readable anyway. */
    public static final int MAX_ATTACHMENTS = 6;

    /**
     * Estimated tokens for a piece of text. ASCII English runs ~4 chars per
     * token; CJK and most non-ASCII scripts run ~1 token per character.
     * Whitespace-heavy padding is naturally absorbed by the /4 rule.
     * Empty/blank → 0 (nothing to price). Never negative, min 1 when any
     * non-whitespace content exists.
     */
    public static long estimateTextTokens(String text) {
        if (text == null) return 0;
        String t = text.trim();
        if (t.isEmpty()) return 0;
        long ascii = 0, wide = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) < 128) ascii++; else wide++;
        }
        long tok = ascii / 4 + wide;
        return Math.max(1, tok);
    }

    /**
     * Estimated tokens for one image of w×h pixels: ceil(px / 750),
     * clamped to [450, 4000]. Zero/negative dims → 0 (undecodable attach
     * prices nothing rather than inventing a number).
     */
    public static long estimateImageTokens(int w, int h) {
        if (w <= 0 || h <= 0) return 0;
        long px = (long) w * (long) h;
        long tok = (px + 749) / 750;
        return Math.max(450, Math.min(4000, tok));
    }

    /** Whole new-input estimate: text + nImages × per-image. */
    public static long nextInputTokens(String text, int nImages, int imgW, int imgH) {
        long img = nImages <= 0 ? 0 : estimateImageTokens(imgW, imgH);
        return estimateTextTokens(text) + img * Math.max(0, nImages);
    }

    /**
     * Worst-case next-input cost: the model re-reads the WHOLE window, so
     * the priced prompt is (context + new) × input $/Mtok. costIn <= 0 →
     * 0 (free model or unknown price — callers show tokens, not money).
     */
    public static double nextInputCost(long ctxTok, long newTok, double costInPerMtok) {
        if (costInPerMtok <= 0) return 0;
        long total = Math.max(0, ctxTok) + Math.max(0, newTok);
        return total / 1_000_000.0 * costInPerMtok;
    }

    /** Context fill as a whole percent, 0 when either side is unknown. */
    public static int windowPct(long ctxTok, long limit) {
        if (ctxTok <= 0 || limit <= 0) return 0;
        return (int) Math.round(ctxTok * 100.0 / limit);
    }

    /**
     * The quiet hint line above the composer. Contract:
     *   no new content (no text, no attaches)            → ""  (hidden)
     *   tokens only (price unknown / free)               → "≈ 1.2k new · ctx 48k"
     *   priced                                           → "≈ 1.2k new · next ≈ $0.0142 · ctx 48k"
     *   free model with a known 0 price                  → "… · free model"
     *   ctx ≥ 50% of the window                          → " · compact to pay less"
     * The Σ pill keeps its exact protected format; this line never feeds it.
     */
    public static String hintLine(long newTok, long ctxTok, double cost,
                                  boolean priceKnown, long limit) {
        if (newTok <= 0) return "";
        StringBuilder b = new StringBuilder();
        b.append("≈ ").append(compact(Resilience.fmtTok(newTok))).append(" new");
        if (priceKnown) {
            if (cost > 0) b.append(" · next ≈ ").append(Resilience.fmtCost(cost));
            else b.append(" · free model");
        }
        if (ctxTok > 0) b.append(" · ctx ").append(compact(Resilience.fmtTok(ctxTok)));
        if (windowPct(ctxTok, limit) >= 50) b.append(" · compact to pay less");
        return b.toString();
    }

    /** "48.0k" → "48k" — the same whole-tail rule the Σ meter uses;
     *  a quiet hint should not read like a spreadsheet. */
    private static String compact(String v) {
        return v != null ? v.replace(".0k", "k").replace(".0M", "M") : null;
    }
}
