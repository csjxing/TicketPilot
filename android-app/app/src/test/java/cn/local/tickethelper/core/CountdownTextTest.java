package cn.local.tickethelper.core;
import org.junit.Test;
import static org.junit.Assert.*;
public final class CountdownTextTest {
    @Test public void retainsMillisecondsAcrossBoundaryWithoutWrappingDays() {
        assertEquals("00:00:00.001",CountdownText.format(1));
        assertEquals("00:00:01.001",CountdownText.format(1001));
        assertEquals("25:00:00.000",CountdownText.format(90000000));
        assertEquals("00:00:00.000",CountdownText.format(-1));
    }
}
