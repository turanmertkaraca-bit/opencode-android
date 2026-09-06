package ai.opencode.app;

/**
 * P29 — the token-saver reply style, written into the project's AGENTS.md
 * as a MANAGED BLOCK.
 *
 * Where this comes from (web research, the user's pointer): the community
 * "i-have-adhd" preset and the caveman output style cut output tokens
 * 40-65% with one move — the agent ACTS more and TALKS less (run the
 * command, make the edit, skip the essay). openslimedit attacks the same
 * bill from the input side. The app already compacts its own meters; this
 * is the honest lever we have over the agent's own verbosity, and AGENTS.md
 * is the mechanism opencode itself documents for project-level rules —
 * no per-message overhead, no server patches, survives every restart.
 *
 * Managed block = the app owns EXACTLY the text between the markers and
 * nothing else. User-written AGENTS.md content is preserved byte-for-byte;
 * toggling OFF removes only our block. All pure, JVM-pinned.
 */
public final class TerseMode {

    private TerseMode() {}

    public static final String START = "<!-- oc-mobile:terse:start -->";
    public static final String END = "<!-- oc-mobile:terse:end -->";

    /** The action-first instruction — distilled from the community presets
     *  (i-have-adhd / caveman): act first, no restating, code speaks. */
    public static final String TEXT =
            "## Reply style (token saver)\n"
            + "- Act first, explain after: run the command / make the edit, then one short line on what happened.\n"
            + "- Skip pleasantries, skip restating my request, skip announcing what you are about to do — just do it.\n"
            + "- Code speaks: show diffs and commands instead of describing them. Never repeat file contents back at me.\n"
            + "- Prose is the enemy: short sentences, no filler, no apologies, no praise, no summaries of the summary.\n"
            + "- If a step needs no words (a pure edit or run), the result alone is the reply.\n"
            + "- Terse commit messages, no attribution footers.";

    /** True when the managed block is present in this AGENTS.md content. */
    public static boolean isOn(String content) {
        return content != null && content.contains(START);
    }

    /**
     * Remove the managed block (and the blank-line seam it left behind)
     * from content. User content around it is untouched. Null-safe.
     */
    public static String strip(String content) {
        if (content == null) return "";
        int s = content.indexOf(START);
        if (s < 0) return content;
        int e = content.indexOf(END, s);
        int cutEnd = e < 0 ? content.length() : e + END.length();
        String out = content.substring(0, s) + content.substring(cutEnd);
        return out.replaceAll("\\n{3,}$", "\n").replaceAll("^\\n{2,}", "");
    }

    /**
     * merge(existing, on):
     *   on  → stripped base + our block (idempotent: merging twice is the
     *         same file as merging once);
     *   off → stripped base (idempotent the same way).
     * Never throws; null existing is "no file yet".
     */
    public static String merge(String existing, boolean on) {
        String base = strip(existing);
        if (!on) return base;
        StringBuilder b = new StringBuilder(base);
        if (base.length() > 0) b.append("\n\n");
        b.append(START).append('\n')
         .append(TEXT).append('\n')
         .append(END).append('\n');
        return b.toString();
    }
}
