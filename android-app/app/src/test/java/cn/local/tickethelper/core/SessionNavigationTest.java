package cn.local.tickethelper.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic public structure exercises bounded navigation, never a server refresh or order. */
public final class SessionNavigationTest {
    private static final String TITLE = "[长沙]演出测试2026巡回演唱会";
    private static final String ID = "cn.damai:id/";
    private final long now = Instant.parse("2026-10-29T06:00:00Z").toEpochMilli();
    private final MutableClock clock = new MutableClock(now);

    private MonitorConfig config() {
        return new MonitorConfig("演出测试", "长沙", "2026-11-06", "19:00", List.of(1680), 2, 3360,
                Instant.parse("2026-11-06T11:00:00Z"), 500, 1500, MonitorConfig.Mode.PREPARE);
    }
    private UiItem item(String text, int left, int top, int right, int bottom, boolean selected, UiItem.Source source, String id) {
        return new UiItem(text, left, top, right, bottom, true, source == UiItem.Source.NODE,
                selected, source, id.isEmpty() ? "" : ID + id);
    }
    private UiItem node(String text, int y, String id) { return item(text, 20, y, 680, y + 50, false, UiItem.Source.NODE, id); }
    private List<UiItem> sku() {
        return new ArrayList<>(List.of(node(TITLE, 100, "tv_project_name"),
                item("", 0, 200, 700, 600, false, UiItem.Source.NODE, "project_detail_perform_flowlayout"),
                item("", 20, 220, 320, 300, false, UiItem.Source.NODE, "ll_perform_item"),
                item("", 20, 350, 320, 430, false, UiItem.Source.NODE, "ll_perform_item"),
                item("2026-11-06 19:00 无票", 20, 220, 320, 300, false, UiItem.Source.OCR, ""),
                item("2026-11-08 19:00 无票", 20, 350, 320, 430, false, UiItem.Source.OCR, "")));
    }
    private List<UiItem> detail(String title, String entry) {
        return new ArrayList<>(List.of(node(title, 100, "tv_project_name"),
                node("时间 2026.11.06/11.08", 200, "project_date"), node(entry, 1000, "buy_button")));
    }
    private Observation observation(List<UiItem> items, Observation.VerifiedSelection evidence) {
        return new Observation("cn.damai", items, clock.millis(), evidence);
    }
    private Observation.VerifiedSelection unavailable() {
        return new Observation.VerifiedSelection(clock.millis(), "2026-11-06", "19:00", null, null, null, null, null, true);
    }
    private Decision back(DecisionEngine engine) {
        Decision result = engine.evaluate(config(), observation(sku(), unavailable()), 0);
        assertEquals(Decision.Action.BACK, result.action); return result;
    }

