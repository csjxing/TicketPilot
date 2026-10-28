package cn.local.tickethelper.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic public SKU facts. Two-ticket confirmation never submits an order. */
public final class TwoTicketPreparationTest {
    private final long now = Instant.parse("2026-10-27T06:00:00Z").toEpochMilli();
    private final UiItem confirm = new UiItem("确定", 30, 1100, 500, 1150, true, true, false,
            UiItem.Source.NODE, "cn.damai:id/btn_buy_view");

    private MonitorConfig config() {
        return new MonitorConfig("演出测试", "温州", "2026-10-31", "19:00", List.of(1680), 2, 3360,
                Instant.parse("2026-10-31T11:00:00Z"), 500, 1500, MonitorConfig.Mode.PREPARE);
    }

    private UiItem item(String text, int top, boolean clickable, boolean selected) {
        return new UiItem(text, 30, top, 500, top + 50, true, clickable, selected, UiItem.Source.NODE);
    }

    private List<UiItem> items(int quantity, int total) {
        return new ArrayList<>(List.of(item("温州 演出测试", 100, false, false),
                item("2026-10-31 19:00", 200, false, false), item("票档", 300, false, false),
                item("1680元", 500, true, true), item("购买数量 " + quantity + " 张", 800, false, false),
                item("合计 ¥" + total, 950, false, false), confirm));
    }

    private Observation.VerifiedSelection evidence(Integer quantity, Integer total) {
        return new Observation.VerifiedSelection(now, "2026-10-31", "19:00", 1680, quantity, total, confirm);
    }

    private Decision evaluate(List<UiItem> items, Observation.VerifiedSelection evidence) {
        return new DecisionEngine(Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))
                .evaluate(config(), new Observation("cn.damai", items, now, evidence), 0);
    }

    @Test public void sameFrameTwoTicketsAndExactTotalCanPrepareConfirmation() {
        Decision result = evaluate(items(2, 3360), evidence(2, 3360));
        assertEquals(Decision.State.TIER_SELECTED, result.state);
        assertEquals(Decision.Action.CONFIRM_TIER, result.action);
        assertSame(confirm, result.target);
    }

    @Test public void completeNativeTwoTicketFactsCanPrepareWithoutCoercingQuantity() {
        Decision result = evaluate(items(2, 3360), null);
        assertEquals(Decision.State.TIER_SELECTED, result.state);
        assertEquals(Decision.Action.CONFIRM_TIER, result.action);
    }

    @Test public void oneTicketCannotConfirmOrInventAnIncreaseAction() {
        Decision result = evaluate(items(1, 1680), evidence(1, 1680));
        assertEquals(Decision.State.UNKNOWN, result.state);
        assertEquals(Decision.Action.NONE, result.action);
        assertEquals("adapter_quantity_or_total_not_verified", result.reason);
    }

    @Test public void twoTicketsRequireAnExactVerifiedTotal() {
        for (Integer total : new Integer[]{null, 1680, 3359, 3361}) {
            Decision result = evaluate(items(2, total == null ? 3360 : total), evidence(2, total));
            assertEquals(Decision.Action.NONE, result.action);
            assertEquals("adapter_quantity_or_total_not_verified", result.reason);
        }
    }

    @Test public void visibleQuantityConflictCannotBeOverruledByTypedEvidence() {
        Decision result = evaluate(items(1, 3360), evidence(2, 3360));
        assertEquals(Decision.Action.NONE, result.action);
        assertEquals("adapter_quantity_or_total_not_verified", result.reason);
    }

    @Test public void wrongSessionOrDifferentFrameCannotConfirmTwoTickets() {
        for (Observation.VerifiedSelection selection : List.of(
                new Observation.VerifiedSelection(now, "2026-11-01", "19:00", 1680, 2, 3360, confirm),
                new Observation.VerifiedSelection(now - 1, "2026-10-31", "19:00", 1680, 2, 3360, confirm))) {
            Decision result = evaluate(items(2, 3360), selection);
            assertEquals(Decision.Action.NONE, result.action);
            assertEquals("adapter_session_evidence_mismatch", result.reason);
        }
    }

    @Test public void reservationControlsRemainCatalogOnlyEvenWithCompleteLookingAmounts() {
        List<UiItem> items = items(2, 3360);
        items.add(new UiItem("预约想看场次", 20, 50, 400, 90, true, false, false, UiItem.Source.NODE,
                "cn.damai:id/tv_perform_name"));
        Decision result = evaluate(items, evidence(2, 3360));
        assertEquals(Decision.State.UNKNOWN, result.state);
        assertEquals(Decision.Action.NONE, result.action);
        assertEquals("reservation_selection_only", result.reason);
    }

    private UiItem plus(boolean enabled) { return new UiItem("",530,800,590,850,enabled,true,false,UiItem.Source.NODE,"cn.damai:id/img_jia"); }
    @Test public void freshAdapterBoundPlusProposesOneIncreaseRatherThanConfirmOrClaimTwoTickets() {
        UiItem plus=plus(true);List<UiItem> items=items(1,1680);items.add(plus);
        var selection=new Observation.VerifiedSelection(now,"2026-10-31","19:00",1680,1,1680,confirm,plus);
        Decision result=evaluate(items,selection);
        assertEquals(Decision.State.PROBING,result.state);assertEquals(Decision.Action.INCREASE_QUANTITY,result.action);
        assertSame(plus,result.target);assertSame(selection,result.selectionEvidence);
    }
    @Test public void missingDisabledOrDetachedIncreaseNodeDoesNotAuthorizeAPlus() {
        for(boolean enabled:List.of(false,true)) {
            UiItem plus=plus(enabled);List<UiItem> items=items(1,1680);if(!enabled)items.add(plus);
            Decision result=evaluate(items,new Observation.VerifiedSelection(now,"2026-10-31","19:00",1680,1,1680,confirm,plus));
            assertEquals(Decision.Action.NONE,result.action);
        }
    }
    @Test public void contradictoryVisibleOneTicketAmountOrCountCannotBeOverruledForIncrease() {
        UiItem plus=plus(true);
        for(List<UiItem> items:List.of(items(2,1680),items(1,3360))) {
            items.add(plus);Decision result=evaluate(items,new Observation.VerifiedSelection(now,"2026-10-31","19:00",1680,1,1680,confirm,plus));
            assertEquals(Decision.Action.NONE,result.action);
        }
    }
    @Test public void twoTicketsOrOversizedAmountNeverProposeAnotherIncrease() {
        UiItem plus=plus(true);List<UiItem> items=items(2,3360);items.add(plus);
        Decision result=evaluate(items,new Observation.VerifiedSelection(now,"2026-10-31","19:00",1680,2,3360,confirm,plus));
        assertEquals(Decision.Action.CONFIRM_TIER,result.action);
        assertSame(result.selectionEvidence.confirmTarget,confirm);
        items=items(1,1681);items.add(plus);
        assertEquals(Decision.Action.NONE,evaluate(items,new Observation.VerifiedSelection(now,"2026-10-31","19:00",1680,1,1681,confirm,plus)).action);
    }
}
