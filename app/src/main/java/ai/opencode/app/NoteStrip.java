package ai.opencode.app;

/**
 * P38 — the notes ride the message, but the user never sees the ride.
 *
 * P37's field report: the environment map rendered inside the user's own
 * bubble as a giant system-reminder block, burying the actual message
 * (and the same for the P35 render-check note and the P30 terse note on
 * their ride turns). The notes must still reach the MODEL — they fix
 * real problems (git probing, /root confusion, the render loop, the
 * reply style) — but the chat is for humans.
 *
 * The strip is display-only: the wire text keeps the notes, the server
 * stores them, the model reads them. Only the painted bubble cleans up —
 * which also repairs OLD sessions whose stored history already carries
 * the blocks (the replay paints through the same hub upsert).
 *
 * Pure text in, pure text out. Only blocks the APP itself wrote are
 * removed — recognized by their exact opening signatures — so a user
 * typing their own system-reminder lookalike keeps their text.
 */
public final class NoteStrip {

    private NoteStrip() {}

    /** The app's note signatures — what follows the opening tag. These
     *  MUST match the note builders (pinned cross-class in P38Test):
     *  EnvNote.note → "Environment:", RenderCheck.note → "App capability",
     *  TerseMode.note → "User preference updated". */
    static final String[] SIGNATURES = {
            "Environment:", "App capability", "User preference updated"
    };

    static final String OPEN = "<system-reminder>";
    static final String CLOSE = "</system-reminder>";

    /** TRUE when the block opening at {@code s} (offset {@code start}) is
     *  one of the app's own notes. {@code start} points at the '<' of the
     *  opening tag. */
    static boolean appNote(CharSequence s, int start) {
        if (s == null || start < 0 || start + OPEN.length() > s.length()) return false;
        for (int i = 0; i < OPEN.length(); i++)
            if (s.charAt(start + i) != OPEN.charAt(i)) return false;
        int p = start + OPEN.length();
        // optional newline between the tag and the signature (EnvNote has one)
        if (p < s.length() && s.charAt(p) == '\n') p++;
        for (String sig : SIGNATURES) {
            if (matchesAt(s, p, sig)) return true;
        }
        return false;
    }

    /** CharSequence-safe probe match. */
    private static boolean matchesAt(CharSequence s, int start, String probe) {
        if (start + probe.length() > s.length()) return false;
        for (int i = 0; i < probe.length(); i++)
            if (s.charAt(start + i) != probe.charAt(i)) return false;
        return true;
    }

    /** The text a human reads: every leading app-authored
     *  system-reminder block removed, plus the blank seam that separated
     *  it from the message. Blocks in the MIDDLE of the text (not at the
     *  seam under inspection) stay — the app only ever prepends. */
    public static String display(String wire) {
        if (wire == null || wire.isEmpty()) return wire;
        String t = wire;
        while (true) {
            // leading whitespace before a block is part of the seam
            int i = 0;
            while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
            if (i >= t.length()) return "";          // nothing but notes
            if (!appNote(t, i)) {
                t = t.substring(i);                  // trim the seam only
                break;
            }
            int close = indexOfClose(t, i + OPEN.length());
            if (close < 0) break;                    // unterminated → keep
            t = t.substring(close + CLOSE.length());
        }
        return t;
    }

    /** The closing tag from offset {@code from}, or -1. */
    private static int indexOfClose(CharSequence s, int from) {
        for (int i = Math.max(0, from); i <= s.length() - CLOSE.length(); i++)
            if (s.charAt(i) == '<' && matchesAt(s, i, CLOSE))
                return i;
        return -1;
    }

    /** P38 — the residual-gap fix from the field: a message body ending
     *  in blank lines paints them (TextView honors trailing newlines), so
     *  the token footer floats a visible void below the text. Trim the
     *  display tail; interior whitespace is untouched, and leading blank
     *  lines go too (nobody indents a chat message with empty lines).
     *  Display + copy only — the stored text keeps its bytes. */
    public static String trimTail(String s) {
        if (s == null || s.isEmpty()) return s;
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        int start = 0;
        while (start < end && (s.charAt(start) == '\n' || s.charAt(start) == '\r')) start++;
        return (start == 0 && end == s.length()) ? s : s.substring(start, end);
    }
}
