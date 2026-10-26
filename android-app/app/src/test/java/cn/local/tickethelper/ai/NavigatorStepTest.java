package cn.local.tickethelper.ai;

import cn.local.tickethelper.core.MonitorStopState;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavigatorStepTest {
    private NavigatorStep resolve(boolean ready, boolean target, boolean pending, String stage, boolean fresh) {
        return NavigatorStep.resolve(ready, target, pending, stage, fresh, false, MonitorStopState.NONE);
    }
    @Test public void missingPhoneSetupHasActionBeforeCatalogNavigation() {
        assertEquals(NavigatorStep.SETUP, resolve(false, true, false, "TIERS", true));
    }
    @Test public void freshSelectionTakesPrecedenceOverPreviouslySavedPlan() {
        assertEquals(NavigatorStep.SESSION, resolve(true, true, false, "SESSIONS", true));
        assertEquals(NavigatorStep.PRICE, resolve(true, true, false, "TIERS", true));
    }
    @Test public void staleCatalogCannotPretendSelectionIsStillCurrent() {
        assertEquals(NavigatorStep.PLAN, resolve(true, true, false, "TIERS", false));
        assertEquals(NavigatorStep.EVENT, resolve(true, false, false, "SESSIONS", false));
    }
    @Test public void importedDraftCanContinueRealSelectionButCannotSkipIt() {
        assertEquals(NavigatorStep.PRICE, resolve(true, true, true, "TIERS", true));
        assertEquals(NavigatorStep.EVENT, resolve(true, true, true, "TIERS", false));
        assertEquals(NavigatorStep.EVENT, resolve(true, true, true, "IDLE", false));
    }
    @Test public void pastSignalsNeverBecomeAStartOrCurrentStockClaim() {
        assertEquals(NavigatorStep.VERIFY_DAMAI, NavigatorStep.resolve(true, true, false, "TIERS", true, false, MonitorStopState.REVIEW_DAMAI));
        assertEquals("plan", NavigatorStep.VERIFY_DAMAI.destination);
        assertEquals(NavigatorStep.VERIFY_ORDER, NavigatorStep.resolve(false, true, false, "IDLE", false, false, MonitorStopState.REVIEW_ORDER));
        assertEquals("plan", NavigatorStep.VERIFY_ORDER.destination);
    }
    @Test public void realFailureHasRecoveryExitAndNormalPauseCanBeReviewed() {
        assertEquals(NavigatorStep.ISSUE, NavigatorStep.resolve(true, true, false, "IDLE", false, false, MonitorStopState.NEEDS_ATTENTION));
        assertEquals("recovery", NavigatorStep.ISSUE.destination);
        assertEquals(NavigatorStep.PLAN, NavigatorStep.resolve(true, true, false, "IDLE", false, false, MonitorStopState.PAUSED));
        assertEquals("review", NavigatorStep.PLAN.destination);
    }
    @Test public void unsupportedModeCannotOfferStartReview() {
        assertEquals(NavigatorStep.CONDITIONS, NavigatorStep.resolve(true, true, false, "IDLE", false, true, MonitorStopState.NONE));
        assertEquals("preferences", NavigatorStep.CONDITIONS.destination);
    }
    @Test public void interruptedNewSelectionDoesNotAbandonItForOldPlan() {
        assertEquals(NavigatorStep.CATALOG_ISSUE, resolve(true, true, false, "BLOCKED", true));
        assertEquals("catalog", NavigatorStep.CATALOG_ISSUE.destination);
    }
    @Test public void guidanceOnlyNavigatesAndNeverStartsOrSubmitsAnOrder() {
        for (NavigatorStep step : NavigatorStep.values())
            assertTrue(step.destination.equals("setup") || step.destination.equals("catalog") || step.destination.equals("plan")
                    || step.destination.equals("review") || step.destination.equals("recovery") || step.destination.equals("preferences"));
    }
}
