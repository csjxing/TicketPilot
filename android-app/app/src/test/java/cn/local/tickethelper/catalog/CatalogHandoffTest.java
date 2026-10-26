package cn.local.tickethelper.catalog;

import org.junit.Test;
import static org.junit.Assert.*;
import static cn.local.tickethelper.catalog.CatalogHandoff.Result.*;

public class CatalogHandoffTest {
    private CatalogHandoff handoff() { return new CatalogHandoff(1_000, 20_000, 500); }

    @Test public void helperOrNotificationForegroundNeverBeginsReading() {
        CatalogHandoff h = handoff();
        assertEquals(WAITING, h.observe(1_750, false, true, false));
        assertEquals(WAITING, h.observe(9_000, false, true, false));
        assertEquals(WAITING, h.observe(20_999, false, true, false));
        assertEquals(TIMED_OUT, h.observe(21_000, false, true, false));
    }

    @Test public void readsOnlyOnceAfterTargetRemainsForeground() {
        CatalogHandoff h = handoff();
        assertEquals(WAITING, h.observe(1_250, true, true, false));
        assertEquals(WAITING, h.observe(1_749, true, true, false));
        assertEquals(READY, h.observe(1_750, true, true, false));
        assertEquals(FINISHED, h.observe(2_000, true, true, false));
    }

    @Test public void briefTargetAppearanceDoesNotSurviveForegroundLoss() {
        CatalogHandoff h = handoff();
        assertEquals(WAITING, h.observe(2_000, true, true, false));
        assertEquals(WAITING, h.observe(2_400, false, true, false));
        assertEquals(WAITING, h.observe(2_500, true, true, false));
        assertEquals(WAITING, h.observe(2_999, true, true, false));
        assertEquals(READY, h.observe(3_000, true, true, false));
    }

    @Test public void cancellationBeforeReadyPreventsBothReadAndLateTimeout() {
        CatalogHandoff h = handoff();
        h.observe(2_000, true, true, false);
        h.cancel();
        assertEquals(CANCELLED, h.observe(2_500, true, true, false));
        assertEquals(CANCELLED, h.observe(22_000, true, true, false));
    }

    @Test public void cancelledPreviousOperationCannotBecomeReadyDuringNewOperation() {
        CatalogHandoff previous = handoff();
        previous.observe(2_000, true, true, false);
        previous.cancel();
        CatalogHandoff next = new CatalogHandoff(3_000, 20_000, 500);
        assertEquals(CANCELLED, previous.observe(3_500, true, true, false));
        assertEquals(WAITING, next.observe(3_500, true, true, false));
        assertEquals(READY, next.observe(4_000, true, true, false));
    }

    @Test public void deadlineWinsEvenWhenTargetBecomesReadyAtBoundary() {
        CatalogHandoff h = handoff();
        assertEquals(WAITING, h.observe(20_500, true, true, false));
        assertEquals(TIMED_OUT, h.observe(21_000, true, true, false));
        assertEquals(FINISHED, h.observe(21_500, true, true, false));
    }

    @Test public void lockOrMonitoringStartStopsHandoffWithoutRead() {
        assertEquals(STATE_CHANGED, handoff().observe(1_500, true, false, false));
        assertEquals(STATE_CHANGED, handoff().observe(1_500, true, true, true));
    }
}
