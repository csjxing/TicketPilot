package cn.local.tickethelper.core;

import org.junit.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Same-frame adapter evidence tests model the observed custom-drawn Damai SKU page. */
public class DamaiAdapterDecisionTest {
    private final long now=Instant.parse("2026-10-24T03:30:00Z").toEpochMilli();
    private MonitorConfig config(MonitorConfig.Mode mode) {
        return new MonitorConfig("谢霆锋","杭州","2026-11-15","19:00",Arrays.asList(980),1,980,
                Instant.parse("2026-11-15T11:00:00Z"),3000,6000,mode);
    }
    private UiItem item(String text,int y) { return new UiItem(text,30,y,500,y+50,true,false,false,UiItem.Source.OCR); }
    private UiItem confirm(boolean enabled) {
        return new UiItem("",778,2527,1186,2681,enabled,enabled,false,UiItem.Source.NODE,"cn.damai:id/btn_buy_view");
    }
    private List<UiItem> page(UiItem confirm) {
        List<UiItem> items=new ArrayList<>(Arrays.asList(item("杭州·谢霆锋演唱会",100),
                item("2026-11-14 周六19:00 无票",300),item("2026-11-15周日19:00 预售",500),item("票档",700),
                new UiItem("",20,780,1200,1780,true,false,false,UiItem.Source.NODE,"cn.damai:id/project_detail_perform_price_flowlayout"),
                item("看台980元",1000)));
        if (confirm!=null) items.add(confirm);
        return items;
    }
    private Observation.VerifiedSelection evidence(Integer price,Integer quantity,Integer total,UiItem confirm) {
        return new Observation.VerifiedSelection(now,"2026-11-15","19:00",price,quantity,total,confirm);
    }
    private Decision evaluate(List<UiItem> items,Observation.VerifiedSelection evidence,MonitorConfig.Mode mode) {
        return new DecisionEngine(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC))
                .evaluate(config(mode),new Observation("cn.damai",items,now,evidence),0);
    }
    @Test public void presaleAndMissingSoldOutTagWithoutVerifiedSessionRemainUnknown() {
        Decision d=evaluate(page(null),null,MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.State.UNKNOWN,d.state);assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void explicitSessionEvidenceAllowsTrialButNeverClaimsStock() {
        Decision d=evaluate(page(null),evidence(null,null,0,null),MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.State.PROBING,d.state);assertEquals(Decision.Action.PROBE_TIER,d.action);
        assertEquals("trial_selection_inventory_unknown",d.reason);
    }
    @Test public void alertModeDoesNotTrialSelect() {
        Decision d=evaluate(page(null),evidence(null,null,0,null),MonitorConfig.Mode.ALERT);
        assertEquals(Decision.State.UNKNOWN,d.state);assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void selectedRememberedTwoTicketsCannotConfirm() {
        UiItem confirm=confirm(true);
        Decision d=evaluate(page(confirm),evidence(980,2,1960,confirm),MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.State.UNKNOWN,d.state);assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void selectedOneTicketExactTotalAndConfirmedNodeAllowPrepare() {
        UiItem confirm=confirm(true);
        Decision d=evaluate(page(confirm),evidence(980,1,980,confirm),MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.State.TIER_SELECTED,d.state);assertEquals(Decision.Action.CONFIRM_TIER,d.action);
        assertSame(confirm,d.target);
    }
    @Test public void selectedVerifiedTicketInAlertModeOnlyNotifies() {
        UiItem confirm=confirm(true);
        Decision d=evaluate(page(confirm),evidence(980,1,980,confirm),MonitorConfig.Mode.ALERT);
        assertEquals(Decision.State.IN_STOCK,d.state);assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void disabledConfirmCannotBeExcusedBySelectionEvidence() {
        UiItem confirm=confirm(false);
        assertEquals(Decision.Action.NONE,evaluate(page(confirm),evidence(980,1,980,confirm),MonitorConfig.Mode.PREPARE).action);
    }
    @Test public void confirmMustBeInTheSameObservation() {
        UiItem confirm=confirm(true);
        assertEquals(Decision.Action.NONE,evaluate(page(null),evidence(980,1,980,confirm),MonitorConfig.Mode.PREPARE).action);
    }
    @Test public void staleAdapterEvidenceDoesNotOverrideCurrentScreen() {
        Observation.VerifiedSelection old=new Observation.VerifiedSelection(now-1,"2026-11-15","19:00",null,null,null,null);
        Decision d=evaluate(page(null),old,MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.Action.NONE,d.action);assertEquals("adapter_session_evidence_mismatch",d.reason);
    }
    @Test public void wrongSelectedSessionEvidenceDoesNotOverrideVisibleTarget() {
        Observation.VerifiedSelection wrong=new Observation.VerifiedSelection(now,"2026-11-14","19:00",null,null,null,null);
        assertEquals(Decision.Action.NONE,evaluate(page(null),wrong,MonitorConfig.Mode.PREPARE).action);
    }
    @Test public void explicitSelectedOtherDateConflictsWithAdapter() {
        List<UiItem> items=page(null);
        items.set(1,new UiItem("2026-11-14 19:00",30,300,500,350,true,true,true,UiItem.Source.NODE));
        assertEquals(Decision.Action.NONE,evaluate(items,evidence(null,null,0,null),MonitorConfig.Mode.PREPARE).action);
    }
    @Test public void selectedTierStillRequiresVisibleTargetEvent() {
        UiItem confirm=confirm(true);List<UiItem> items=page(confirm);items.set(0,item("南京 其他演唱会",100));
        assertEquals(Decision.Action.NONE,evaluate(items,evidence(980,1,980,confirm),MonitorConfig.Mode.PREPARE).action);
    }
    @Test public void adapterCannotOverrideExplicitTierUnavailability() {
        UiItem confirm=confirm(true);List<UiItem> items=page(confirm);items.set(5,item("看台980元 缺货登记",1000));
        assertEquals(Decision.Action.NONE,evaluate(items,evidence(980,1,980,confirm),MonitorConfig.Mode.PREPARE).action);
    }
    @Test public void unknownQuantityAndFeeMismatchDoNotConfirmOrRetryProbe() {
        UiItem confirm=confirm(true);
        assertEquals(Decision.Action.NONE,evaluate(page(confirm),evidence(980,null,980,confirm),MonitorConfig.Mode.PREPARE).action);
        assertEquals(Decision.Action.NONE,evaluate(page(confirm),evidence(980,1,990,confirm),MonitorConfig.Mode.PREPARE).action);
    }
    @Test public void outsidePriceAreaBottomTotalIsNotDuplicateTier() {
        UiItem confirm=confirm(true);List<UiItem> items=page(confirm);
        items.add(new UiItem("980",100,2563,180,2647,true,false,false,UiItem.Source.NODE,"cn.damai:id/tv_price"));
        items.add(item("¥980",2563));
        assertEquals(Decision.Action.CONFIRM_TIER,evaluate(items,evidence(980,1,980,confirm),MonitorConfig.Mode.PREPARE).action);
    }
    @Test public void explicitQuantityConflictWinsOverAdapterEvidence() {
        UiItem confirm=confirm(true);List<UiItem> items=page(confirm);items.add(item("购买数量 2 张",2100));
        assertEquals(Decision.Action.NONE,evaluate(items,evidence(980,1,980,confirm),MonitorConfig.Mode.PREPARE).action);
    }
    @Test public void checkoutTitleAloneCannotClaimPreparationOrUseLeftoverSelection() {
        UiItem confirm=confirm(true);List<UiItem> items=page(confirm);items.add(item("确认购买",2100));
        Decision d=evaluate(items,evidence(980,1,980,confirm),MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.State.UNKNOWN,d.state);assertEquals(Decision.Action.NONE,d.action);
        assertEquals("checkout_content_unverified",d.reason);
    }
    @Test public void checkoutSubmitControlStillHandsOffWithoutSubmitting() {
        UiItem confirm=confirm(true);List<UiItem> items=page(confirm);items.add(item("确认购买",2100));
        items.add(new UiItem("提交订单",700,2400,1180,2500,true,true,false,UiItem.Source.NODE));
        Decision d=evaluate(items,evidence(980,1,980,confirm),MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.State.CHECKOUT,d.state);assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void realQueueErrorShellIsBlockedInEveryModeEvenWithSelectedSkuEvidence() {
        UiItem confirm=confirm(true);List<UiItem> items=page(confirm);
        items.add(new UiItem("确认购买",196,146,1260,298,true,false,false,UiItem.Source.NODE,"cn.damai:id/order_activity_title"));
        items.add(new UiItem("",0,298,1260,2709,true,false,false,UiItem.Source.NODE,"cn.damai:id/error_layout"));
        items.add(new UiItem("抱歉，当前排队的人数太多啦，请稍后再试哦",88,1638,1173,1785,true,false,false,UiItem.Source.NODE,"cn.damai:id/state_view_tip"));
        items.add(new UiItem("返回",414,1838,846,1996,true,true,false,UiItem.Source.NODE,"cn.damai:id/state_view_btn"));
        for (MonitorConfig.Mode mode : MonitorConfig.Mode.values()) {
            Decision d=evaluate(items,evidence(980,1,980,confirm),mode);
            assertEquals(Decision.State.BLOCKED,d.state);assertEquals(Decision.Action.NONE,d.action);
            assertNull(d.target);assertEquals("checkout_queue_busy",d.reason);
        }
    }
    @Test public void queueTextFromOcrWinsOverSubmitLabelWithoutNativeErrorContainer() {
        List<UiItem> items=page(null);items.add(item("确认购买",200));items.add(item("提交订单",2300));
        items.add(item("抱歉，当前排队的人数",1600));items.add(item("太多啦，请稍后再试哦",1700));
        Decision d=evaluate(items,null,MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.State.BLOCKED,d.state);assertEquals(Decision.Action.NONE,d.action);
        assertEquals("checkout_queue_busy",d.reason);
    }
    @Test public void preorderEntryIsNavigationNotStockEvidence() {
        UiItem entry=new UiItem("立即预订",30,1800,500,1900,true,true,false,UiItem.Source.NODE);
        List<UiItem> items=Arrays.asList(item("杭州谢霆锋演唱会",100),item("2026-11-15 19:00",300),entry);
        Decision d=evaluate(items,null,MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.State.READY,d.state);assertEquals(Decision.Action.OPEN_TICKETS,d.action);
    }
}
