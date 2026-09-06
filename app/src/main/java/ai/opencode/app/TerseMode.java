package ai.opencode.app;

/**
 * P29→P30 — the token-saver reply style.
 *
 * P29 shipped it as a MANAGED BLOCK written into the project's AGENTS.md.
 * The field report (P30) killed that design with two findings:
 *
 *   1. NOTHING HAPPENS MID-SESSION. opencode reads AGENTS.md once per
 *      session — the user flips the toggle, the running conversation
 *      keeps the style it started with, and the feature "looks broken".
 *   2. THE SETTING LEAKED. AGENTS.md lives inside the project folder, so
 *      a chat style built for one user's phone showed up in git diffs
 *      and became project-wide rules for every other session.
 *
 * P30 moves the toggle INTO the app: a plain preference (no file writes,
 * nothing to commit, nothing to leak), and the preference reaches the
 * model as a <system-reminder> note PREPENDED to the user's next message
 * in that session — the exact mechanism the field report validated. No
 * extra turn is spent, no extra message bubbles in, and the model picks
 * the style up from that message onward. The per-session "already told"
 * state (RunHub.styleTold) keeps the note to exactly one ride per change.
 *
 * The managed-block machinery below SURVIVES for one reason: the upgrade
 * migration. Devices that toggled the style under P29 have our block
 * sitting in their AGENTS.md; App boot strips it (strip is pure,
 * idempotent, byte-preserving for user content). merge/isOn stay pinned
 * by the P29 suite and document what we no longer do.
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

    /** P30 — the live-injection notes. One line each: this rides the
     *  user's OWN message, so it must cost tokens like a sentence, not
     *  like a document. The full P29 TEXT block is the migration
     *  reference; the note distills it. */
    public static final String NOTE_ON =
            "<system-reminder>User preference updated (applies to every reply "
            + "from now on): TERSE token-saver mode is ON — act first, talk "
            + "less: run the command, make the edit, then one short line on "
            + "what happened; show diffs instead of describing them; no "
            + "pleasantries, no filler, no restating the request.</system-reminder>";

    public static final String NOTE_OFF =
            "<system-reminder>User preference updated (applies to every reply "
            + "from now on): TERSE token-saver mode is OFF — normal, complete "
            + "explanations are welcome again.</system-reminder>";

    public static String note(boolean on) { return on ? NOTE_ON : NOTE_OFF; }

    /**
     * Pure state machine: does the NEXT message in this session need to
     * carry the preference note?
     *   told == null  → this session never heard the preference:
     *                   inject when ON (OFF is the default — silence).
     *   told == state → the session already runs this exact style: no note.
     *   told != state → the user flipped the toggle mid-conversation:
     *                   inject (ON→OFF must ALSO be announced, or the
     *                   model stays terse forever).
     */
    public static boolean needsInject(boolean on, Boolean told) {
        if (on) return !Boolean.TRUE.equals(told);
        return Boolean.TRUE.equals(told);
    }

    /**
     * The wire text for the next send: the user's text unchanged when the
     * session is already in sync, otherwise the note prepended above it
     * (one text part — the note and the message travel together, the
     * model reads both in one turn). Pure.
     */
    public static String wrap(String text, boolean on, Boolean told) {
        if (!needsInject(on, told)) return text;
        return note(on) + "\n\n" + text;
    }

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
     * P30: app code no longer CALLS this — it survives as the migration
     * reference and the P29 suite pin.
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
