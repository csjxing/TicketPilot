package cn.local.tickethelper.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ActionPacingTest {
    private final UiItem target = new UiItem("确定", 10, 10, 100, 80, true, true, false, UiItem.Source.NODE, "test/confirm");
    private Decision decision(Decision.State state, Decision.Action action, long at) {
        return new Decision(state, action, target, "synthetic_test", at);
    }
    @Test public void verifiedConfirmAdvancesBeforeRoutineCooldown() {
        assertTrue(ActionPacing.mayDispatch(decision(Decision.State.TIER_SELECTED, Decision.Action.CONFIRM_TIER, 10000), 10001, 400, 5400, false));
    }
    @Test public void aFailedConfirmStillRespectsItsRetryDelay() {
        Decision confirm = decision(Decision.State.TIER_SELECTED, Decision.Action.CONFIRM_TIER, 10000);
        assertFalse(ActionPacing.mayDispatch(confirm,10001,400,5400,true));
        assertTrue(ActionPacing.mayDispatch(confirm,10001,5400,5400,true));
    }
    @Test public void navigationAndProbeContinueToRespectConfiguredWait() {
        for (Decision.Action action : new Decision.Action[]{Decision.Action.BACK, Decision.Action.OPEN_TICKETS,
                Decision.Action.PROBE_TIER, Decision.Action.SELECT_TIER}) {
            Decision d = decision(Decision.State.READY, action, 10000);
            assertFalse(ActionPacing.mayDispatch(d,10001,400,5400,false));
            assertTrue(ActionPacing.mayDispatch(d,10001,5400,5400,false));
        }
    }
    @Test public void staleFutureUnverifiedOrMissingConfirmCannotSkipWaiting() {
        for (long observed : new long[]{0, 7000, 10001})
            assertFalse(ActionPacing.mayDispatch(decision(Decision.State.TIER_SELECTED,Decision.Action.CONFIRM_TIER,observed),10000,400,5400,false));
        assertFalse(ActionPacing.mayDispatch(decision(Decision.State.UNKNOWN,Decision.Action.CONFIRM_TIER,10000),10001,400,5400,false));
        assertFalse(ActionPacing.mayDispatch(new Decision(Decision.State.TIER_SELECTED,Decision.Action.CONFIRM_TIER,null,"test",10000),10001,400,5400,false));
        assertFalse(ActionPacing.mayDispatch(decision(Decision.State.TIER_SELECTED,Decision.Action.NONE,10000),10001,9999,5400,false));
    }
}
