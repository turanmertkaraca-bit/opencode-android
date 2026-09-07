package ai.opencode.app;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P34 — the UI-alignment release. Pins the pure decisions behind the
 * rework:
 *
 *   1. Resume.backRule — back from a chat ALWAYS lands on the deck.
 *      The field report: after an app update the boot restored the last
 *      chat and finished the splash; the chat became the task root and
 *      BOTH backs (system + the in-app ‹) exited the app instead. The
 *      rule is one pure branch — task root → the deck is opened
 *      explicitly; any other depth → plain finish reveals the deck.
 *
 *   2. CreditLimit.QUICK_CAPS — the Pixel-style quick-pick chips in the
 *      new credit-limit sheet: presets are ascending, positive, inside
 *      the sanity ceiling, end with exactly one "no limit" sentinel,
 *      and every chip label parses back through parseCap to the same
 *      value (the chip fill → Save round-trip can never dead-end).
 */
public class P34Test {

    // ------------------------------------------------------- back rule

    @Test
    public void backRule_taskRootOpensDeckExplicitly() {
        // the update-launch case: boot → chat → splash finished → the
        // chat IS the task root. finish() alone would exit the app.
        assertEquals(Resume.BACK_OPEN_DECK, Resume.backRule(true));
    }

    @Test
    public void backRule_normalDepthJustFinishes() {
        // deck → chat: the deck sits below; finish reveals it.
        assertEquals(Resume.BACK_STAY, Resume.backRule(false));
    }

    @Test
    public void backRuleConstantsAreDistinct() {
        assertTrue(Resume.BACK_STAY != Resume.BACK_OPEN_DECK);
    }

    // ------------------------------------------------ quick-cap chips

    @Test
    public void quickCaps_ascendingPositiveInsideCeiling() {
        double[] q = CreditLimit.QUICK_CAPS;
        assertTrue(q.length >= 5);
        double prev = 0;
        for (int i = 0; i < q.length - 1; i++) {       // all but the sentinel
            assertTrue(q[i] > prev);
            assertTrue(q[i] <= CreditLimit.CAP_MAX);
            prev = q[i];
        }
    }

    @Test
    public void quickCaps_exactlyOneNoLimitSentinel_atTheEnd() {
        double[] q = CreditLimit.QUICK_CAPS;
        assertEquals(CreditLimit.CAP_NONE, q[q.length - 1], 0);
        int sentinels = 0;
        for (double v : q) if (v == CreditLimit.CAP_NONE) sentinels++;
        assertEquals(1, sentinels);
    }

    @Test
    public void chipLabels_parseBackToTheirValue() {
        // the sheet fills the input with the label minus "$"; Save then
        // parses it — every preset must survive that round-trip.
        for (double v : CreditLimit.QUICK_CAPS) {
            if (v == CreditLimit.CAP_NONE) {
                assertEquals("no limit", CreditLimit.chipLabel(v));
                assertEquals(0, CreditLimit.parseCap(""), 0);   // empty = no limit
            } else {
                String typed = CreditLimit.chipLabel(v).replace("$", "");
                assertEquals(v, CreditLimit.parseCap(typed), 1e-9);
            }
        }
    }

    @Test
    public void chipLabel_formatsMoney() {
        assertEquals("$25", CreditLimit.chipLabel(25));
    }

    // --------------------------------- parseCap still guards the sheet

    @Test
    public void parseCap_rejectsGarbageTheSheetFlagsInline() {
        assertEquals(-1, CreditLimit.parseCap("abc"), 0);
        assertEquals(-1, CreditLimit.parseCap("-5"), 0);
        assertEquals(-1, CreditLimit.parseCap("99999999"), 0);
    }

    @Test
    public void parseCap_acceptsTheChipForms() {
        assertEquals(5.5, CreditLimit.parseCap("5.50"), 0);
        assertEquals(5.5, CreditLimit.parseCap("5,50"), 0);
        assertEquals(25, CreditLimit.parseCap(" $25 "), 0);
        assertEquals(0, CreditLimit.parseCap(null), 0);
        assertEquals(0, CreditLimit.parseCap(""), 0);
    }

    // -------------------------------------- resume parse still pins boot

    @Test
    public void resumeParsing_unchangedByP34() {
        assertArrayEquals(new String[]{"deck"}, Resume.parseLastScreen(null));
        assertArrayEquals(new String[]{"deck"}, Resume.parseLastScreen("deck"));
        assertArrayEquals(new String[]{"chat", "playground", "/path/x"},
                Resume.parseLastScreen("chat|playground|/path/x"));
        assertArrayEquals(new String[]{"deck"}, Resume.parseLastScreen("chat|n|"));
        assertArrayEquals(new String[]{"deck"}, Resume.parseLastScreen("garbage"));
    }
}
