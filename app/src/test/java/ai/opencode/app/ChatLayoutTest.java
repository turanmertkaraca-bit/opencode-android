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
}
