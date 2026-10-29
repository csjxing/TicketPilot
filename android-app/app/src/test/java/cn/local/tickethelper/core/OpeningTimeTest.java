package cn.local.tickethelper.core;
import org.junit.Test;
import static org.junit.Assert.*;
public final class OpeningTimeTest {
    @Test public void exactMillisecondsSurviveRoundTrip() {
        assertEquals("15:00:00.007", OpeningTime.format(OpeningTime.parse("15:00:00.007")));
        assertEquals("15:00:00.000", OpeningTime.format(OpeningTime.parse("15:00:00")));
        assertEquals("23:59:59.999", OpeningTime.format(OpeningTime.parse("23:59:59.999")));
    }
    @Test public void invalidOrAmbiguousTimesAreRejected() {
        for(String value:new String[]{"24:00:00","15:60:00","15:00:60","15:00","15:00:00.1","15:00:00.0001","-1:00:00","15:00:00Z"}) {
            try { OpeningTime.parse(value); fail(value); } catch (java.time.DateTimeException | IllegalArgumentException expected) { }
        }
    }
}
