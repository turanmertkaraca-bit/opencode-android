package ai.opencode.app;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P28 — the two field fixes, pinned:
 *
 *   • THE DEAD LINK FIX. P27 rendered accent+underline mention links but
 *     nothing ever fired their ClickableSpans (the transcript rows have no
 *     movement method — the field's "clicking on these blue file links
 *     does nothing"). The fix routes taps by hit-testing; its layers are
 *     pinned where each layer is faithful:
 *       — spanNear(): the span finder (exact → ±1 → equal-x tie walk) as
 *         pure logic. Robolectric's emulated font metrics are DEGENERATE
 *         for x→offset mapping (measured on this rig: tapX=6 resolved to
 *         offset 0, tapX=15 to offset 29), so a dispatch-level positive
 *         through real text layout would pin the emulator's mood, not the
 *         app — the positive lives here instead.
 *       — the touch listener: REAL dispatch proves the negatives that hold
 *         on any font — a tap on plain text is never consumed, a drag
 *         across a link never fires one.
 *
 *   • THE DRIFTED TYPING DOTS. The P27 transcript FrameLayout broke the
 *     dots' runtime parenting math (indexOfChild returned −1, clamped to
 *     0 → the dots became a floating transcript overlay — "thinking
 *     animation is in the middle"). The slot is DECLARED in the layout
 *     now; this test pins it to the root column above the composer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class P28UiTest {

    private static final String MSG = "see probe-1.txt for the burst";
    private static final int SPAN_S = 4, SPAN_E = 15;

    private static SpannableStringBuilder linkedText() {
        SpannableStringBuilder b = new SpannableStringBuilder(MSG);
        b.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) { }
            @Override public void updateDrawState(android.text.TextPaint ds) { }
        }, SPAN_S, SPAN_E, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    // --------------------------------------------------- spanNear (the finder)

    @Test
    public void spanNear_findsTheLink_exactAndOneOff() {
        SpannableStringBuilder b = linkedText();
        java.util.function.IntPredicate noTies = i -> false;
        assertEquals(1, Markdown.spanNear(b, 9, noTies).length);   // exact
        assertEquals(1, Markdown.spanNear(b, 5, noTies).length);   // exact
        assertEquals(1, Markdown.spanNear(b, 15, noTies).length);  // +1 probe
        assertEquals(1, Markdown.spanNear(b, 3, noTies).length);   // −1 probe
        assertEquals(0, Markdown.spanNear(b, 1, noTies).length);   // plain head
        assertEquals(0, Markdown.spanNear(b, 22, noTies).length);  // plain tail
    }

    @Test
    public void spanNear_tieWalk_reachesLinksSharingOneVisualPoint() {
        SpannableStringBuilder b = linkedText();
        // a link 16..21 plus an equal-x run 16..25 (one visual point):
        // a resolved offset of 24 sits ON that point — walking it must
        // reach the link, while a strict ±1 (no ties) must not.
        b.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) { }
        }, 16, 21, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        java.util.function.IntPredicate tie = i -> i >= 16 && i <= 25;
        assertEquals(1, Markdown.spanNear(b, 24, tie).length);
        assertEquals(0, Markdown.spanNear(b, 24,
                (java.util.function.IntPredicate) i -> false).length);
    }

    // ------------------------------------------- dispatch-level touch routing

    private static TextView mentionView(AtomicReference<String> opened) {
        android.content.Context c = org.robolectric.RuntimeEnvironment
                .getApplication();
        TextView tv = new TextView(c);
        Markdown.MentionResolver mr = new Markdown.MentionResolver() {
            @Override public String resolve(String candidate) {
                return "/proj/" + candidate;      // existence is the app's gate
            }
            @Override public void open(String abs) { opened.set(abs); }
        };
        CharSequence cs = Markdown.render(
                "see `probe-1.txt` for the burst", mr);
        assertTrue(Markdown.hasLinks(cs));
        tv.setText(cs);
        Markdown.enableSpanTaps(tv);
        tv.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        tv.layout(0, 0, 480, tv.getMeasuredHeight());
        return tv;
    }

    private static boolean tap(TextView tv, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        tv.dispatchTouchEvent(MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, x, y, 0));
        return tv.dispatchTouchEvent(MotionEvent.obtain(now, now + 60,
                MotionEvent.ACTION_UP, x, y, 0));
    }

    @Test
    public void tapOnPlainText_isNeverConsumedOrFired() {
        AtomicReference<String> opened = new AtomicReference<>(null);
        TextView tv = mentionView(opened);
        boolean consumed = tap(tv, 8f, tv.getHeight() - 2f);
        assertNull("plain text never opens the viewer", opened.get());
        assertTrue("unlinked area stays with the view (selection etc.)",
                !consumed);
    }

    @Test
    public void dragAcrossMention_neverFires() {
        AtomicReference<String> opened = new AtomicReference<>(null);
        TextView tv = mentionView(opened);
        long now = android.os.SystemClock.uptimeMillis();
        tv.dispatchTouchEvent(MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, 8f, 12f, 0));
        boolean consumed = tv.dispatchTouchEvent(MotionEvent.obtain(now,
                now + 300, MotionEvent.ACTION_UP, 128f, 92f, 0));
        assertNull("a scroll drag must never open the viewer", opened.get());
        assertTrue(!consumed);
    }

    // -------------------------------------------------------- typing slot

    @Test
    public void typingSlot_declaredAboveComposer_neverInTheTranscript() {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            LinearLayout typing = a.findViewById(R.id.typingSlot);
            assertNotNull("typingSlot must exist in activity_chat.xml", typing);
            ViewGroup root = (ViewGroup) typing.getParent();
            ViewGroup content = a.findViewById(android.R.id.content);
            ViewGroup rootExpected = (ViewGroup) content.getChildAt(0);
            assertEquals("the dots live in the root column",
                    rootExpected, root);
            int composerIdx = rootExpected.indexOfChild(
                    a.findViewById(R.id.composerBar));
            int typingIdx = rootExpected.indexOfChild(typing);
            assertTrue("dots sit ABOVE the composer",
                    typingIdx >= 0 && typingIdx < composerIdx);
            assertEquals("populated by buildTyping: three dots + label",
                    4, typing.getChildCount());
            assertEquals("thinking…", ((TextView) typing.getChildAt(3))
                    .getText().toString());
            assertEquals("hidden until the hub reports busy",
                    View.GONE, typing.getVisibility());
        }
    }

    // -------------------------------------------------- the peek (view side)

    @Test
    public void stylePeek_wrapsTheFocusLine_andContentCompares() {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            String peek = "  … 3 lines above\n  4│ alpha\n▸ 5│ beta now\n  6│ gamma";
            CharSequence s1 = a.stylePeek(peek);
            assertTrue("styled form is a Spannable",
                    s1 instanceof android.text.Spanned);
            android.text.Spanned sp = (android.text.Spanned) s1;
            android.text.style.BackgroundColorSpan[] bg =
                    sp.getSpans(0, s1.length(),
                            android.text.style.BackgroundColorSpan.class);
            assertEquals("exactly one highlighted range", 1, bg.length);
            assertEquals("the range is the ▸ line",
                    "▸ 5│ beta now", s1.subSequence(
                            sp.getSpanStart(bg[0]), sp.getSpanEnd(bg[0])).toString());
            // unchanged content is content-equal — the production guard
            // (String.contentEquals) skips identical re-setText even though
            // SpannableString.equals is identity-based
            CharSequence s2 = a.stylePeek(peek);
            assertTrue("two stylings have equal content",
                    s2.toString().contentEquals(s1));
        }
    }
}
