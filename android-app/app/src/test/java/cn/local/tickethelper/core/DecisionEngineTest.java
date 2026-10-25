package cn.local.tickethelper.core;

import org.junit.Test;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class DecisionEngineTest {
    private final long now = Instant.parse("2026-10-23T06:00:00Z").toEpochMilli();

    private DecisionEngine engine() { return new DecisionEngine(Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)); }

    private MonitorConfig config(MonitorConfig.Mode mode) {
        return new MonitorConfig("演出测试", "杭州", "2026-10-23", "19:30", Arrays.asList(1680,1380,980), 1,1680,
                Instant.parse("2026-10-23T11:30:00Z"),3000,6000,mode);
    }
    private UiItem text(String s,int y) { return new UiItem(s,30,y,500,y+50,true,false,false,UiItem.Source.NODE); }
    private UiItem tier(String s,int y,boolean selected) { return new UiItem(s,30,y,500,y+60,true,true,selected,UiItem.Source.NODE); }
    private List<UiItem> sku(UiItem... extra) {
        List<UiItem> items=new ArrayList<>(Arrays.asList(text("杭州 演出测试",100),text("2026-10-23 19:30",200),text("票档",300)));
        items.addAll(Arrays.asList(extra)); return items;
    }
    private Observation obs(List<UiItem> items) { return new Observation("cn.damai",items,now); }
    private Decision eval(List<UiItem> items) { return engine().evaluate(config(MonitorConfig.Mode.PREPARE),obs(items),0); }

    @Test public void disabledOrPlainPricesDoNotMeanInStock() {
        Decision d=eval(sku(tier("1680元",500,false),tier("1380元",700,false),tier("980元",900,false)));
        assertEquals(Decision.State.UNKNOWN,d.state); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void allVisibleUnavailableAllowsOneReturn() {
        Decision d=eval(sku(tier("1680元 缺货登记",500,false),tier("1380元 售罄",700,false),tier("980元 无票",900,false)));
        assertEquals(Decision.State.OUT_OF_STOCK,d.state); assertEquals(Decision.Action.BACK,d.action);
    }
    @Test public void missingTargetTierIsUnknown() {
        Decision d=eval(sku(tier("1680元 缺货",500,false),tier("1380元 缺货",700,false)));
        assertEquals(Decision.State.UNKNOWN,d.state); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void explicitAvailableOnlySelectsAcceptedPrice() {
        Decision d=eval(sku(tier("1680元 缺货",500,false),tier("1380元 有票",700,false),tier("980元 缺货",900,false)));
        assertEquals(Decision.State.IN_STOCK,d.state); assertEquals(Decision.Action.SELECT_TIER,d.action);
        assertEquals("1380元 有票",d.target.text);
    }
    @Test public void alertModeNeverSelectsPrice() {
        Decision d=engine().evaluate(config(MonitorConfig.Mode.ALERT),obs(sku(tier("1380元 有票",700,false))),0);
        assertEquals(Decision.State.IN_STOCK,d.state); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void notifyWhenAvailableLabelIsNotActualInventory() {
        Decision d=eval(sku(tier("1380元 有票提醒",700,false)));
        assertEquals(Decision.State.UNKNOWN,d.state);
    }
    @Test public void explanationAboutAvailabilityIsNotInventory() {
        Decision d=eval(sku(tier("1380元 检测到有票时提醒",700,false)));
        assertEquals(Decision.State.UNKNOWN,d.state);
    }
    @Test public void negativeWinsWhenLabelsConflict() {
        Decision d=eval(sku(tier("1380元 有票 缺货登记",700,false)));
        assertEquals(Decision.Action.NONE,d.action); assertNotEquals(Decision.State.IN_STOCK,d.state);
    }
    @Test public void selectedTierRequiresQuantityAndExactTotal() {
        Decision d=eval(sku(tier("1380元",700,true),text("购买数量 1 张",900),text("合计 ¥1380.00",1000),tier("确定",1100,false)));
        assertEquals(Decision.State.TIER_SELECTED,d.state); assertEquals(Decision.Action.CONFIRM_TIER,d.action);
    }
    @Test public void extraFeesBlockConfirmation() {
        Decision d=eval(sku(tier("1380元",700,true),text("购买数量 1 张",900),text("合计 ¥1390",1000),tier("确定",1100,false)));
        assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void wrongQuantityBlocksConfirmation() {
        Decision d=eval(sku(tier("1380元",700,true),text("购买数量 2 张",900),text("合计 ¥1380",1000),tier("确定",1100,false)));
        assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void checkoutNeverSubmitsOrder() {
        Decision d=eval(sku(text("确认购买",400),tier("立即提交",1100,false)));
        assertEquals(Decision.State.CHECKOUT,d.state); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void lockModeCanPrepareButSkuEngineStillCannotSubmitCheckout() {
        var selected=sku(tier("1380元",700,true),text("购买数量 1 张",900),text("合计 ¥1380.00",1000),tier("确定",1100,false));
        var decision=engine().evaluate(config(MonitorConfig.Mode.LOCK_ORDER),obs(selected),0);
        assertEquals(Decision.Action.CONFIRM_TIER,decision.action);
        decision=engine().evaluate(config(MonitorConfig.Mode.LOCK_ORDER),obs(sku(text("确认购买",400),tier("提交订单",1100,false))),0);
        assertEquals(Decision.State.CHECKOUT,decision.state); assertEquals(Decision.Action.NONE,decision.action);
    }
    @Test public void verificationPausesEvenWithPositiveInventory() {
        Decision d=eval(sku(text("请完成滑块验证",400),tier("1380元 有票",700,false)));
        assertEquals(Decision.State.BLOCKED,d.state); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void bareOrderTitlesRemainUnknownWithoutCheckoutContent() {
        for (String title : Arrays.asList("确认购买", "确认订单")) {
            Decision d=eval(Arrays.asList(text(title,100)));
            assertEquals(Decision.State.UNKNOWN,d.state); assertEquals(Decision.Action.NONE,d.action);
            assertEquals("checkout_content_unverified",d.reason);
        }
    }
    @Test public void nativeErrorShellBlocksEvenWhenItsMessageIsNotRecognized() {
        List<UiItem> items=sku(text("确认购买",100),tier("1380元 有票",700,false));
        items.add(new UiItem("",0,300,600,2000,true,false,false,UiItem.Source.NODE,"cn.damai:id/error_layout"));
        items.add(new UiItem("暂时无法展示，请稍后再试",40,800,550,900,true,false,false,UiItem.Source.NODE,"cn.damai:id/state_view_tip"));
        Decision d=eval(items);
        assertEquals(Decision.State.BLOCKED,d.state); assertEquals(Decision.Action.NONE,d.action);
        assertEquals("native_error_page",d.reason);
    }
    @Test public void nativeErrorReturnButtonBlocksWhenErrorTextIsUnreadable() {
        List<UiItem> items=sku(text("确认购买",100));
        items.add(new UiItem("",0,300,600,2000,true,false,false,UiItem.Source.NODE,"cn.damai:id/error_layout"));
        items.add(new UiItem("返回",40,800,550,900,true,true,false,UiItem.Source.NODE,"cn.damai:id/state_view_btn"));
        Decision d=eval(items);
        assertEquals(Decision.State.BLOCKED,d.state); assertEquals(Decision.Action.NONE,d.action);
        assertNull(d.target);
    }
    @Test public void isolatedErrorContainerIsNotEnoughToClassifyAnError() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.add(new UiItem("",0,300,600,2000,true,false,false,UiItem.Source.NODE,"cn.damai:id/error_layout"));
        assertEquals(Decision.State.IN_STOCK,eval(items).state);
    }
    @Test public void expiryBlocksAllActions() {
        Decision d=engine().evaluate(config(MonitorConfig.Mode.PREPARE),new Observation("cn.damai",sku(tier("1380元 有票",700,false)),now+86400000),0);
        assertEquals(Decision.State.EXPIRED,d.state); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void wrongApplicationIsNeverOperated() {
        Decision d=engine().evaluate(config(MonitorConfig.Mode.PREPARE),new Observation("cn.other",sku(tier("1380元 有票",700,false)),now),0);
        assertEquals(Decision.State.UNRELATED,d.state);
    }
    @Test public void wrongDateOrCityDoesNotAllowSelection() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.set(0,text("南京 演出测试",100)); assertEquals(Decision.Action.NONE,eval(items).action);
        items.set(0,text("杭州 演出测试",100)); items.set(1,text("2026-10-24 19:30",200));
        assertEquals(Decision.Action.NONE,eval(items).action);
    }
    @Test public void multipleDatesRequireSelectedTarget() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.add(text("2026-10-24 19:30",250));
        assertEquals(Decision.Action.NONE,eval(items).action);
        items.set(1,tier("2026-10-23 19:30",200,true));
        assertEquals(Decision.Action.SELECT_TIER,eval(items).action);
    }
    @Test public void missingTitleMayUseOnlyActuallyOpenedVerifiedContext() {
        DecisionEngine e=engine(); MonitorConfig c=config(MonitorConfig.Mode.PREPARE);
        List<UiItem> detail=Arrays.asList(text("杭州 演出测试",100),text("2026-10-23 19:30",200),tier("缺货登记",1100,false));
        Decision open=e.evaluate(c,obs(detail),0); assertEquals(Decision.Action.OPEN_TICKETS,open.action);
        List<UiItem> items=sku(tier("1380元 有票",700,false)); items.remove(0);
        assertEquals(Decision.Action.NONE,e.evaluate(c,obs(items),0).action);
        // The initial proposal was superseded; recording a stale decision cannot arm context.
        e.recordAction(open,now); assertEquals(Decision.Action.NONE,e.evaluate(c,obs(items),0).action);
        open=e.evaluate(c,obs(detail),0); e.recordAction(open,now);
        assertEquals(Decision.Action.SELECT_TIER,e.evaluate(c,obs(items),0).action);
        assertEquals(Decision.Action.NONE,e.evaluate(c,new Observation("cn.damai",items,now+120001),0).action);
    }
    @Test public void returnWaitsUntilIntervalDue() {
        Decision d=engine().evaluate(config(MonitorConfig.Mode.PREPARE),obs(sku(tier("1680元 缺货",500,false),tier("1380元 缺货",700,false),tier("980元 缺货",900,false))),now-1000);
        assertEquals(Decision.State.OUT_OF_STOCK,d.state); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void duplicatePriceRegionsAreAmbiguous() {
        Decision d=eval(sku(tier("1380元 有票",700,false),tier("1380元 缺货",900,false)));
        assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void nearbyStockLabelStaysWithItsOwnTier() {
        UiItem low=new UiItem("680元",30,600,500,650,true,true,false,UiItem.Source.NODE);
        UiItem sold=text("缺货登记",651);
        Decision d=eval(sku(low,sold,tier("980元",720,false)));
        assertEquals(Decision.State.UNKNOWN,d.state);
    }
    @Test public void selectedOcrColorGuessCannotConfirm() {
        UiItem selected=new UiItem("1380元",30,700,500,760,true,true,true,UiItem.Source.OCR);
        Decision d=eval(sku(selected,text("购买数量 1 张",900),text("合计 ¥1380",1000),tier("确定",1100,false)));
        assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void unexpectedDisplayedYearIsNotCurrentDate() {
        List<UiItem> items=sku(tier("1380元 有票",700,false)); items.set(1,text("2027-10-23 19:30",200));
        assertEquals(Decision.Action.NONE,eval(items).action);
    }
    @Test public void multipleSessionTimesRequireExplicitSelectedSession() {
        List<UiItem> items=sku(tier("1380元 有票",700,false)); items.add(text("2026-10-23 15:00",250));
        assertEquals(Decision.Action.NONE,eval(items).action);
    }
    @Test public void sameMonthDayAcrossYearsRemainsAmbiguous() {
        List<UiItem> items=sku(tier("1380元 有票",700,false)); items.add(text("2027-10-23 19:30",250));
        assertEquals(Decision.Action.NONE,eval(items).action);
    }
    @Test public void staleScreenCannotTriggerGesture() {
        Decision d=engine().evaluate(config(MonitorConfig.Mode.PREPARE),new Observation("cn.damai",sku(tier("1380元 有票",700,false)),now-3001),0);
        assertEquals(Decision.State.UNKNOWN,d.state); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void futureScreenClockCannotTriggerGesture() {
        Decision d=engine().evaluate(config(MonitorConfig.Mode.PREPARE),new Observation("cn.damai",sku(tier("1380元 有票",700,false)),now+1001),0);
        assertEquals(Decision.State.UNKNOWN,d.state); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void observedTimestampIsRetainedForLastMomentGestureCheck() {
        Decision d=eval(sku(tier("1380元 有票",700,false)));
        assertEquals(now,d.observedAtMs);
    }
    @Test public void conflictingSelectedSessionsPreventPreparation() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.set(1,tier("2026-10-23 19:30",200,true)); items.add(tier("2026-10-24 19:30",250,true));
        assertEquals(Decision.Action.NONE,eval(items).action);
    }
    @Test public void selectedWrongSessionCannotBeOverruledByVisibleTargetDate() {
        List<UiItem> items=sku(tier("1380元 有票",700,false)); items.add(tier("2026-10-24 19:30",250,true));
        assertEquals(Decision.Action.NONE,eval(items).action);
    }
    @Test public void selectedUnexpectedPricePreventsConfirmation() {
        Decision d=eval(sku(tier("1380元",700,true),tier("680元",800,true),text("购买数量 1 张",900),text("合计 ¥1380",1000),tier("确定",1100,false)));
        assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void overPreciseFractionDoesNotPassAsExactTotal() {
        Decision d=eval(sku(tier("1380元",700,true),text("购买数量 1 张",900),text("合计 ¥1380.001",1000),tier("确定",1100,false)));
        assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void ocrCannotProveConfirmButtonEnabled() {
        UiItem confirm=new UiItem("确定",30,1100,500,1160,true,true,false,UiItem.Source.OCR);
        Decision d=eval(sku(tier("1380元",700,true),text("购买数量 1 张",900),text("合计 ¥1380",1000),confirm));
        assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void wrappedOcrTitleContinuationRetainsVerifiedTarget() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.set(0,new UiItem("杭州 演出测试",30,100,500,150,true,false,false,UiItem.Source.OCR));
        items.add(new UiItem("巡回演唱会",30,155,500,195,true,false,false,UiItem.Source.OCR));
        Decision d=eval(items);
        assertEquals(Decision.State.IN_STOCK,d.state); assertEquals(Decision.Action.SELECT_TIER,d.action);
    }
    @Test public void wrappedCityAndEventLinesTogetherVerifyLocalTitle() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.set(0,new UiItem("演出测试",30,100,500,150,true,false,false,UiItem.Source.OCR));
        items.add(new UiItem("巡回演唱会－杭州",30,155,500,195,true,false,false,UiItem.Source.OCR));
        assertEquals(Decision.Action.SELECT_TIER,eval(items).action);
    }
    @Test public void fullAccessibilityTitleCanContainAnOcrContinuation() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.set(0,new UiItem("杭州 演出测试\n就在日落以后\n巡回演唱会",20,20,530,195,true,false,false,UiItem.Source.NODE));
        items.add(new UiItem("巡回演唱会",30,155,500,190,true,false,false,UiItem.Source.OCR));
        assertEquals(Decision.Action.SELECT_TIER,eval(items).action);
    }
    @Test public void separateOtherEventTitleStillBlocksPositiveStock() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.add(new UiItem("另一个歌手演唱会－南京",30,400,500,450,true,false,false,UiItem.Source.OCR));
        Decision d=eval(items);
        assertEquals("conflicting_event_title",d.reason); assertEquals(Decision.Action.NONE,d.action);
    }
    @Test public void horizontallySeparateTitleIsNotAWrappedContinuation() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.add(new UiItem("另一场音乐节",650,155,1050,195,true,false,false,UiItem.Source.OCR));
        assertEquals("conflicting_event_title",eval(items).reason);
    }
    @Test public void ScreenSizedAggregateNodeCannotExcuseAnotherTitle() {
        List<UiItem> items=sku(tier("1380元 有票",700,false));
        items.add(new UiItem("另一场音乐节",30,400,500,450,true,false,false,UiItem.Source.OCR));
        items.add(new UiItem("杭州 演出测试 另一场音乐节",0,0,1260,2772,true,false,false,UiItem.Source.NODE));
        assertEquals("conflicting_event_title",eval(items).reason);
    }
}
