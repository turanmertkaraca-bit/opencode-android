package ai.opencode.app;

import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.Shadows;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P24 — the field reproduction, on the JVM: a row whose paint THROWS,
 * inside the REAL ChatActivity, driven through the REAL coalesced flush.
 * <p>
 * The field device ("doesn't crash but it still won't work", Sep 4) ran
 * P23: the batch-level guard contained the process, but ONE poisoned row
 * aborted every flush it rode in on — the feed re-dirtied it on every
 * delta, so the transcript froze behind a wall of containment notes.
 * These tests pin the P24 contract against the actual activity: the
 * poison row fails ALONE, the siblings still paint, and the repeat
 * offender is quarantined into a can't-fail fallback line.
 */
@RunWith(RobolectricTestRunner.class)
public class P24ChatTest {

    /** ChatActivity with one poisonable row key — the field's "bad part". */
    public static class PoisonChat extends ChatActivity {
        String poisonKey;
        int attempts;
        @Override void paintRowOnce(Row r) {
            if (poisonKey != null && poisonKey.equals(r.key)) {
                attempts++;
                throw new IllegalStateException("poison:" + poisonKey);
            }
            super.paintRowOnce(r);
        }
    }

    // ---- reflection seams (private fields, same-package test) ----------

    @SuppressWarnings("unchecked")
    private static List<ChatActivity.Row> rows(ChatActivity a) throws Exception {
        Field f = ChatActivity.class.getDeclaredField("rows");
        f.setAccessible(true);
        return (List<ChatActivity.Row>) f.get(a);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> idx(ChatActivity a) throws Exception {
        Field f = ChatActivity.class.getDeclaredField("idxByKey");
        f.setAccessible(true);
        return (Map<String, Integer>) f.get(a);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> quarantined(ChatActivity a) throws Exception {
        Field f = ChatActivity.class.getDeclaredField("quarantined");
        f.setAccessible(true);
        return (Set<String>) f.get(a);
    }

    private static LinearLayout list(ChatActivity a) throws Exception {
        Field f = ChatActivity.class.getDeclaredField("list");
        f.setAccessible(true);
        return (LinearLayout) f.get(a);
    }

    private static ChatActivity.Row row(int kind, String key, String text) {
        ChatActivity.Row r = new ChatActivity.Row();
        r.kind = kind;
        r.key = key;
        r.text.append(text);
        r.shown = 0;
        return r;
    }

    // ---- the pins -------------------------------------------------------

    @Test
    public void poisonRow_doesNotBlockSiblingsInTheSameFlush() throws Exception {
        try (ActivityController<PoisonChat> ctl =
                     Robolectric.buildActivity(PoisonChat.class)) {
            PoisonChat a = ctl.setup().get();
            a.poisonKey = "msgA|bad";

            List<ChatActivity.Row> rows = rows(a);
            rows.add(row(ChatActivity.K_USER, "u1", "hello"));
            rows.add(row(ChatActivity.K_ASSISTANT, "msgA|bad", "poison"));
            rows.add(row(ChatActivity.K_ASSISTANT, "msgA|good", "world"));
            idx(a).put("u1", 0);
            idx(a).put("msgA|bad", 1);
            idx(a).put("msgA|good", 2);

            a.requestPaint(rows.get(0));
            a.requestPaint(rows.get(1));
            a.requestPaint(rows.get(2));
            a.flushPaintsNow();          // direct: synchronous by design

            LinearLayout list = list(a);
            assertEquals("all THREE rows hold views — the poison row must not"
                            + " abort the batch (P23 field freeze)",
                    3, list.getChildCount());
            assertEquals("poison attempted exactly once per flush — no loops",
                    1, a.attempts);

            // the sibling AFTER the poison row actually shows its text
            TextView body = (TextView) ((LinearLayout) list.getChildAt(2)).getChildAt(0);
            assertTrue("the row behind the poison must be painted",
                    body.getText().toString().contains("world"));
        }
    }

    @Test
    public void poisonRow_quarantinedAfterSecondFailure_chatKeepsWorking() throws Exception {
        try (ActivityController<PoisonChat> ctl =
                     Robolectric.buildActivity(PoisonChat.class)) {
            PoisonChat a = ctl.setup().get();
            a.poisonKey = "msgB|bad";

            List<ChatActivity.Row> rows = rows(a);
            rows.add(row(ChatActivity.K_ASSISTANT, "msgB|bad", "poison"));
            rows.add(row(ChatActivity.K_ASSISTANT, "msgB|ok", "fine"));
            idx(a).put("msgB|bad", 0);
            idx(a).put("msgB|ok", 1);

            // strike 1: fails, second chance granted
            a.requestPaint(rows.get(0));
            a.requestPaint(rows.get(1));
            a.flushPaintsNow();
            assertEquals("no quarantine after ONE failure",
                    false, quarantined(a).contains("msgB|bad"));
            assertEquals(1, a.attempts);

            // strike 2: quarantine
            a.requestPaint(rows.get(0));
            a.flushPaintsNow();
            Shadows.shadowOf(Looper.getMainLooper()).idle();  // swap runs posted
            assertTrue("quarantined after 2 failures",
                    quarantined(a).contains("msgB|bad"));
            assertEquals("row kind swapped to the sys pill",
                    ChatActivity.K_SYS, rows.get(0).kind);
            assertTrue("content swapped for the fallback line",
                    rows.get(0).text.toString()
                            .contains("could not be displayed"));

            // the fallback itself rendered (poison skipped, no throw)
            LinearLayout list = list(a);
            assertEquals(2, list.getChildCount());
            TextView pill = (TextView) list.getChildAt(0);
            assertTrue(pill.getText().toString()
                    .contains("could not be displayed"));

            // strike 3 (would-be): quarantined rows are never painted again
            a.requestPaint(rows.get(0));
            a.flushPaintsNow();
            assertEquals("no paint attempts after quarantine",
                    2, a.attempts);

            // the healthy sibling is untouched by all of this
            TextView ok = (TextView) ((LinearLayout) list.getChildAt(1)).getChildAt(0);
            assertTrue(ok.getText().toString().contains("fine"));
        }
    }

    @Test
    public void emptyFlush_isANoop() throws Exception {
        try (ActivityController<PoisonChat> ctl =
                     Robolectric.buildActivity(PoisonChat.class)) {
            PoisonChat a = ctl.setup().get();
            a.poisonKey = null;
            a.flushPaintsNow();          // must not throw, must not paint
            assertEquals(0, list(a).getChildCount());
        }
    }
}
