package ai.opencode.app;

import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * P31 — the long-press fix, pinned at the UI layer. The field report:
 * "long press to delete doesn't work, it just opens the chat". Root
 * cause: the deck's GestureDetector never sees a stationary hold on a
 * card (the clickable child consumes the touch stream), so the ONLY
 * reliable long-press is the card's own native one. These pins make
 * sure it stays that way.
 */
@RunWith(RobolectricTestRunner.class)
public class P31UiTest {

    @Test
    public void projectCards_ownTheirLongPress_andOpenTheMenu() throws Exception {
        try (ActivityController<HomeActivity> ctl =
                     Robolectric.buildActivity(HomeActivity.class)) {
            HomeActivity a = ctl.setup().get();
            invokeInit(a);                       // force the deck build

            ViewGroup deck = deckOf(a);
            assertTrue("deck has at least one card", deck.getChildCount() >= 1);
            View card = deck.getChildAt(0);

            // THE PIN: the card itself carries a long-click listener. The
            // old bug shipped exactly this assertion as false.
            // hasOnLongClickListeners() is not in the android-all stub —
            // the CONSUMED result is the honest signal: a card without a
            // long-click listener would return false and fall through to
            // the tap-open path (the field bug, exactly).
            assertTrue("long press must be consumed by the card",
                    card.performLongClick());

            // long-press opens the ACTIONS menu (Open/Rename/Remove/Delete)
            // and NOT the chat
            Dialog dlg = (Dialog) ShadowDialog.getLatestDialog();
            assertNotNull("long press must open the actions dialog", dlg);
            assertTrue(dlg.isShowing());
        }
    }

    @Test
    public void deckCallback_survivesAsTheGapFallback() throws Exception {
        try (ActivityController<HomeActivity> ctl =
                     Robolectric.buildActivity(HomeActivity.class)) {
            HomeActivity a = ctl.setup().get();
            invokeInit(a);                       // setCallback lives in buildDeck
            // the Deck callback (gap/padding long-press) still wired —
            // removing it would orphan long-presses between cards
            ViewGroup deck = deckOf(a);
            Object cb = deckCbOf(deck);
            assertNotNull("DeckView.Callback fallback must stay wired", cb);
        }
    }

    @Test
    public void ghostCard_isClickable_onlyForAdding() throws Exception {
        try (ActivityController<HomeActivity> ctl =
                     Robolectric.buildActivity(HomeActivity.class)) {
            HomeActivity a = ctl.setup().get();
            invokeInit(a);
            ViewGroup deck = deckOf(a);
            View ghost = deck.getChildAt(deck.getChildCount() - 1);
            assertTrue("the ＋ card still adds projects on tap",
                    ghost.hasOnClickListeners());
            // no long-press menu on the ghost — a long press there has no
            // meaning (nothing to rename/delete yet)
            assertFalse("the ＋ card has no long-press menu",
                    ghost.performLongClick());
        }
    }

    // ------------------------------------------------------------ helpers

    private static void invokeInit(HomeActivity a) throws Exception {
        Method m = HomeActivity.class.getDeclaredMethod("init");
        m.setAccessible(true);
        m.invoke(a);
    }

    private static ViewGroup deckOf(HomeActivity a) throws Exception {
        Object d = fieldOf(a, "deck");
        assertNotNull(d);
        return (ViewGroup) d;
    }

    private static Object deckCbOf(ViewGroup deck) {
        return ((DeckView) deck).callbackForTest();
    }

    private static Object fieldOf(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
