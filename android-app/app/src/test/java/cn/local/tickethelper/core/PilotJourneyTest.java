package cn.local.tickethelper.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class PilotJourneyTest {
    private PilotJourney.Input ready() {
        PilotJourney.Input input = new PilotJourney.Input();
        input.hasTarget = true; input.targetValid = true; input.deviceReady = true;
        return input;
    }

    @Test public void missingDeviceOrTargetCannotPresentStart() {
        assertEquals(PilotJourney.Action.SETUP, PilotJourney.resolve(null).action);
        PilotJourney.Input input = new PilotJourney.Input(); input.deviceReady = true;
        assertEquals(PilotJourney.Action.SELECT_EVENT, PilotJourney.resolve(input).action);
        input.hasTarget = true;
        assertEquals(PilotJourney.Stage.REPAIR_TARGET, PilotJourney.resolve(input).stage);
    }

    @Test public void importedDraftMustReturnToRealSelection() {
        PilotJourney.Input input = ready(); input.importedPendingReview = true;
        assertEquals(PilotJourney.Stage.REVIEW_TARGET, PilotJourney.resolve(input).stage);
        assertEquals(PilotJourney.Action.SELECT_EVENT, PilotJourney.resolve(input).action);
    }

    @Test public void waitingIsDistinctFromActualPageChecking() {
        PilotJourney.Input input = ready(); input.running = true; input.waitingForDamai = true;
        PilotJourney.Result waiting = PilotJourney.resolve(input);
        assertEquals(PilotJourney.Stage.WAITING_FOR_DAMAI, waiting.stage);
        assertEquals(PilotJourney.Action.OPEN_DAMAI, waiting.action);
        assertTrue(waiting.detail.contains("还不能说已查询库存"));
        input.waitingForDamai = false;
        assertEquals(PilotJourney.Stage.RUNNING, PilotJourney.resolve(input).stage);
        assertEquals(PilotJourney.Action.PAUSE, PilotJourney.resolve(input).action);
    }

    @Test public void oldWaitingFlagDoesNotMakeAnIdleTaskRunning() {
        PilotJourney.Input input = ready(); input.waitingForDamai = true;
        assertEquals(PilotJourney.Stage.READY, PilotJourney.resolve(input).stage);
    }

    @Test public void unsupportedModeAndPauseHaveClearNonStartingActions() {
        PilotJourney.Input input = ready(); input.unsupportedMode = true;
        assertEquals(PilotJourney.Action.CHANGE_MODE, PilotJourney.resolve(input).action);
        input.unsupportedMode = false; input.paused = true;
        assertEquals(PilotJourney.Action.RESOLVE_PAUSE, PilotJourney.resolve(input).action);
    }

    @Test public void checkoutDoesNotClaimOrderOrLockedInventory() {
        PilotJourney.Input input = ready(); input.checkoutReached = true;
        PilotJourney.Result result = PilotJourney.resolve(input);
        assertEquals(PilotJourney.Stage.CHECKOUT, result.stage);
        assertTrue(result.outcome.contains("没有创建订单"));
        assertNotEquals(PilotJourney.Stage.PENDING_PAYMENT, result.stage);
    }

    @Test public void unresolvedOrderHasPriorityOverRunningAndValidPlan() {
        PilotJourney.Input input = ready(); input.running = true;
        input.orderState = PilotJourney.OrderState.NEEDS_REVIEW;
        assertEquals(PilotJourney.Stage.ORDER_REVIEW, PilotJourney.resolve(input).stage);
        assertEquals(PilotJourney.Action.REVIEW_ORDER, PilotJourney.resolve(input).action);
    }

    @Test public void verifiedPendingOrderOnlyOffersUserPaymentHandoff() {
        PilotJourney.Input input = ready(); input.orderState = PilotJourney.OrderState.PENDING_PAYMENT_VERIFIED;
        PilotJourney.Result result = PilotJourney.resolve(input);
        assertEquals(PilotJourney.Action.OPEN_DAMAI, result.action);
        assertTrue(result.outcome.contains("付款由你亲自完成"));
        assertEquals(PilotJourney.OrderState.PENDING_PAYMENT_VERIFIED, input.orderState);
    }

    @Test public void stoppedStockOrCheckoutSignalsOnlyAskForCurrentPageVerification() {
        for (String event : new String[]{"CHECKOUT", "IN_STOCK"}) {
            PilotJourney.Input input = ready();
            input.stopState = MonitorStopState.fromStopEvent(event, true);
            PilotJourney.Result result = PilotJourney.resolve(input);
            assertEquals(PilotJourney.Stage.DAMAI_REVIEW, result.stage);
            assertEquals(PilotJourney.Action.OPEN_DAMAI, result.action);
            assertTrue(result.detail.contains("不代表现在有票或已锁票"));
            assertFalse(result.title.contains("已确认"));
        }
    }

    @Test public void allStoppedOrderEventsRequireReviewEvenAHistoricalPendingRecord() {
        for (String event : new String[]{"ORDER_PENDING", "ORDER_UNKNOWN", "ORDER_REVIEW", "ORDER_REJECTED"}) {
            PilotJourney.Input input = ready();
            input.stopState = MonitorStopState.fromStopEvent(event, true);
            PilotJourney.Result result = PilotJourney.resolve(input);
            assertEquals(PilotJourney.Stage.ORDER_REVIEW, result.stage);
            assertEquals(PilotJourney.Action.REVIEW_ORDER, result.action);
            assertTrue(result.detail.contains("不能证明现在有待付款订单"));
        }
    }

    @Test public void structuredStopsRecoverWithoutDependingOnChineseStatusWords() {
        for (String event : new String[]{"BLOCKED", "UNRELATED", "UNKNOWN", "CONFIG_INVALID", "EXPIRED", "NEW_STOP"}) {
            PilotJourney.Input input = ready();
            input.stopState = MonitorStopState.fromStopEvent(event, true);
            assertEquals(PilotJourney.Action.RESOLVE_PAUSE, PilotJourney.resolve(input).action);
        }
    }

    @Test public void normalPauseOffersANewExplicitReviewRatherThanAnError() {
        PilotJourney.Input input = ready();
        input.stopState = MonitorStopState.fromStopEvent("PAUSED", false);
        assertEquals(PilotJourney.Stage.PAUSED, PilotJourney.resolve(input).stage);
        assertEquals(PilotJourney.Action.START, PilotJourney.resolve(input).action);
        assertTrue(PilotJourney.resolve(input).outcome.contains("由你确认后才启动"));
        input.stopState = MonitorStopState.fromStopEvent("PAUSED", true);
        assertEquals(PilotJourney.Action.RESOLVE_PAUSE, PilotJourney.resolve(input).action);
    }

    @Test public void idleNavigationPauseCannotEraseAttentionOrReview() {
        for (MonitorStopState previous : new MonitorStopState[]{MonitorStopState.NEEDS_ATTENTION,
                MonitorStopState.REVIEW_DAMAI, MonitorStopState.REVIEW_ORDER}) {
            assertEquals(previous, MonitorStopState.afterStop(previous, MonitorStopState.PAUSED, false));
            assertEquals(MonitorStopState.PAUSED, MonitorStopState.afterStop(previous, MonitorStopState.PAUSED, true));
        }
    }

    @Test public void stopGuidanceIsBoundToThePlanRevision() {
        assertEquals(MonitorStopState.REVIEW_DAMAI, MonitorStopState.restore("REVIEW_DAMAI", 8, 8));
        assertEquals(MonitorStopState.NONE, MonitorStopState.restore("REVIEW_DAMAI", 8, 9));
        assertEquals(MonitorStopState.NONE, MonitorStopState.restore("", -1, 0));
        assertEquals(MonitorStopState.NEEDS_ATTENTION, MonitorStopState.restore("bad_state", 8, 8));
    }

    @Test public void runningTaskAndFreshOrderBarrierHavePriorityOverStopGuidance() {
        PilotJourney.Input input = ready();
        input.stopState = MonitorStopState.REVIEW_DAMAI; input.running = true;
        assertEquals(PilotJourney.Stage.RUNNING, PilotJourney.resolve(input).stage);
        input.orderState = PilotJourney.OrderState.NEEDS_REVIEW;
        assertEquals(PilotJourney.Stage.ORDER_REVIEW, PilotJourney.resolve(input).stage);
    }
}
