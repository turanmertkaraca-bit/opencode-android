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
 * P42 — THE FIELD REPORT THAT REWROTE THIS NOTE: the agent burned a
 * whole session "fixing its environment" because the old note LIED to
 * it. It claimed the shell was always a Debian guest (false in the
 * default native mode), claimed GH_TOKEN was exported (guest-only),
 * and told the agent to run "apt-get install ca-certificates" when
 * https failed — the exact probe loop that wasted real money. The note
 * is now mode-aware, and every claim is true for the mode it describes:
 *
 *   - the TLS paragraph states the CA bundle EXISTS and is exported
 *     (P42 ships it) and names certificate-fixing as a non-step;
 *   - the network paragraph states raw DNS cannot work BY DESIGN and
 *     names the one real remedy (DNS bridge) instead of hosts hacks;
 *   - an anti-loop paragraph tells the agent, in plain words, that the
 *     map is complete and re-probing wastes the user's money.
 *
 * Pure text in, pure text out: the note NEVER contains the token itself,
 * only its name and what it is for. When no token exists the note says
 * that plainly, so the agent stops probing and can tell the user exactly
 * where to add one.
 */
public final class EnvNote {

    /** The one repo the agent-side token is scoped to. */
    public static final String REPO = "turanmertkaraca-bit/opencode-android";

    /** Which environment the note should describe. */
    public enum Mode { LITE, ALPINE, DEBIAN }

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

    /** The mode the agent's bash actually lands in today: the Debian
     *  launcher when the guest is installed, else the native shell (the
     *  Alpine toolkit is an add-on ON that native shell, noted inline). */
    public static Mode mode(Context c) {
        try {
            if (Debian.active(c)) return Mode.DEBIAN;
        } catch (Exception ignored) {}
        try {
            if (Sandbox.ready(c)) return Mode.ALPINE;
        } catch (Exception ignored) {}
        return Mode.LITE;
    }

    /** Mode-aware entry point (P42). RunHub calls this. */
    public static String note(Context c) {
        return note(mode(c), ghConfigured(c), Sandbox.ready(c));
    }

    /** The Debian-guest variant — the same text the P37 field run was
     *  shown, corrected by P42 (CA bundle preseeded, honest bootstrap,
     *  no more "fix certs with apt" advice). */
    public static String note(boolean ghSet) {
        return note(Mode.DEBIAN, ghSet, false);
    }

    /** The note. The GitHub paragraph is honest in BOTH states: present
     *  (scoped, what it is for, never print it) or absent (pushes will
     *  fail; where the user adds one). */
    public static String note(Mode m, boolean ghSet, boolean alpineReady) {
        StringBuilder s = new StringBuilder();
        s.append("<system-reminder>\n");
        if (m == Mode.DEBIAN) {
            s.append("Environment: your shell (bash) runs inside a Debian ");
            s.append("guest; its /root is private to the guest. The ");
            s.append("read/edit/write tools run on the Android host and ");
            s.append("only see the project folder (the session cwd) — ");
            s.append("keep files both sides need inside the project ");
            s.append("folder. git and ca-certificates are installed by ");
            s.append("the guest bootstrap, and a CA bundle is preseeded ");
            s.append("before the first apt call, so https works ");
            s.append("immediately. If a /storage/emulated/0 path does not ");
            s.append("exist, the mount is not ready — use the project ");
            s.append("folder instead of probing mounts.\n");
        } else {
            s.append("Environment: your shell runs NATIVELY on Android ");
            s.append("(mksh plus a busybox toolset) — there is no Linux ");
            s.append("distro here: no apt, no apt-get, no gh, and no ");
            s.append("preinstalled curl or git. HOME is app-private. The ");
            s.append("read/edit/write tools run on the same host and see ");
            s.append("the project folder (the session cwd) — keep files ");
            s.append("both sides need inside the project folder.\n");
            if (m == Mode.ALPINE) {
                s.append("A musl Alpine toolkit is installed: ");
                s.append("`pkg search <tool>` / `pkg install <tool>` adds ");
                s.append("real packages (curl, python, …) and puts them ");
                s.append("on PATH via wrappers.\n");
            }
        }
        // P42 TLS truth — one paragraph for every mode.
        s.append("TLS: a CA bundle is provisioned and exported for you — ");
        s.append("SSL_CERT_FILE, CURL_CA_BUNDLE, GIT_SSL_CAINFO, ");
        s.append("REQUESTS_CA_BUNDLE and PIP_CERT all point at it, so ");
        s.append("curl, git, pip and friends verify https out of the box. ");
        s.append("You never need to install or repair certificates; ");
        s.append("apt-get install ca-certificates is NOT a step here.\n");
        // P42 network truth — raw DNS is dead by design; name the remedy.
        s.append("Network: there is no /etc/resolv.conf and raw DNS ");
        s.append("(ping, nslookup, host, name-resolving wget) cannot ");
        s.append("work by design — every lookup rides the in-app proxy. ");
        s.append("When http_proxy/https_proxy are set in your env, https ");
        s.append("works through them (NO_PROXY already exempts the local ");
        s.append("server). If they are unset and a task needs https, ");
        s.append("tell the user once: Diagnostics → DNS bridge ON → ");
        s.append("restart server. Do not debug DNS; it is intentionally ");
        s.append("not a normal Linux network.\n");
        // P42 anti-loop paragraph — the money saver.
        s.append("If an environment probe fails twice, stop probing: ");
        s.append("report what is missing to the user and continue the ");
        s.append("task another way. This sandbox is intentionally ");
        s.append("minimal — the map in this note is complete, and ");
        s.append("re-probing it wastes the user's money.\n");
        // P42: the silent-fallback confession (set by ServerService).
        String fb = ServerService.servingFallback;
        if (fb != null) {
            s.append("Sandbox root: the chosen project folder (").append(fb);
            s.append(") is not accessible this boot (storage not ready or ");
            s.append("removed) — the sandbox is serving the app's private ");
            s.append("home instead. Writes to the project path or to ");
            s.append("/storage/emulated/0 will fail until the user ");
            s.append("reopens the project from the deck; work in the ");
            s.append("current cwd and say so in your first reply.\n");
        }
        if (ghSet) {
            if (m == Mode.DEBIAN) {
                s.append("GitHub: GH_TOKEN is exported in your shell, ");
                s.append("scoped to ").append(REPO).append(" only — it is ");
                s.append("there to be used for git clone/commit/push of ");
                s.append("that repo and for GitHub API calls about it. A ");
                s.append("credential helper is preinstalled, so plain ");
                s.append("git push to https://github.com/").append(REPO);
                s.append(" works. Never print the token.\n");
            } else {
                s.append("GitHub: a token scoped to ").append(REPO);
                s.append(" is stored in the app. It is NOT exported in ");
                s.append("this native shell; inside the Debian guest (if ");
                s.append("installed) it is exported as GH_TOKEN with a ");
                s.append("preinstalled credential helper, so plain git ");
                s.append("push works there. Never print the token.\n");
            }
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