    @Test public void initialSessionOnlyPageSelectsOnlyConfiguredNativeBoundCellEvenWhenNegative() {
        List<UiItem> items = sku();
        Decision result = new DecisionEngine(clock).evaluate(config(), observation(items, null), 0);
        assertEquals(Decision.State.PROBING, result.state); assertEquals(Decision.Action.SELECT_SESSION, result.action);
        assertSame(items.get(4), result.target); assertEquals("target_session_cell_verified", result.reason);
        assertNull(result.selectionEvidence);
    }
    @Test public void dateElsewhereOrFragmentWithoutNativeCellDoesNotAuthorizeSelection() {
        for (boolean removeCell : List.of(false, true)) {
            List<UiItem> items = sku();
            if (removeCell) items.remove(2);
            else items.set(4, item("2026-11-06 19:00 无票", 25, 225, 315, 290, false, UiItem.Source.OCR, ""));
            Decision result = new DecisionEngine(clock).evaluate(config(), observation(items, null), 0);
            assertEquals(Decision.State.UNKNOWN, result.state); assertEquals(Decision.Action.NONE, result.action);
        }
    }
    @Test public void duplicateTargetCellsOrSelectedWrongSessionFailClosed() {
        List<UiItem> duplicate = sku(); duplicate.add(duplicate.get(2));
        assertEquals(Decision.Action.NONE, new DecisionEngine(clock).evaluate(config(), observation(duplicate, null), 0).action);
        List<UiItem> selected = sku();
        selected.set(3, item("2026-11-08 19:00 无票", 20, 350, 320, 430, true, UiItem.Source.NODE, "ll_perform_item"));
        assertEquals(Decision.Action.NONE, new DecisionEngine(clock).evaluate(config(), observation(selected, null), 0).action);
    }
    @Test public void staleOrOtherProjectSessionPageCannotProposeSelection() {
        List<UiItem> items = sku(); items.set(0, node("[杭州]演出测试2026巡回演唱会", 100, "tv_project_name"));
        assertEquals(Decision.Action.NONE, new DecisionEngine(clock).evaluate(config(), observation(items, null), 0).action);
        Observation stale = new Observation("cn.damai", sku(), now - 3001);
        assertEquals(Decision.Action.NONE, new DecisionEngine(clock).evaluate(config(), stale, 0).action);
    }
    @Test public void explicitStartOpensDetailOnceWithoutInventingSessionEvidence() {
        DecisionEngine engine = new DecisionEngine(clock);
        engine.allowInitialDetailNavigation(config());
        Decision open = engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0);
        assertEquals(Decision.Action.OPEN_TICKETS, open.action);
        assertEquals("verified_initial_detail_entry", open.reason);
        engine.recordAction(open, now); clock.at += 200;
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
        List<UiItem> noTitle = sku(); noTitle.remove(0);
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(noTitle, null), 0).action);
    }
    @Test public void initialNavigationRejectsWrongTimeModalAndWrongCity() {
        for (String extra : List.of("20:00", "确认登记", "验证码", "确认购买")) {
            DecisionEngine engine = new DecisionEngine(clock); engine.allowInitialDetailNavigation(config());
            List<UiItem> page = detail(TITLE, "立即购买"); page.add(node(extra, 500, "extra"));
            assertNotEquals(Decision.Action.OPEN_TICKETS, engine.evaluate(config(), observation(page, null), 0).action);
        }
        DecisionEngine engine = new DecisionEngine(clock); engine.allowInitialDetailNavigation(config());
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail("[杭州]演出测试2026巡回演唱会", "立即购买"), null), 0).action);
        engine.reset();
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
    }
    @Test public void movingDecorationsDoNotValidateInventoryOrChangedEntry() {
        DecisionEngine engine = new DecisionEngine(clock); engine.allowInitialDetailNavigation(config());
        List<UiItem> a = detail(TITLE, "缺货登记");
        a.set(2, item("缺货登记", 200, 1000, 500, 1040, false, UiItem.Source.OCR, ""));
        a.add(node("弹幕甲", 50, "comment"));
        Decision first = engine.evaluate(config(), observation(a, null), 0);
        clock.at += 450;
        List<UiItem> b = new ArrayList<>(a); b.set(3, node("弹幕乙", 50, "comment"));
        Decision second = engine.evaluate(config(), observation(b, null), 0);
        assertTrue(DetailNavigationRead.agrees(config(), first, a, second, b, clock.millis()));
        b.add(node("确认登记", 600, "modal"));
        assertFalse(DetailNavigationRead.agrees(config(), first, a, second, b, clock.millis()));
        b.remove(b.size()-1);
        Decision shifted = new Decision(second.state, second.action,
                item("缺货登记", 201, 1000, 501, 1040, false, UiItem.Source.OCR, ""), second.reason, second.observedAtMs);
        assertFalse(DetailNavigationRead.agrees(config(), first, a, shifted, b, clock.millis()));
        assertFalse(DetailNavigationRead.agrees(config(), first, a, second, b, now + 3000));
        assertFalse(DetailNavigationRead.agrees(config(), first, a,
                new Decision(Decision.State.IN_STOCK, Decision.Action.SELECT_TIER, second.target, second.reason, second.observedAtMs), b, clock.millis()));
    }
    @Test public void firstUnknownDetailCannotUseMissingTimeOrUnexecutedBackAsAuthority() {
        DecisionEngine engine = new DecisionEngine(clock);
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
        back(engine); // proposal only; no dispatched BACK
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
    }
    @Test public void successfulVerifiedBackAllowsOneSameProjectDetailEntryThenFreshSessionSelection() {
        DecisionEngine engine = new DecisionEngine(clock); Decision back = back(engine);
        engine.recordAction(back, now); clock.at = now + 600;
        Decision open = engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), now);
        assertEquals(Decision.Action.OPEN_TICKETS, open.action); assertEquals("verified_return_detail_entry", open.reason);
        engine.recordAction(open, clock.millis()); clock.at += 600;
        Decision session = engine.evaluate(config(), observation(sku(), null), clock.millis() - 600);
        assertEquals(Decision.Action.SELECT_SESSION, session.action);
        // The return capability is consumed and cannot reopen a second detail without a new verified BACK.
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
    }
    @Test public void returnContextWaitsIntervalAndExpiresAtShortBound() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now);
        clock.at += 100;
        Decision waiting = engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), now);
        assertEquals(Decision.Action.NONE, waiting.action); assertEquals("waiting_for_interval", waiting.reason);
        clock.at = now + 30_000;
        assertEquals(Decision.Action.OPEN_TICKETS, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
        clock.at = now + 30_001;
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
    }
    @Test public void correctVisibleTimeStillUsesRestrictedReturnFooterContract() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        List<UiItem> detail = detail(TITLE, "缺货登记"); detail.add(node("19:00", 300, "perform_time"));
        detail.set(2, item("缺货登记", 200, 1000, 500, 1040, false, UiItem.Source.OCR, ""));
        Decision result = engine.evaluate(config(), observation(detail, null), now);
        assertEquals("verified_return_detail_entry", result.reason); assertTrue(DetailOcrNavigation.isProposal(result));
    }
    @Test public void longUserCycleDoesNotDelayPairedReentryOrPermitEarlyNextRefresh() {
        MonitorConfig longCycle = new MonitorConfig("演出测试", "长沙", "2026-11-06", "19:00", List.of(1680), 2, 3360,
                Instant.parse("2026-11-06T11:00:00Z"), 60_000, 90_000, MonitorConfig.Mode.PREPARE);
        DecisionEngine engine = new DecisionEngine(clock);
        Decision back = engine.evaluate(longCycle, observation(sku(), unavailable()), 0);
        assertEquals(Decision.Action.BACK, back.action); engine.recordAction(back, now);
        clock.at += 149;
        assertEquals(Decision.Action.NONE, engine.evaluate(longCycle, observation(detail(TITLE, "立即购买"), null), now).action);
        clock.at++;
        Decision open = engine.evaluate(longCycle, observation(detail(TITLE, "立即购买"), null), now);
        assertEquals(Decision.Action.OPEN_TICKETS, open.action); engine.recordAction(open, clock.millis());
        clock.at = now + 59_999;
        assertEquals(Decision.Action.NONE, engine.evaluate(longCycle, observation(sku(), unavailable()), now).action);
        clock.at++;
        assertEquals(Decision.Action.BACK, engine.evaluate(longCycle, observation(sku(), unavailable()), now).action);
    }
    @Test public void incompleteNativePassDoesNotConsumePermitBeforeSameFrameOcr() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        List<UiItem> nativeOnly = detail(TITLE, "立即购买"); nativeOnly.remove(1);
        assertEquals(Decision.State.UNKNOWN, engine.evaluate(config(), observation(nativeOnly, null), 0).state);
        assertEquals(Decision.Action.OPEN_TICKETS,
                engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
    }
    @Test public void sameProjectReturnCanProposeUniqueOcrFooterOnlyAfterExecutedBack() {
        List<UiItem> detail = detail(TITLE, "缺货登记");
        detail.set(2, item("缺货登记", 200, 1000, 500, 1040, false, UiItem.Source.OCR, ""));
        DecisionEngine engine = new DecisionEngine(clock);
        assertFalse(DetailOcrNavigation.isProposal(engine.evaluate(config(), observation(detail, null), 0)));
        engine.recordAction(back(engine), now); clock.at += 600;
        Decision result = engine.evaluate(config(), observation(detail, null), 0);
        assertEquals(Decision.Action.OPEN_TICKETS, result.action); assertTrue(DetailOcrNavigation.isProposal(result));
    }
    @Test public void returnCannotProposeDuplicateOcrLabelOrRegistrationModal() {
        for (String extra : List.of("缺货登记", "确认登记", "选择观演人")) {
            DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at = now + 600;
            List<UiItem> detail = detail(TITLE, "缺货登记");
            detail.set(2, item("缺货登记", 200, 1000, 500, 1040, false, UiItem.Source.OCR, ""));
            detail.add(item(extra, 100, 600, 500, 640, false, UiItem.Source.OCR, ""));
            Decision result = engine.evaluate(config(), observation(detail, null), 0);
            assertEquals(Decision.Action.NONE, result.action); assertFalse(DetailOcrNavigation.isProposal(result)); clock.at = now;
        }
    }
    @Test public void staleBackOrArbitraryBackCannotCreateReturnCapability() {
        DecisionEngine engine = new DecisionEngine(clock); Decision old = back(engine);
        engine.evaluate(config(), observation(sku(), null), 0); engine.recordAction(old, now);
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
        engine.recordAction(new Decision(Decision.State.READY, Decision.Action.BACK, null, "other", now), now);
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
    }
    @Test public void changedCompleteTitleCityOrExplicitWrongTimeInvalidatesNavigation() {
        for (String title : List.of("[长沙]演出测试另一巡回演唱会", "[杭州]演出测试2026巡回演唱会")) {
            DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at = now + 600;
            assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(title, "立即购买"), null), 0).action);
            assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
            clock.at = now;
        }
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        List<UiItem> wrongTime = detail(TITLE, "立即购买"); wrongTime.add(node("20:00", 300, "other_time"));
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(wrongTime, null), 0).action);
    }
    @Test public void reentryNeverProvidesInheritedPurchaseScopeAfterSelectorOpen() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        Decision open = engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), now);
        engine.recordAction(open, clock.millis()); clock.at += 600;
        List<UiItem> noTitle = sku(); noTitle.remove(0);
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(noTitle, null), 0).action);
    }
    @Test public void configurationChangeInvalidatesReturnContext() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        MonitorConfig other = new MonitorConfig("演出测试", "长沙", "2026-11-08", "19:00", List.of(1680), 1, 1680,
                Instant.parse("2026-11-08T11:00:00Z"), 500, 1500, MonitorConfig.Mode.PREPARE);
        assertEquals(Decision.Action.NONE, engine.evaluate(other, observation(detail(TITLE, "立即购买"), null), 0).action);
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
    }

    private List<UiItem> ocrDetail() {
        return new ArrayList<>(List.of(item(TITLE, 20, 100, 680, 150, false, UiItem.Source.OCR, ""),
                item("时间 2026.11.06/11.08", 20, 200, 680, 250, false, UiItem.Source.OCR, ""),
                item("缺货登记", 200, 1000, 500, 1040, false, UiItem.Source.OCR, "")));
    }
    @Test public void detailScrollRequiresExecutedBackAndPreservesOriginalOcrTimestamp() {
        DecisionEngine engine = new DecisionEngine(clock);
        assertNull(engine.prepareDetailScroll(config(), observation(ocrDetail(), null)));
        back(engine); assertNull(engine.prepareDetailScroll(config(), observation(ocrDetail(), null)));
        engine.recordAction(back(engine), now); clock.at += 600;
        long frameAt = clock.millis(); Observation frame = observation(ocrDetail(), null); clock.at += 500;
        Decision scroll = engine.prepareDetailScroll(config(), frame);
        assertNotNull(scroll); assertEquals(Decision.Action.SCROLL_DETAIL, scroll.action);
        assertNull(scroll.target); assertNull(scroll.selectionEvidence); assertEquals(frameAt, scroll.observedAtMs);
        assertTrue(engine.consumeDetailScroll(scroll, config())); assertTrue(engine.detailScrollCurrent(scroll, config()));
    }
    @Test public void consumedScrollIsOneShotAndDoesNotRenewReturnOrObservationLifetimes() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        Decision scroll = engine.prepareDetailScroll(config(), observation(ocrDetail(), null));
        assertTrue(engine.consumeDetailScroll(scroll, config())); assertFalse(engine.consumeDetailScroll(scroll, config()));
        assertNull(engine.prepareDetailScroll(config(), observation(ocrDetail(), null)));
        clock.at += 3000; assertFalse(engine.detailScrollCurrent(scroll, config()));
        engine.finishDetailScroll(scroll); assertFalse(engine.detailScrollCurrent(scroll, config()));
        // Repeated accepted native/combined reads cannot extend the original BACK's 30 seconds.
        clock.at = now + 29_000; engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0);
        clock.at = now + 30_001;
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
        assertNull(engine.prepareDetailScroll(config(), observation(ocrDetail(), null)));
    }
    @Test public void scrollNearExpiryCannotExtendThirtySecondPermit() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at = now + 29_900;
        Decision scroll = engine.prepareDetailScroll(config(), observation(ocrDetail(), null));
        assertNotNull(scroll); assertTrue(engine.consumeDetailScroll(scroll, config())); clock.at += 101;
        assertFalse(engine.detailScrollCurrent(scroll, config()));
    }
    @Test public void scrollRejectsWrongProjectStaleFrameSkuAndDecodedModal() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        for (String modal : List.of("安全验证", "请登录", "确认登记", "取消", "确认购买", "待付款", "选择场次", "票档")) {
            List<UiItem> items = ocrDetail(); items.add(node(modal, 500, "text"));
            assertNull(engine.prepareDetailScroll(config(), observation(items, null)));
        }
        List<UiItem> wrongProject = ocrDetail(); wrongProject.set(0, node("[长沙]另一场演唱会", 100, "tv_project_name"));
        assertNull(engine.prepareDetailScroll(config(), observation(wrongProject, null)));
        assertNull(engine.prepareDetailScroll(config(), new Observation("cn.damai", ocrDetail(), clock.millis() - 3000)));
        assertNull(engine.prepareDetailScroll(config(), observation(sku(), null)));
    }
    @Test public void scrollFinishDoesNotReplaceRequiredFreshDetailIdentityForClicks() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        Decision scroll = engine.prepareDetailScroll(config(), observation(ocrDetail(), null));
        assertTrue(engine.consumeDetailScroll(scroll, config())); engine.finishDetailScroll(scroll); clock.at += 600;
        List<UiItem> noTitle = detail(TITLE, "立即购买"); noTitle.remove(0);
        assertEquals(Decision.Action.NONE, engine.evaluate(config(), observation(noTitle, null), 0).action);
        assertEquals(Decision.Action.OPEN_TICKETS, engine.evaluate(config(), observation(detail(TITLE, "立即购买"), null), 0).action);
    }
    @Test public void ordinaryScrollDecisionCannotConsumeBackPermit() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        Decision fake = new Decision(Decision.State.READY, Decision.Action.SCROLL_DETAIL, null, "verified_return_detail_settle", clock.millis());
        assertFalse(engine.consumeDetailScroll(fake, config())); assertFalse(engine.detailScrollCurrent(fake, config()));
    }
    @Test public void onlyTwoCertainUnsentNativeChangesCanObtainEntirelyNewOcrProposals() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now); clock.at += 600;
        Observation oldFrame = observation(ocrDetail(), null);
        Decision scroll = engine.prepareDetailScroll(config(), oldFrame); assertTrue(engine.consumeDetailScroll(scroll, config()));
        for (int retry=0;retry<2;retry++) {
            assertTrue(engine.retryUnsentDetailScroll(scroll,config(),false,false,"dispatch_guard_rejected",
                    InputDispatchPolicy.Rejection.SCROLL_NATIVE_CHANGED,DetailScrollPolicy.FrameCheck.NATIVE_TEXT_CHANGED));
            assertFalse(engine.detailScrollCurrent(scroll,config()));
            assertNull(engine.prepareDetailScroll(config(),oldFrame));
            clock.at += 350; oldFrame=observation(ocrDetail(),null);
            scroll=engine.prepareDetailScroll(config(),oldFrame);assertNotNull(scroll);assertTrue(engine.consumeDetailScroll(scroll,config()));
        }
        assertFalse(engine.retryUnsentDetailScroll(scroll,config(),false,false,"dispatch_guard_rejected",
                InputDispatchPolicy.Rejection.SCROLL_NATIVE_CHANGED,DetailScrollPolicy.FrameCheck.NATIVE_TEXT_CHANGED));
        engine.finishDetailScroll(scroll); assertNull(engine.prepareDetailScroll(config(),observation(ocrDetail(),null)));
    }
    @Test public void unknownCompletedBoundaryAndNonServiceFailuresNeverRestoreScrollBudget() {
        DecisionEngine engine = new DecisionEngine(clock); engine.recordAction(back(engine), now);clock.at+=600;
        Decision scroll=engine.prepareDetailScroll(config(),observation(ocrDetail(),null));assertTrue(engine.consumeDetailScroll(scroll,config()));
        assertFalse(engine.retryUnsentDetailScroll(scroll,config(),false,true,"dispatch_guard_rejected",InputDispatchPolicy.Rejection.SCROLL_NATIVE_CHANGED,DetailScrollPolicy.FrameCheck.NATIVE_TEXT_CHANGED));
        assertFalse(engine.retryUnsentDetailScroll(scroll,config(),true,false,"command_completed",InputDispatchPolicy.Rejection.SCROLL_NATIVE_CHANGED,DetailScrollPolicy.FrameCheck.NATIVE_TEXT_CHANGED));
        assertFalse(engine.retryUnsentDetailScroll(scroll,config(),false,false,"gesture_guard_rejected",InputDispatchPolicy.Rejection.SCROLL_NATIVE_CHANGED,DetailScrollPolicy.FrameCheck.NATIVE_TEXT_CHANGED));
        assertFalse(engine.retryUnsentDetailScroll(scroll,config(),false,false,"dispatch_guard_rejected",InputDispatchPolicy.Rejection.PAGE_BOUNDARY_CHANGED,DetailScrollPolicy.FrameCheck.NATIVE_TEXT_CHANGED));
        assertFalse(engine.retryUnsentDetailScroll(scroll,config(),false,false,"dispatch_guard_rejected",InputDispatchPolicy.Rejection.SCROLL_NATIVE_CHANGED,DetailScrollPolicy.FrameCheck.BOUNDARY_CHANGED));
        assertNull(engine.prepareDetailScroll(config(),observation(ocrDetail(),null)));
    }
    @Test public void certainUnsentRetryNeverExtendsOriginalThirtySecondExpiry() {
        DecisionEngine engine = new DecisionEngine(clock);engine.recordAction(back(engine),now);clock.at=now+29_900;
        Decision scroll=engine.prepareDetailScroll(config(),observation(ocrDetail(),null));assertTrue(engine.consumeDetailScroll(scroll,config()));
        assertTrue(engine.retryUnsentDetailScroll(scroll,config(),false,false,"dispatch_guard_rejected",InputDispatchPolicy.Rejection.SCROLL_NATIVE_CHANGED,DetailScrollPolicy.FrameCheck.NATIVE_FLAGS_CHANGED));
        clock.at=now+30_001;assertNull(engine.prepareDetailScroll(config(),observation(ocrDetail(),null)));
    }

    private static final class MutableClock extends Clock {
        long at; MutableClock(long at) { this.at = at; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(at); }
    }
}
