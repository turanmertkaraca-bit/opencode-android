package ai.opencode.app;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P10 SELF-TEST — the "allow / always / deny buttons don't work" regression.
 *
 * Root cause (found by code audit): permission replies ran on the same
 * single-thread executor as POST /session/{id}/message, which blocks until
 * the agent run finishes — a permission ask always arrives mid-run, so the
 * reply task waited behind a stuck socket and never executed.
 *
 * This test drives the REAL pipeline on the JVM: a permission.asked event
 * enters ServerService through ingest() (the same method the SSE reader
 * calls), ChatActivity renders the approval card, the Allow button is
 * clicked, and the mock opencode server on 127.0.0.1:4096 must receive
 *   POST /permission/{requestID}/reply  {"reply":"once"}
 * within a short deadline — proving replies fire while a message POST
 * would still be in flight.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PermissionFlowTest {

    private OcTestServer server;

    @Before
    public void setUp() throws Exception {
        server = new OcTestServer();
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    private static ShadowLooper mainLooper() {
        return Shadows.shadowOf( android.os.Looper.getMainLooper());
    }

    /** Reflective helper: run ServerService.ingest (the SSE frame sink). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void ingest(Map<String, Object> ev) throws Exception {
        android.app.Service svc = Robolectric.setupService(ServerService.class);
        Method m = ServerService.class.getDeclaredMethod("ingest", Map.class);
        m.setAccessible(true);
        m.invoke(svc, ev);
    }

    @Test
    public void allowButtonRepliesOnceWhileMessagePostWouldBeInFlight() throws Exception {
        ChatActivity act = Robolectric.buildActivity(ChatActivity.class).setup().get();
        mainLooper().idle();

        // the agent asks for permission (same shape the server emits)
        Map asked = Json.obj(Json.parse(
                "{\"type\":\"permission.asked\",\"properties\":{"
                        + "\"id\":\"req-test-1\",\"sessionID\":\"ses-1\","
                        + "\"permission\":\"bash\","
                        + "\"patterns\":[\"npm test*\"],"
                        + "\"metadata\":{\"title\":\"Run tests\","
                        + "\"command\":\"npm test\"}}}"));
        ingest(asked);
        mainLooper().idle();

        // the approval card must be visible with three buttons
        ViewGroup slot = act.findViewById(R.id.permSlot);
        assertNotNull("permission card missing", slot);
        assertEquals("permission card must show", View.VISIBLE, slot.getVisibility());
        assertEquals("card must be attached", 1, slot.getChildCount());
        TextView allow = findButton(slot, "Allow");
        TextView always = findButton(slot, "Always allow");
        TextView deny = findButton(slot, "Deny");
        assertNotNull("Allow button missing", allow);
        assertNotNull("Always allow button missing", always);
        assertNotNull("Deny button missing", deny);

        // tap Allow → reply must reach the server promptly (its own pool)
        allow.performClick();

        OcTestServer.Hit reply = awaitHit("/permission/req-test-1/reply", 5000);
        assertNotNull("no reply POST arrived — buttons are dead again", reply);
        assertEquals("POST", reply.method);
        assertEquals("{\"reply\":\"once\"}", reply.body);

        // the card must be dismissed after the successful reply
        mainLooper().idle();
        assertEquals("card should disappear after replying",
                View.GONE, slot.getVisibility());
        act.finish();
    }

    @Test
    public void denyAndAlwaysUseVerifiedReplyValues() throws Exception {
        ChatActivity act = Robolectric.buildActivity(ChatActivity.class).setup().get();
        mainLooper().idle();

        ingest(Json.obj(Json.parse(
                "{\"type\":\"permission.v2.asked\",\"properties\":{"
                        + "\"id\":\"req-test-2\",\"sessionID\":\"ses-1\","
                        + "\"permission\":\"edit\"}}")));
        mainLooper().idle();
        ViewGroup slot = act.findViewById(R.id.permSlot);
        assertEquals(View.VISIBLE, slot.getVisibility());

        findButton(slot, "Deny").performClick();
        OcTestServer.Hit deny = awaitHit("/permission/req-test-2/reply", 5000);
        assertNotNull(deny);
        assertEquals("{\"reply\":\"reject\"}", deny.body);

        mainLooper().idle();
        ingest(Json.obj(Json.parse(
                "{\"type\":\"permission.asked\",\"properties\":{"
                        + "\"id\":\"req-test-3\",\"sessionID\":\"ses-1\","
                        + "\"permission\":\"bash\"}}")));
        mainLooper().idle();
        slot = act.findViewById(R.id.permSlot);
        findButton(slot, "Always allow").performClick();
        OcTestServer.Hit always = awaitHit("/permission/req-test-3/reply", 5000);
        assertNotNull(always);
        assertEquals("{\"reply\":\"always\"}", always.body);

        mainLooper().idle();
        act.finish();
    }

    // ------------------------------------------------------------ helpers

    private OcTestServer.Hit awaitHit(String path, long deadlineMs) throws Exception {
        long end = System.currentTimeMillis() + deadlineMs;
        while (System.currentTimeMillis() < end) {
            List<OcTestServer.Hit> hs = server.hitsFor(path);
            if (!hs.isEmpty()) return hs.get(hs.size() - 1);
            Thread.sleep(25);
        }
        return null;
    }

    private static TextView findButton(ViewGroup root, String label) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v instanceof TextView && label.equals(((TextView) v).getText().toString())) {
                return (TextView) v;
            }
            if (v instanceof ViewGroup) {
                TextView r = findButton((ViewGroup) v, label);
                if (r != null) return r;
            }
        }
        return null;
    }
}
