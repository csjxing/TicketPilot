package cn.local.tickethelper.core;

import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic transition regressions; none represent a phone click or inventory success. */
public final class QuantityTransitionTest {
    private final UiItem plus=new UiItem("",200,100,250,150,true,true,false,UiItem.Source.NODE,"cn.damai:id/img_jia");
    private final UiItem confirm=new UiItem("",100,200,300,250,true,true,false,UiItem.Source.NODE,"cn.damai:id/btn_buy_view");
    private Observation.VerifiedSelection selection(String date,int price,Integer count,Integer total,UiItem increase) {
        return new Observation.VerifiedSelection(1234,date,"19:00",price,count,total,confirm,increase);
    }
    private Observation.VerifiedSelection one(){return selection("2026-10-31",1680,1,1680,plus);}
    @Test public void onlyOneIncreaseCanBeInFlightUntilExplicitReset() {
        QuantityTransition t=new QuantityTransition();assertTrue(t.begin(one(),100));assertFalse(t.begin(one(),200));
        assertEquals(QuantityTransition.Result.WAITING,t.inspect(one(),1000));assertFalse(t.begin(one(),1100));
        t.reset();assertFalse(t.pending());assertTrue(t.begin(one(),1200));
    }
    @Test public void readbackMustRetainOriginalDatePriceAndTwoTimesAmount() {
        QuantityTransition t=new QuantityTransition();assertTrue(t.begin(one(),100));
        assertEquals(QuantityTransition.Result.VERIFIED,t.inspect(selection("2026-10-31",1680,2,3360,null),101));
        assertEquals(QuantityTransition.Result.CHANGED,t.inspect(selection("2026-11-01",1680,2,3360,null),102));
        assertEquals(QuantityTransition.Result.CHANGED,t.inspect(selection("2026-10-31",1380,2,2760,null),103));
        assertEquals(QuantityTransition.Result.CHANGED,t.inspect(selection("2026-10-31",1680,2,3361,null),104));
        assertEquals(QuantityTransition.Result.CHANGED,t.inspect(selection("2026-10-31",1680,3,5040,null),105));
    }
    @Test public void unknownReadDoesNotAllowAnotherPlusAndSixSecondsTimesOut() {
        QuantityTransition t=new QuantityTransition();assertTrue(t.begin(one(),100));
        assertEquals(QuantityTransition.Result.WAITING,t.inspect(null,1000));
        assertEquals(QuantityTransition.Result.WAITING,t.inspect(selection("2026-10-31",1680,null,null,null),6099));
        assertEquals(QuantityTransition.Result.TIMED_OUT,t.inspect(one(),6100));assertFalse(t.begin(one(),7000));
    }
    @Test public void noControlOrAlreadyTwoOrBadTotalCannotStartATransition() {
        assertFalse(QuantityTransition.canBegin(selection("2026-10-31",1680,1,1680,null)));
        assertFalse(QuantityTransition.canBegin(selection("2026-10-31",1680,2,3360,plus)));
        assertFalse(QuantityTransition.canBegin(selection("2026-10-31",1680,1,1681,plus)));
        assertFalse(QuantityTransition.canBegin(selection("",1680,1,1680,plus)));
    }
    @Test public void increaseProposalMustBindItsTargetStateAndOriginalFrame() {
        var evidence=one();
        assertTrue(QuantityTransition.isIncreaseProposal(new Decision(Decision.State.PROBING,Decision.Action.INCREASE_QUANTITY,plus,"test",1234,evidence)));
        assertFalse(QuantityTransition.isIncreaseProposal(new Decision(Decision.State.PROBING,Decision.Action.INCREASE_QUANTITY,confirm,"test",1234,evidence)));
        assertFalse(QuantityTransition.isIncreaseProposal(new Decision(Decision.State.PROBING,Decision.Action.INCREASE_QUANTITY,plus,"test",1235,evidence)));
        assertFalse(QuantityTransition.isIncreaseProposal(new Decision(Decision.State.UNKNOWN,Decision.Action.INCREASE_QUANTITY,plus,"test",1234,evidence)));
    }
}
