package ai.opencode.app;

/**
 * P43 — the greeting-context guard. The field report: mid-session, the
 * user typed a short "hello world" style message and the model answered
 * "Hello! What would you like help with?" — as if the hours of work in
 * front of it did not exist. The turn WAS billed with the full payload
 * (the history reached the model), so this is not amnesia — it is the
 * model treating a bare greeting as a fresh-conversation opener and
 * politely discarding the thread.
 *
 * The cure is context, not silence: when a send is a bare greeting AND
 * the session already has a real thread, the message quietly carries a
 * session-context reminder (the same ride-along pattern as the env,
 * terse, render and repair notes — NoteStrip keeps the user's bubble
 * clean). The reminder names where the thread stands and instructs the
 * model to answer AS the assistant that has been there the whole time.
 * The user's words are never altered on the wire; only preceded.
 *
 * Pure text in, pure text out; every branch pinned in P43Test.
 */
public final class GreetGuard {

    private GreetGuard() {}

    /** NoteStrip signature — MUST stay in sync with NoteStrip.SIGNATURES
     *  (pinned cross-class in the JVM suite). */
    public static final String SIG = "Session context";

    /** The greeting vocabulary: common openers and throwaway test lines
     *  ("hello world" among them — the exact message the field sent). */
    static final String[] WORDS = {
            "hi", "hey", "hello", "yo", "sup", "hiya", "howdy", "hei",
            "test", "testing", "ping", "hola", "hallo", "selam", "merhaba"
    };

    /** TRUE when a send is a bare greeting: short (≤ 4 words), no code,
     *  no URL, no task marker — made of greeting/test vocabulary and
     *  filler only. "hello world" and "hi!" qualify; "hello, can you
     *  fix the build" does not. Pure. */
    public static boolean isBareGreeting(String t) {
        if (t == null) return false;
        String s = t.trim().toLowerCase();
        if (s.isEmpty() || s.length() > 32) return false;
        if (s.contains("`") || s.contains("http") || s.contains("/")
                || s.contains("\n") || s.contains("?")) return false;
        String[] tok = s.split("[^a-z0-9]+");
        int words = 0;
        for (String w : tok) {
            if (w.isEmpty()) continue;
            words++;
            if (words > 4) return false;
            boolean known = false;
            for (String k : WORDS) if (k.equals(w)) { known = true; break; }
            // world / it / there …: filler that follows a greeting word
            if (!known && !isFiller(w)) return false;
        }
        return words >= 1;
    }

    /** Filler words a bare greeting may carry ("hi there", "hello world",
     *  "test one two"). Anything verb-like disqualifies — the guard must
     *  never swallow a real instruction. */
    static boolean isFiller(String w) {
        switch (w) {
            case "world": case "there": case "the": case "a": case "an":
            case "it": case "its": case "is": case "are": case "you":
            case "u": case "one": case "two": case "three": case "123":
            case "1": case "2": case "3":
                return true;
            default:
                return false;
        }
    }

    /**
     * The ride-along block. {@code turns} = messages already in the
     * thread; {@code firstUser} the session's opening request (trimmed);
     * {@code lastUser} the previous real user message (trimmed);
     * {@code lastAssistant} the tail of the last assistant reply
     * (trimmed). Any part may be null/empty — the block adapts.
     */
    public static String recapBlock(int turns, String firstUser,
                                    String lastUser, String lastAssistant) {
        StringBuilder b = new StringBuilder();
        b.append("<system-reminder>\n").append(SIG).append(": this chat ")
         .append("is ").append(Math.max(0, turns))
         .append(" messages deep — it is NOT a new conversation");
        String first = clip(firstUser, 110);
        if (first != null) b.append("; it opened with: \"").append(first).append("\"");
        String last = clip(lastUser, 110);
        if (last != null) b.append("; the user's latest real message was: \"")
                           .append(last).append("\"");
        String asst = clip(lastAssistant, 140);
        if (asst != null) b.append("; you last said: \"").append(asst).append("\"");
        b.append(". The message that follows is only a greeting or test — ")
         .append("answer as the assistant who has been here the whole ")
         .append("time: acknowledge it briefly and offer to continue the ")
         .append("ongoing task. Do not introduce yourself and do not ask ")
         .append("what you can help with.\n</system-reminder>");
        return b.toString();
    }

    /** One-line clip for the block: first line, trimmed, bounded. */
    static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) t = t.substring(0, max - 1) + "…";
        return t;
    }
}
