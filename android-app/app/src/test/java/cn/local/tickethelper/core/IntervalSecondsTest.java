package cn.local.tickethelper.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class IntervalSecondsTest {
    @Test public void acceptsExactDecimalSecondsAndLegacyWholeSeconds() {
        assertEquals(500, IntervalSeconds.parse("0.5"));
        assertEquals(500, IntervalSeconds.parse(".5"));
        assertEquals(500, IntervalSeconds.parse(" 0.5000 "));
        assertEquals(501, IntervalSeconds.parse("0.501"));
        assertEquals(1500, IntervalSeconds.parse("1.5"));
        assertEquals(5000, IntervalSeconds.parse("5"));
        assertEquals(600000, IntervalSeconds.parse("600"));
    }
    @Test public void formattingAndParsingNeverLosePersistedMilliseconds() {
        for (long millis : new long[]{500, 501, 999, 1000, 1501, 5000, 10000, 600000})
            assertEquals(millis, IntervalSeconds.parse(IntervalSeconds.format(millis)));
        assertEquals("0.5", IntervalSeconds.format(500));
        assertEquals("5", IntervalSeconds.format(5000));
    }
    @Test public void rejectsAmbiguousOverflowOrSubMillisecondInput() {
        for (String input : new String[]{null, "", "abc", "0", "0.499", "-1", "600.001", "0.5001",
                "1,5", "1e3", "NaN", "Infinity", "1.2.3", "999999999999999999999999999999999999999999"}) {
            try { IntervalSeconds.parse(input); fail("must reject " + input); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
    }
}
