package ai.opencode.app;

/**
 * P31 — where the user left off. Each chat screen stamps "last_screen"
 * on pause; after a hibernation (or any cold open) MainActivity drops
 * the user back into THAT surface instead of always landing on the deck.
 *
 * Format: "chat|<project name>|<project path>" or "deck". Parsing is
 * pure and defensive — a hand-edited or truncated value must never
 * crash the boot path (worst case: fall back to the deck).
 */
public final class Resume {

    private Resume() {}

    public static final String KEY = "last_screen";
    public static final String DECK = "deck";

    /** Stamp a chat surface. */
    public static String chatValue(String name, String path) {
        return "chat|" + nz(name) + "|" + nz(path);
    }

    /**
     * Parse the stamp. Returns:
     *   {"chat", name, path}  — a restorable chat (path non-empty),
     *   {"deck"}              — anything else (absent, deck, garbage).
     */
    public static String[] parseLastScreen(String pref) {
        if (pref == null) return new String[]{DECK};
        if (DECK.equals(pref)) return new String[]{DECK};
        if (!pref.startsWith("chat|")) return new String[]{DECK};
        String rest = pref.substring(5);
        int cut = rest.indexOf('|');
        String name = cut >= 0 ? rest.substring(0, cut) : rest;
        String path = cut >= 0 ? rest.substring(cut + 1) : "";
        if (path.isEmpty()) return new String[]{DECK};
        return new String[]{"chat", name, path};
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ---------------------------------------------------- P34: back rule

    /** Where BACK from a chat lands. BACK_STAY: plain finish() — the deck
     *  sits below in the task and is revealed. BACK_OPEN_DECK: the chat
     *  IS the task root (the P34 field report: after an app update the
     *  boot routed straight into the last chat and finished the splash,
     *  so finish() had nothing beneath it — both the system back and the
     *  in-app ‹ closed the whole app). The deck must be opened
     *  explicitly. Pure + JVM-pinned by P34Test. */
    public static final int BACK_STAY = 0, BACK_OPEN_DECK = 1;

    public static int backRule(boolean isTaskRoot) {
        return isTaskRoot ? BACK_OPEN_DECK : BACK_STAY;
    }
}
