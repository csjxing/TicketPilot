package cn.local.tickethelper.core;
import org.junit.Test;
import static org.junit.Assert.*;
public final class CapturePacingTest {
    @Test public void processingTimeCountsTowardScreenshotCadence() {
        assertEquals(400,CapturePacing.remainingMs(1000,1000));
        assertEquals(160,CapturePacing.remainingMs(1000,1240));
        assertEquals(0,CapturePacing.remainingMs(1000,1400));
        assertEquals(0,CapturePacing.remainingMs(1000,2000));
    }
    @Test public void firstCaptureAndClockRollbackAreBounded() {
        assertEquals(0,CapturePacing.remainingMs(0,1000));
        assertEquals(400,CapturePacing.remainingMs(1000,999));
    }
}
