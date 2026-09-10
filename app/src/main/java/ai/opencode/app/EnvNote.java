package ai.opencode.app;

import android.content.Context;

/**
 * P37 — the environment note. The field reports: the agent did not know
 * whether the GitHub token was fair game, probed git and gh blindly, and
 * wrote files into the Debian guest's /root that the HOST read tool then
 * could not see. The map of the place, delivered once per session as a
 * system-reminder riding the first user message — the P30/P35 ride
 * pattern, never an AGENTS.md file (the P30 lesson: they leak into git
 * and go stale mid-session).
 *
 * Pure text in, pure text out: the note NEVER contains the token itself,
 * only its name and what it is for. When no token exists the note says
 * that plainly, so the agent stops probing and can tell the user exactly
 * where to add one.
 */
public final class EnvNote {

    /** The one repo the agent-side token is scoped to. */
    public static final String REPO = "turanmertkaraca-bit/opencode-android";

    private EnvNote() {}

    /** Absent = this session never heard the map → the next send carries
     *  it. TRUE = already delivered. (No armed state — the env note rides
     *  the first send of every session, period.) */
    public static boolean needsNote(Boolean told) {
        return told == null;
    }

    /** TRUE = the keys screen holds an agent GitHub token. */
    public static boolean ghConfigured(Context c) {
        try {
            String t = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
                    .getString("gh_token", null);
            return t != null && !t.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** The note. The GitHub paragraph is honest in BOTH states: present
     *  (scoped, what it is for, never print it) or absent (pushes will
     *  fail; where the user adds one). */
    public static String note(boolean ghSet) {
        StringBuilder s = new StringBuilder();
        s.append("<system-reminder>\n");
        s.append("Environment: shell commands run inside a Debian guest; ");
        s.append("its /root is private to the guest. The read/edit/write ");
        s.append("tools run on the Android host and only see the project ");
        s.append("folder (the session cwd) — keep files both sides need ");
        s.append("inside the project folder. git and ca-certificates are ");
        s.append("installed in the guest; if https still fails on certs, ");
        s.append("run apt-get install -y ca-certificates first.\n");
        if (ghSet) {
            s.append("GitHub: GH_TOKEN is exported in your shell, scoped to ");
            s.append(REPO).append(" only — it is there to be used for git ");
            s.append("clone/commit/push of that repo and for GitHub API ");
            s.append("calls about it. A credential helper is preinstalled, ");
            s.append("so plain git push to https://github.com/").append(REPO);
            s.append(" works. Never print the token.\n");
        } else {
            s.append("GitHub: no token is configured in this app, so git ");
            s.append("push will fail — if pushing is needed, tell the user ");
            s.append("to add one under the keys screen (Agent GitHub ");
            s.append("access) instead of retrying.\n");
        }
        s.append("</system-reminder>");
        return s.toString();
    }
}
