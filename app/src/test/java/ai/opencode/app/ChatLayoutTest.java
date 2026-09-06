package ai.opencode.app;

import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P14: inflate the real chat screen and verify the spend pill exists,
 * starts hidden, and the picker-related state pieces are wired. Runs the
 * ACTIVITY through Robolectric so a layout/ID typo fails here, not on the
 * user's device.
 */
@RunWith(RobolectricTestRunner.class)
public class ChatLayoutTest {

    @Test
    public void chatInflates_withSpendPill() {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();   // create + start + resume
            TextView spend = a.findViewById(R.id.tvSpend);
            assertNotNull("tvSpend must exist in activity_chat.xml", spend);
            assertEquals("spend pill hidden until there is spend",
                    View.GONE, spend.getVisibility());
            TextView sub = a.findViewById(R.id.tvSub);
            assertNotNull(sub);
            assertTrue(sub.getText().length() > 0);
            // P17: the vision chip must exist in the composer
            assertNotNull("btnVision must exist in activity_chat.xml",
                    a.findViewById(R.id.btnVision));
        }
    }

    /** P29: the composer grew upward — the attachment tray and the cost
     *  hint must exist and BOTH sit between the typing dots and the text
     *  input (the tray is composerBar's FIRST child, the hint the LAST
     *  before the input row). A regression that reorders the composer
     *  (P28's drifting-dots lesson) fails here, not on the device. */
    @Test
    public void p29_composer_trayAndCostHint_declaredAboveInput() {
        try (ActivityController<ChatActivity> ctl =
                     Robolectric.buildActivity(ChatActivity.class)) {
            ChatActivity a = ctl.setup().get();
            View attachScroll = a.findViewById(R.id.attachScroll);
            View costHint = a.findViewById(R.id.costHint);
            View composerBar = a.findViewById(R.id.composerBar);
            View input = a.findViewById(R.id.input);
            View typingSlot = a.findViewById(R.id.typingSlot);
            assertNotNull(attachScroll);
            assertNotNull(costHint);
            assertNotNull(composerBar);
            assertNotNull(input);
            assertEquals("tray hidden until an image is picked",
                    View.GONE, attachScroll.getVisibility());
            assertEquals("cost hint hidden until there is something to send",
                    View.GONE, costHint.getVisibility());
            // order in the root column: typing dots ABOVE the whole composer
            android.view.ViewGroup root = (android.view.ViewGroup) composerBar.getParent();
            assertTrue("typing dots above the composer",
                    root.indexOfChild(typingSlot) < root.indexOfChild(composerBar));
            // inside the composer: tray first, input row LAST
            android.view.ViewGroup bar = (android.view.ViewGroup) composerBar;
            assertEquals(attachScroll, bar.getChildAt(0));
            View inputRow = (View) input.getParent();
            assertEquals("the input row stays the composer's last child",
                    bar.getChildCount() - 1, bar.indexOfChild(inputRow));
            // and the hint sits directly ABOVE the input row
            assertEquals(costHint, bar.getChildAt(bar.indexOfChild(inputRow) - 1));
        }
    }
}
