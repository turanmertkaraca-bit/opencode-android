package ai.opencode.app;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * P19 "the crash killer" regression suite — the field evidence:
 *  1. whole-app process death left the orphaned server child holding
 *     port 4096, every respawn died EADDRINUSE, recovery needed a cold
 *     boot of the PHONE ("the chat and sandbox shuts off").
 *     → ephemeral port + listen-banner adoption + orphan sweep.
 *  2. the busy watchdog's 3.5 s quiet threshold murdered the live-edit
 *     shower during every silent tool run ("live file edits dont show
 *     in chat").
 *  3. nothing was ever recorded for whole-process deaths — the diag
 *     heartbeat now leaves a trace.
 */
public class P19Test {

    // ------------------------------------------------- listen-banner parse

    @Test
    public void parseListenPort_upstreamBanner() {
        // exact line shape seen from v1.18.25 on the test rig
        assertEquals(14096, Api.parseListenPort(
                "opencode server listening on http://127.0.0.1:14096"));
    }

    @Test
    public void parseListenPort_ephemeralAndWrapped() {
        assertEquals(41234, Api.parseListenPort(
                "Warning: OPENCODE_SERVER_PASSWORD is not set; server is unsecured.\n"
                        + "opencode server listening on http://127.0.0.1:41234"));
        assertEquals(80, Api.parseListenPort("listening on http://0.0.0.0:80"));
    }

    @Test
    public void parseListenPort_garbageIsZero() {
        assertEquals(0, Api.parseListenPort(null));
        assertEquals(0, Api.parseListenPort(""));
        assertEquals(0, Api.parseListenPort("opencode server listening on http://127.0.0.1:"));
        assertEquals(0, Api.parseListenPort("listening on http://127.0.0.1:port"));
        assertEquals(0, Api.parseListenPort("random server output line"));
        assertEquals(0, Api.parseListenPort("listening on http://127.0.0.1:0")); // port 0 invalid
        assertEquals(0, Api.parseListenPort("listening on http://127.0.0.1:99999")); // out of range
    }

    // ------------------------------------------------- port freedom (P19)

    @Test
    public void pickFreePort_prefersDefaultWhenFree() throws Exception {
        assertEquals(4096, Resilience.pickFreePort(4096));
    }

    @Test
    public void pickFreePort_movesWhenDefaultIsTaken() throws Exception {
        // occupy the preferred port the way a wedged orphan would
        java.net.ServerSocket squatter = new java.net.ServerSocket(45555);
        try {
            int got = Resilience.pickFreePort(45555);
            assertTrue("must not return the taken port", got != 45555);
            assertTrue("must return a usable port", got > 0);
            // and the returned port must actually be bindable
            java.net.ServerSocket verify = new java.net.ServerSocket(got);
            verify.close();
        } finally {
            squatter.close();
        }
    }

    // ------------------------------------------------------- orphan match

    @Test
    public void ocCmdline_exactArgv0Only() {
        String bin = "/data/user/0/ai.opencode.app/files/opencode";
        assertTrue(Resilience.isOcCmdline(bin, bin));
        assertTrue(Resilience.isOcCmdline(bin + "\0serve\0--port\00", bin));
        // prefix of a similarly-named path must NOT match
        assertFalse(Resilience.isOcCmdline(bin + ".old", bin));
        assertFalse(Resilience.isOcCmdline(bin + "-staging\0serve", bin));
        // a different app's binary must NOT match
        assertFalse(Resilience.isOcCmdline(
                "/data/user/0/com.other.app/files/opencode\0serve", bin));
        assertFalse(Resilience.isOcCmdline(null, bin));
        assertFalse(Resilience.isOcCmdline(bin, null));
        assertFalse(Resilience.isOcCmdline("", bin));
    }

    // ------------------------------------------------------ watchdog quiet

    @Test
    public void quietEnd_matchesPostBudget_andBeatsP18Bug() {
        long q = Resilience.quietEndMs();
        // P18's 3.5 s killed busy mid-tool-run; P19 must be minutes, not seconds
        assertTrue("quiet threshold too short: " + q, q >= 300_000);
        // and it must not outlive the POST read budget by much (10 min = 900s/1.5)
        assertTrue("quiet threshold absurdly long: " + q, q <= 900_000);
    }

    // --------------------------------------------------------- port adopt

    @Test
    public void setPort_clampsAndApplies() {
        int before = Api.PORT;
        try {
            Api.setPort(45123);
            assertEquals(45123, Api.PORT);
            assertTrue(Api.baseUrl().endsWith(":45123"));
            Api.setPort(0);            // invalid → ignored
            assertEquals(45123, Api.PORT);
            Api.setPort(70000);        // invalid → ignored
            assertEquals(45123, Api.PORT);
        } finally {
            Api.setPort(before);
        }
        assertEquals(before, Api.PORT);
    }

    // ---------------------------------------------- edit-shower UX policy

    @Test
    public void emptySettledFeed_hasNoSummary() {
        // the ghost-row rule: settled + zero edits → the row must render as
        // nothing; EditPulse.summary on an empty feed must say 0 files
        java.util.Map<String, EditPulse.Ev> feed =
                new java.util.HashMap<>();
        assertTrue(EditPulse.summary(feed).startsWith("0 edits"));
        assertFalse(EditPulse.hot(feed, System.currentTimeMillis()));
    }

    @Test
    public void freshEdit_recorForFeed() {
        java.util.Map<String, EditPulse.Ev> feed = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        EditPulse.record(feed, "/proj", "/proj/src/App.tsx", "mod", now);
        assertEquals(1, feed.size());
        assertTrue(EditPulse.hot(feed, now + 1000));
        assertFalse(EditPulse.hot(feed, now + EditPulse.ACTIVE_MS + 1000));
    }

    // ------------------------------------------------------- diag heartbeat

    @Test
    public void heartbeatLine_isGreppable() {
        String l = Resilience.diagLine(1690000000000L, "hb", "server up · :4096", 3_000_000L);
        assertTrue(l.contains(" hb "));
        assertTrue(l.contains("server up · :4096"));
        assertTrue(l.contains("memAvail=3000000kB"));
        // multiline details can't corrupt the one-line format
        String j = Resilience.diagLine(1L, "hb", "a\nb", 2L);
        assertFalse(j.contains("\n"));
    }
}
