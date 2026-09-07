package ai.opencode.app;

/**
 * P31 — the credit limit. A safety cap on spending: when the accumulated
 * all-time spend (this device) reaches the user's cap, sends are refused
 * with one honest line instead of silently burning another cent.
 *
 * Pure — every rule here is JVM-pinned by P31Test. The accumulator lives
 * in RunHub (it owns the per-message cost deltas); the cap itself is the
 * "spend_cap" preference (empty string = no limit).
 */
public final class CreditLimit {

    private CreditLimit() {}

    public static final int OK = 0, WARN = 1, BLOCK = 2;

    /** Sanity ceiling — a fat-fingered "50000" is legal, "99999999" is not. */
    public static final double CAP_MAX = 100_000.0;

    /**
     * Parse the cap the user typed. Returns:
     *   0  → no limit (empty/null input),
     *   >0 → the cap in dollars ("$5", "5", "5.50", " 5 " all work),
     *   -1 → invalid (negative, garbage, over the sanity ceiling).
     * One comma decimal form is accepted ("5,50") because several
     * keyboard layouts type it by default.
     */
    public static double parseCap(String raw) {
        if (raw == null) return 0;
        String s = raw.trim().replace("$", "").replace(",", ".");
        if (s.isEmpty()) return 0;
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
        if (v < 0) return -1;
        if (v == 0) return 0;                    // "0" = no limit, same as empty
        if (v > CAP_MAX) return -1;
        return v;
    }

    /** OK / WARN (≥80% of cap) / BLOCK (≥ cap). cap ≤ 0 → always OK. */
    public static int verdict(double spent, double cap) {
        if (cap <= 0) return OK;
        if (spent >= cap) return BLOCK;
        if (spent >= cap * 0.8) return WARN;
        return OK;
    }

    /** The one line a BLOCKED send sees in the transcript. */
    public static String blockLine(double spent, double cap) {
        return "⛔ credit limit reached — " + fmt(spent) + " spent of the "
                + fmt(cap) + " cap. Raise or clear it in ⌘ → Settings → "
                + "Safety (spending is paused, nothing was sent)";
    }

    /** Short state line for the subtitle / Σ popover. */
    public static String stateLine(double spent, double cap) {
        if (cap <= 0) return spent > 0 ? fmt(spent) + " spent total" : "";
        int pct = (int) Math.round(spent / cap * 100.0);
        return fmt(spent) + " of " + fmt(cap) + " (" + pct + "%)";
    }

    /** "$12.5" style — whole dollars stay bare, cents trimmed to 2. */
    public static String fmt(double v) {
        if (v >= 100 || v == Math.floor(v)) 
            return "$" + String.format(java.util.Locale.US, "%.0f", v);
        if (Math.abs(v * 100 - Math.round(v * 100)) < 1e-9)
            return "$" + String.format(java.util.Locale.US, "%.2f", v);
        return "$" + String.format(java.util.Locale.US, "%.2f", v);
    }
}
