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

    /** P34: the quick-pick presets in the new credit-limit sheet — the
     *  Pixel-style answer to "a slider wouldn't make sense, you wouldn't
     *  know the price range": tap a common cap, or type an exact one.
     *  The sentinel CAP_NONE renders as "no limit". JVM-pinned. */
    public static final double[] QUICK_CAPS = {5, 10, 25, 50, 100, -1};
    /** The sentinel inside QUICK_CAPS meaning "clear the cap". */
    public static final double CAP_NONE = -1;

    /** The chip label for a quick-cap value ("$25" / "no limit"). */
    public static String chipLabel(double v) {
        return v == CAP_NONE ? "no limit" : fmt(v);
    }

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
        String s = raw.trim().replace("$", "");
        if (s.isEmpty()) return 0;
        // P51: a comma is only a DECIMAL point when it is not thousands
        // grouping — "5,50" → 5.50 but "1,000" → 1000 (the old blanket
        // replace turned 1,000 into $1.00 and blocked sends after $1).
        if (s.indexOf(',') >= 0) {
            s = isThousandsGrouped(s)
                    ? s.replace(",", "")
                    : s.replace(',', '.');
        }
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

    /** True for "1,000" / "1,000,000" / "12,500.75" style thousands grouping
     *  (integer groups of 1-3 then 3 digits; no comma in the fraction). */
    private static boolean isThousandsGrouped(String s) {
        int dot = s.indexOf('.');
        String head = dot < 0 ? s : s.substring(0, dot);
        if (dot >= 0 && s.indexOf(',', dot) >= 0) return false;
        String[] g = head.split(",", -1);
        if (g.length < 2 || g[0].isEmpty() || g[0].length() > 3) return false;
        for (int i = 0; i < g[0].length(); i++)
            if (!Character.isDigit(g[0].charAt(i))) return false;
        for (int i = 1; i < g.length; i++) {
            if (g[i].length() != 3) return false;
            for (int j = 0; j < g[i].length(); j++)
                if (!Character.isDigit(g[i].charAt(j))) return false;
        }
        return true;
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
