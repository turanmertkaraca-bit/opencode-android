package ai.opencode.app;

import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/**
 * P10 SELF-TEST — the deck gesture regression. User report: "it needs to
 * be slide very slowly or it goes back, people who slide fast cant open
 * the below card."
 *
 * The old snap projected velocity*0.14 s and rounded, so quick flicks
 * rounded back to the current page. The new rule: a real fling advances
 * exactly one card from the gesture's anchor page, in the flick direction.
 * These tests drive snapAfterDrag() directly and assert the Scroller's
 * FINAL target (post-animation position), no timing involved.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DeckSnapTest {

    private DeckView deck;
    private int stride;

    private void build() throws Exception {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        deck = new DeckView(c);
        int w = 1080, h = 2340;
        for (int i = 0; i < 3; i++) deck.addView(new View(c));
        deck.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        deck.layout(0, 0, w, h);
        Method st = DeckView.class.getDeclaredMethod("stride");
        st.setAccessible(true);
        stride = (Integer) st.invoke(deck);
    }

    private void anchor(int page) throws Exception {
        Field f = DeckView.class.getDeclaredField("gestureStartPage");
        f.setAccessible(true);
        f.setInt(deck, page);
        Field fs = DeckView.class.getDeclaredField("flingSeen");
        fs.setAccessible(true);
        fs.setBoolean(deck, false);
    }

    /** Simulate: dragged to scrollY, released with velocity vy. */
    private void snap(int scrollY, float vy) throws Exception {
        deck.scrollTo(0, scrollY);
        anchor(deck.page());
        Method m = DeckView.class.getDeclaredMethod("snapAfterDrag", float.class);
        m.setAccessible(true);
        m.invoke(deck, vy);
    }

    private int snapTarget() throws Exception {
        Field f = DeckView.class.getDeclaredField("scroller");
        f.setAccessible(true);
        android.widget.Scroller sc = (android.widget.Scroller) f.get(deck);
        return sc.getFinalY();
    }

    @Test
    public void fastFlingAdvancesToNextCard() throws Exception {
        build();
        // the exact complaint: fast, short flick — barely 20% into the page
        snap((int) (0.2 * stride), -3200f);
        assertEquals("fast fling must land on card 1",
                1 * stride, snapTarget());
    }

    @Test
    public void fastFlingBackGoesToPreviousCard() throws Exception {
        build();
        deck.scrollTo(0, stride); // sit on card 1
        anchor(1);
        snap(stride + (int) (0.15 * stride), 2800f); // flick down
        assertEquals("fast fling down must land on card 0",
                0, snapTarget());
    }

    @Test
    public void slowHalfDragCommits() throws Exception {
        build();
        snap((int) (0.8 * stride), -120f); // slow drag, 80% of the way
        assertEquals(1 * stride, snapTarget());
    }

    @Test
    public void smallSlowNudgeSettlesBack() throws Exception {
        build();
        snap((int) (0.2 * stride), -120f); // slow, not past half
        assertEquals(0, snapTarget());
    }

    @Test
    public void flingClampsAtLastCard() throws Exception {
        build();
        deck.scrollTo(0, 2 * stride); // last card
        anchor(2);
        snap(2 * stride - (int) (0.2 * stride), -4000f);
        assertEquals("cannot fling past the end",
                2 * stride, snapTarget());
    }
}
