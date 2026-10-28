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
    @Test public void onlyBoundFreshQuantityEvidenceCanAdvanceBeforeIdleDelay() {
        UiItem plus=new UiItem("",110,10,160,60,true,true,false,UiItem.Source.NODE,"cn.damai:id/img_jia");
        UiItem confirm=new UiItem("确定",10,100,160,150,true,true,false,UiItem.Source.NODE,"cn.damai:id/btn_buy_view");
        var evidence=new Observation.VerifiedSelection(10000,"2026-10-31","19:00",1680,1,1680,confirm,plus);
        Decision quantity=new Decision(Decision.State.PROBING,Decision.Action.INCREASE_QUANTITY,plus,"test",10000,evidence);
        assertTrue(ActionPacing.mayDispatch(quantity,10001,400,5400,false));
        assertFalse(ActionPacing.mayDispatch(quantity,10001,400,5400,true));
        assertFalse(ActionPacing.mayDispatch(quantity,14000,400,5400,false));
        assertFalse(ActionPacing.mayDispatch(new Decision(Decision.State.PROBING,Decision.Action.INCREASE_QUANTITY,target,"test",10000,evidence),10001,400,5400,false));
    }
    @Test public void onlyExplicitAvailableSelectionCanSkipIdleWaiting() {
        Decision positive=new Decision(Decision.State.IN_STOCK,Decision.Action.SELECT_TIER,target,"explicit_available_label",10000);
        assertTrue(ActionPacing.mayDispatch(positive,10001,400,5400,false));
        assertFalse(ActionPacing.mayDispatch(positive,10001,400,5400,true));
        assertFalse(ActionPacing.mayDispatch(positive,14000,400,5400,false));
        for(Decision d:new Decision[]{
                new Decision(Decision.State.UNKNOWN,Decision.Action.SELECT_TIER,target,"explicit_available_label",10000),
                new Decision(Decision.State.IN_STOCK,Decision.Action.SELECT_TIER,target,"inventory_or_selection_ambiguous",10000),
                new Decision(Decision.State.PROBING,Decision.Action.PROBE_TIER,target,"trial_selection_inventory_unknown",10000)})
            assertFalse(ActionPacing.mayDispatch(d,10001,400,5400,false));
    }
    @Test public void onlyFreshSameRoundReturnNavigationCanSkipSecondRandomInterval() {
        Decision reentry = new Decision(Decision.State.READY, Decision.Action.OPEN_TICKETS, target,
                "verified_return_detail_entry", 10000);
        assertTrue(ActionPacing.mayDispatch(reentry,10001,400,90000,false));
        assertFalse(ActionPacing.mayDispatch(reentry,10001,400,90000,true));
        assertFalse(ActionPacing.mayDispatch(reentry,13000,400,90000,false));
        assertFalse(ActionPacing.mayDispatch(new Decision(Decision.State.READY, Decision.Action.OPEN_TICKETS,
                target,"verified_detail_entry",10000),10001,400,90000,false));
        assertFalse(ActionPacing.mayDispatch(new Decision(Decision.State.UNKNOWN, Decision.Action.OPEN_TICKETS,
                target,"verified_return_detail_entry",10000),10001,400,90000,false));
    }
}
