package ai.opencode.app;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P27 phase 3 — the clipping-audit pins, on real views (Robolectric):
 *
 *   • THE DECK FIX: a project card whose content is taller than the old
 *     fixed height (2-line name + 2-line path + footer — the field shot
 *     with the clipped "Open →" row) must measure into a card height that
 *     FITS it. The deck's shared cardH grows to the tallest natural child,
 *     clamped to the viewport, so every card fits at any font scale.
 *
 *   • The live footer contract, model-side: pin state + selection live in
 *     RunHub, so they survive repaints and re-binds by construction
 *     (toggle pin → simulate a rebind → pin state identical).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class P27UiTest {

    /** A card like HomeActivity.projectCard's worst case: two 2-line text
     *  blocks + divider + footer row. */
    private static LinearLayout tallCard(android.content.Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        TextView tag = new TextView(c); tag.setText("P R O J E C T");
        TextView name = new TextView(c); name.setText("playground with quite a long name");
        name.setMaxLines(2);
        TextView path = new TextView(c);
        path.setText("/storage/emulated/0/opencode-projects/playground-with-a-long-name");
        path.setMaxLines(2);
        View spacer = new View(c);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, 200)); // force tall
        View div = new View(c);
        div.setLayoutParams(new LinearLayout.LayoutParams(1, 2));
        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.addView(new TextView(c)); foot.addView(new TextView(c));
        card.addView(tag); card.addView(name); card.addView(path);
        card.addView(spacer); card.addView(div); card.addView(foot);
        return card;
    }

    @Test
    public void deckCardHeight_growsToFitTallestContent() {
        android.content.Context c = org.robolectric.RuntimeEnvironment
                .getApplication();
        DeckView deck = new DeckView(c);

        LinearLayout tall = tallCard(c);
        LinearLayout shortCard = new LinearLayout(c);
        shortCard.addView(new TextView(c));
        deck.addView(shortCard);
        deck.addView(tall);

        int w = 1080, h = 1800;
        deck.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));

        // natural height of the tall card, measured the same way onMeasure does
        tall.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int natural = tall.getMeasuredHeight();
        int viewport = h; // no padding on this deck
        int cap = (int) Math.min(viewport * 0.72f, w * 0.90f);
        int expected = Math.min(natural, Math.max(cap, Theme.dp(c, 168)));

        // every child was re-measured to the shared EXACTLY height
        assertEquals(expected, shortCard.getMeasuredHeight());
        assertEquals(expected, tall.getMeasuredHeight());
        assertTrue("the tall card's content must FIT its measured height "
                        + "(was the deck clip): " + expected + " >= " + natural,
                expected >= natural || expected == cap);
        if (natural <= cap) {
            assertTrue("content fits fully when within the viewport cap",
                    expected >= natural);
        }
    }

    @Test
    public void livePin_survivesRebind_byConstruction() throws Exception {
        // the pin state lives in RunHub (static) — a repaint or rebind reads
        // the SAME field; pin that reading it twice across a "rebind" (clear
        // of any view-side state) is stable, and toggle semantics are right.
        java.lang.reflect.Field f = RunHub.class.getDeclaredField("liveOpen");
        f.setAccessible(true);
        f.set(null, null);
        assertEquals(false, RunHub.livePinned());
        assertEquals("auto (never interacted) follows heat — cold feed → collapsed",
                false, RunHub.liveExpanded());

        RunHub.toggleLivePin();
        assertEquals(true, RunHub.livePinned());
        assertEquals("pinned stays expanded even cold",
                true, RunHub.liveExpanded());
        RunHub.toggleLivePin();                    // unpin → auto again
        assertEquals(false, RunHub.livePinned());

        // a rebind (the field: "survive every repaint and rebind") cannot
        // change hub state — simulate by re-reading after a state clear of
        // the VIEW layer: nothing view-side touches liveOpen.
        f.set(null, Boolean.TRUE);
        assertEquals(true, RunHub.livePinned());
        f.set(null, null);
    }
}
