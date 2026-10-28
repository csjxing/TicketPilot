package cn.local.tickethelper.adapter;

import cn.local.tickethelper.core.UiItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class DamaiSkuAdapterTest {
    private static final String ID = "cn.damai:id/";
    private static final int ORANGE = 0xffff5500, BLACK = 0xff222222;
    private Frame frame;
    private List<UiItem> nodes, lines;
    private UiItem selectedDate, selectedPrice, confirm;

    @Before public void setup() {
        frame = new Frame(); nodes = new ArrayList<>(); lines = new ArrayList<>();
        nodes.add(node("project_detail_perform_flowlayout", "", 20, 100, 580, 310, false));
        nodes.add(node("project_detail_perform_price_flowlayout", "", 20, 350, 580, 650, false));
        UiItem oldDate = node("ll_perform_item", "", 20, 100, 360, 180, false);
        selectedDate = node("ll_perform_item", "", 20, 200, 360, 280, false);
        selectedPrice = node("ll_perform_item", "", 300, 350, 560, 440, false);
        nodes.add(oldDate); nodes.add(selectedDate); nodes.add(selectedPrice);
        addText("2026-11-14周六19:00", 40, 120, 280, 150, BLACK);
        addText("2026-11-15周日19:00", 40, 220, 280, 250, ORANGE);
        addText("看台980元", 320, 375, 520, 405, ORANGE);
        frame.border(selectedDate, ORANGE); frame.border(selectedPrice, ORANGE);
        nodes.add(node("bottom_layout", "", 0, 700, 600, 980, false));
        nodes.add(node("layout_num", "", 0, 700, 600, 820, false));
        nodes.add(node("tv_limit_num_title", "数量", 20, 720, 80, 750, false));
        nodes.add(node("tv_num", "1张", 400, 740, 460, 775, false));
        nodes.add(node("tv_sku_price_prefix", "¥", 20, 880, 35, 920, false));
        nodes.add(node("tv_price", "980", 35, 880, 100, 920, false));
        confirm = node("btn_buy_view", "", 350, 850, 580, 950, true); nodes.add(confirm);
        addText("确定", 400, 880, 500, 920, BLACK);
        addText("1张", 404, 745, 456, 770, BLACK);
        addText("¥980 价格明细", 20, 880, 220, 920, ORANGE);
    }
    private DamaiSkuAdapter.Result adapt() { return DamaiSkuAdapter.adapt(nodes, lines, frame, 1234); }

    @Test public void sameFrameOrangeSelectionAndRealBottomNodesVerify() {
        DamaiSkuAdapter.Result r = adapt(); assertNotNull(r.evidence);
        assertEquals("2026-11-15", r.evidence.date); assertEquals("19:00", r.evidence.time);
        assertEquals(Integer.valueOf(980), r.evidence.selectedPrice);
        assertEquals(Integer.valueOf(1), r.evidence.quantity); assertEquals(Integer.valueOf(980), r.evidence.totalYuan);
        assertSame(confirm, r.evidence.confirmTarget); assertEquals(1234, r.evidence.observedAtMs);
        assertFalse(r.evidence.sessionUnavailable);
        assertTrue(r.items.stream().allMatch(i -> i.source == UiItem.Source.OCR));
        assertTrue(r.items.stream().noneMatch(i -> i.selected));
    }
    @Test public void orangeBadgeWithoutBorderDoesNotSelectDate() {
        frame.clear(selectedDate); frame.text(lines.get(1), BLACK);
        frame.fill(300, 220, 330, 245, ORANGE); assertNull(adapt().evidence);
    }
    @Test public void orangeBorderWithBlackSemanticTextDoesNotSelect() {
        frame.clear(selectedDate); frame.border(selectedDate, ORANGE); frame.text(lines.get(1), BLACK);
        assertNull(adapt().evidence);
    }
    @Test public void blackDateAndLargeOrangeBadgeInSameOcrLineDoesNotSelect() {
        frame.clear(selectedDate); frame.border(selectedDate, ORANGE); frame.text(lines.get(1), BLACK);
        frame.fill(265, 225, 279, 235, ORANGE); assertNull(adapt().evidence);
    }
    @Test public void oneMissingBorderStopsSelection() {
        frame.fill(selectedDate.left, selectedDate.top, selectedDate.right, selectedDate.top + 4, 0xffffffff);
        assertNull(adapt().evidence);
    }
    @Test public void filledOrangeButtonCannotLookLikeSelectedCell() {
        frame.fill(selectedPrice.left, selectedPrice.top, selectedPrice.right, selectedPrice.bottom, ORANGE);
        assertNull(adapt().evidence.selectedPrice);
    }
    @Test public void multipleSelectedDatesFailClosed() {
        UiItem other = nodes.get(2); frame.border(other, ORANGE); frame.clear(lines.get(0)); frame.text(lines.get(0), ORANGE);
        assertNull(adapt().evidence);
    }
    @Test public void quantityTwoIsPreservedNotCoercedToOne() {
        replaceNode("tv_num", "2张"); replaceNode("tv_price", "1960");
        lines.removeIf(i -> i.text.equals("1张") || i.text.contains("价格明细"));
        addText("2张", 404, 745, 456, 770, BLACK); addText("¥1960 价格明细", 20, 880, 220, 920, ORANGE);
        assertEquals(Integer.valueOf(2), adapt().evidence.quantity);
        assertEquals(Integer.valueOf(1960), adapt().evidence.totalYuan);
    }
    @Test public void twoTicketsAt1680RetainSameFramePriceQuantityAnd3360Total() {
        replaceNode("tv_num", "2张"); replaceNode("tv_price", "3360");
        lines.removeIf(i -> i.text.equals("1张") || i.text.contains("价格明细") || i.text.equals("看台980元"));
        addText("内场1680元", 320, 375, 520, 405, ORANGE);
        addText("2张", 404, 745, 456, 770, BLACK); addText("¥3360 价格明细", 20, 880, 220, 920, ORANGE);
        DamaiSkuAdapter.Result result = adapt();
        assertNotNull(result.evidence);
        assertEquals(Integer.valueOf(1680), result.evidence.selectedPrice);
        assertEquals(Integer.valueOf(2), result.evidence.quantity);
        assertEquals(Integer.valueOf(3360), result.evidence.totalYuan);
        assertSame(confirm, result.evidence.confirmTarget);
    }
    @Test public void quantityMustBeWithinNamedQuantityContainer() {
        nodes.removeIf(i -> i.resourceId.equals(ID + "layout_num")); assertNull(adapt().evidence.quantity);
    }
    @Test public void currencyPrefixRequiredForTotal() {
        nodes.removeIf(i -> i.resourceId.equals(ID + "tv_sku_price_prefix")); assertNull(adapt().evidence.totalYuan);
    }
    @Test public void fractionalTotalIsNotRounded() {
        replaceNode("tv_price", "980.50"); assertNull(adapt().evidence.totalYuan);
    }
    @Test public void buttonRequiresClickableOriginalNode() {
        nodes.remove(confirm); nodes.add(node("btn_buy_view", "", 350, 850, 580, 950, false));
        assertNull(adapt().evidence.confirmTarget);
    }
    @Test public void confirmOcrOutsideButtonIsNotMapped() {
        lines.removeIf(i -> i.text.equals("确定")); addText("确定", 100, 860, 200, 900, BLACK);
        assertNull(adapt().evidence.confirmTarget);
    }
    @Test public void sameCellNegativeTagIsRetainedAndPreventsSelectedPriceEvidence() {
        nodes.add(node("tv_tag", "缺货登记", 470, 410, 555, 435, false));
        assertNull(adapt().evidence.selectedPrice);
        assertTrue(adapt().items.stream().anyMatch(i -> i.text.contains("980元") && i.text.contains("缺货登记")));
    }
    @Test public void unscopedOrangePriceCannotBeSelectedTier() {
        nodes.removeIf(i -> i.resourceId.equals(ID + "project_detail_perform_price_flowlayout"));
        assertNull(adapt().evidence);
    }
    @Test public void differentFrameTimestampIsNotInvented() {
        assertNull(DamaiSkuAdapter.adapt(nodes, lines, frame, 0).evidence);
    }
    @Test public void selectedPriceOutsidePriceFlowIsIgnored() {
        nodes.remove(selectedPrice);
        nodes.add(node("ll_perform_item", "", 300, 660, 560, 750, false));
        assertNull(adapt().evidence.selectedPrice);
    }

    @Test public void staleQuantityNodeConflictingWithScreenshotIsNotVerified() {
        lines.removeIf(i -> i.text.equals("1张")); addText("2张", 404, 745, 456, 770, BLACK);
        assertNull(adapt().evidence.quantity);
    }
    @Test public void staleTotalNodeConflictingWithScreenshotIsNotVerified() {
        lines.removeIf(i -> i.text.contains("价格明细")); addText("¥1960 价格明细", 20, 880, 220, 920, ORANGE);
        assertNull(adapt().evidence.totalYuan);
    }
    @Test public void absentQuantityOcrDoesNotTrustNodeAlone() {
        lines.removeIf(i -> i.text.equals("1张")); assertNull(adapt().evidence.quantity);
    }

    @Test public void closedTotalDiagnosticDistinguishesMissingNativeFields() {
        nodes.removeIf(i -> i.resourceId.equals(ID + "bottom_layout"));
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.BOTTOM_MISSING);
        setup(); nodes.removeIf(i -> i.resourceId.equals(ID + "tv_price"));
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.AMOUNT_MISSING);
        setup(); nodes.removeIf(i -> i.resourceId.equals(ID + "tv_sku_price_prefix"));
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.CURRENCY_MISSING);
    }
    @Test public void closedTotalDiagnosticDistinguishesInvalidNativeEvidence() {
        replaceNode("tv_sku_price_prefix", "$");
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.NATIVE_SHAPE_INVALID);
        setup(); replaceNode("tv_price", "980.50");
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.NATIVE_AMOUNT_INVALID);
    }
    @Test public void closedTotalDiagnosticDistinguishesOcrGeometry() {
        lines.removeIf(i -> i.text.contains("价格明细") || i.text.equals("确定"));
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.OCR_NO_OVERLAP);
        addText("¥980 价格明细", 150, 880, 500, 920, ORANGE);
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.OCR_GEOMETRY_MISMATCH);
    }
    @Test public void closedTotalDiagnosticDistinguishesOcrMissingOrInvalidNumber() {
        lines.removeIf(i -> i.text.contains("价格明细"));
        addText("价格明细", 20, 880, 220, 920, ORANGE);
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.OCR_NUMBER_MISSING);
        lines.removeIf(i -> i.text.contains("价格明细"));
        addText("¥980.50 价格明细", 20, 880, 220, 920, ORANGE);
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.OCR_NUMBER_INVALID);
    }
    @Test public void closedTotalDiagnosticDistinguishesOcrMismatchAndMultipleAmounts() {
        lines.removeIf(i -> i.text.contains("价格明细"));
        addText("¥1960 价格明细", 20, 880, 220, 920, ORANGE);
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.OCR_VALUE_MISMATCH);
        lines.removeIf(i -> i.text.contains("价格明细"));
        addText("¥980 1960 价格明细", 20, 880, 220, 920, ORANGE);
        assertTotalCode(DamaiSkuAdapter.TotalCheckCode.OCR_MULTIPLE_VALUES);
    }
    @Test public void closedTotalDiagnosticRetainsOriginalSuccessfulEvidence() {
        DamaiSkuAdapter.Result result = adapt();
        assertEquals(DamaiSkuAdapter.TotalCheckCode.VERIFIED, result.totalCheckCode);
        assertEquals(Integer.valueOf(980), result.evidence.totalYuan);
    }
    private void assertTotalCode(DamaiSkuAdapter.TotalCheckCode code) {
        DamaiSkuAdapter.Result result = adapt();
        assertNotNull(result.evidence); assertNull(result.evidence.totalYuan);
        assertEquals(code, result.totalCheckCode);
    }

    private DamaiSkuAdapter.Result adaptAmountElements(UiItem... elements) {
        return DamaiSkuAdapter.adapt(nodes, lines, Arrays.asList(elements), frame, 1234);
    }
    private static UiItem amountElement(String text, int left, int right) {
        return new UiItem(text, left, 884, right, 918, true, false, false, UiItem.Source.OCR);
    }
    @Test public void wordAmountVerifiesEvenWhenSameRowIncludesDistantConfirmButton() {
        lines.removeIf(i -> i.text.contains("价格明细"));
        addText("¥980 价格明细 确定", 20, 880, 580, 920, ORANGE);
        assertNull(adapt().evidence.totalYuan);
        DamaiSkuAdapter.Result result = adaptAmountElements(amountElement("¥980", 20, 100), amountElement("确定", 400, 500));
        assertEquals(Integer.valueOf(980), result.evidence.totalYuan);
        assertEquals(DamaiSkuAdapter.TotalCheckCode.VERIFIED, result.totalCheckCode);
        assertEquals(1, result.items.stream().filter(i -> i.text.contains("¥980")).count());
    }
    @Test public void wordAmountCanBeSeparateFromCurrencyWithoutEnteringGlobalItems() {
        DamaiSkuAdapter.Result result = adaptAmountElements(amountElement("¥", 20, 35), amountElement("980", 36, 100));
        assertEquals(Integer.valueOf(980), result.evidence.totalYuan);
        assertFalse(result.items.stream().anyMatch(i -> i.text.equals("980")));
    }
    @Test public void wordAmountRejectsConflictingOrFractionalValue() {
        DamaiSkuAdapter.Result conflict = adaptAmountElements(amountElement("1960", 35, 100));
        assertNull(conflict.evidence.totalYuan);
        assertEquals(DamaiSkuAdapter.TotalCheckCode.OCR_VALUE_MISMATCH, conflict.totalCheckCode);
        DamaiSkuAdapter.Result fraction = adaptAmountElements(amountElement("980.5", 35, 100));
        assertNull(fraction.evidence.totalYuan);
        assertEquals(DamaiSkuAdapter.TotalCheckCode.OCR_NUMBER_INVALID, fraction.totalCheckCode);
    }
    @Test public void wordAmountRejectsMultipleNumbersWithinOneOrSeveralTokens() {
        assertNull(adaptAmountElements(amountElement("980 1960", 20, 100)).evidence.totalYuan);
        DamaiSkuAdapter.Result duplicate = adaptAmountElements(amountElement("980", 35, 100), amountElement("1960", 35, 100));
        assertNull(duplicate.evidence.totalYuan);
        assertEquals(DamaiSkuAdapter.TotalCheckCode.OCR_MULTIPLE_VALUES, duplicate.totalCheckCode);
    }
    @Test public void wordAmountOutsideOrCrossingNativeRegionCannotVerifyTotal() {
        assertNull(adaptAmountElements(amountElement("980", 150, 220)).evidence.totalYuan);
        DamaiSkuAdapter.Result crossing = adaptAmountElements(amountElement("980", 35, 200));
        assertNull(crossing.evidence.totalYuan);
        assertEquals(DamaiSkuAdapter.TotalCheckCode.OCR_GEOMETRY_MISMATCH, crossing.totalCheckCode);
        assertNull(adaptAmountElements(amountElement("980", 35, 100), amountElement("1960", 35, 200)).evidence.totalYuan);
    }
    @Test public void wordAmountRequiresDedicatedEvidenceAndWholeNumericToken() {
        assertNull(adaptAmountElements().evidence.totalYuan);
        assertNull(adaptAmountElements(amountElement("合计980", 20, 100)).evidence.totalYuan);
        assertNull(adaptAmountElements(amountElement("980元", 20, 100)).evidence.totalYuan);
        assertEquals(Integer.valueOf(980), adaptAmountElements(amountElement("￥980.00", 20, 100)).evidence.totalYuan);
    }
    @Test public void wordAmountDoesNotOverrideNativeAmountOrCurrencyRequirements() {
        replaceNode("tv_price", "1960");
        assertNull(adaptAmountElements(amountElement("980", 35, 100)).evidence.totalYuan);
        setup(); nodes.removeIf(i -> i.resourceId.equals(ID + "tv_sku_price_prefix"));
        assertNull(adaptAmountElements(amountElement("¥980", 20, 100)).evidence.totalYuan);
    }
    @Test public void localGeometryDiagnosticContainsOnlyBoundsAndClosedMetadata() {
        String report = DamaiSkuAdapter.totalGeometryDiagnostic(nodes,
                List.of(amountElement("¥980", 20, 100), amountElement("private-text", 140, 250)), 600);
        assertTrue(report.startsWith("geometry amount=[35,880,100,920] currency=[20,880,35,920]"));
        assertTrue(report.contains("chars=4 full=true equal=true kind=COMPLETE_NUMBER"));
        assertTrue(report.contains("full=false equal=false kind=OTHER"));
        assertFalse(report.contains("980")); assertFalse(report.contains("private-text"));
    }
    @Test public void localGeometryDiagnosticCapsFourElementsAndOmitsOtherRowsAndRightSide() {
        List<UiItem> elements = new ArrayList<>();
        for (int i = 0; i < 8; i++) elements.add(amountElement("text-that-must-never-appear", 20 + i * 10, 100 + i * 10));
        elements.add(amountElement("right-side-sentinel", 350, 500));
        elements.add(new UiItem("other-row-sentinel", 20, 740, 100, 775, true, false, false, UiItem.Source.OCR));
        String report = DamaiSkuAdapter.totalGeometryDiagnostic(nodes, elements, 600);
        assertTrue(report.contains(" e3=")); assertFalse(report.contains(" e4="));
        assertFalse(report.contains("sentinel")); assertFalse(report.contains("must-never"));
        assertFalse(report.contains("[350,")); assertFalse(report.contains(",740,"));
    }
    @Test public void localGeometryDiagnosticClassifiesCompleteMismatchAndMixedTokensWithoutTheirText() {
        String report = DamaiSkuAdapter.totalGeometryDiagnostic(nodes,
                List.of(amountElement("1960", 35, 100), amountElement("980价格明细", 110, 280)), 600);
        assertTrue(report.contains("chars=4 full=true equal=false kind=COMPLETE_NUMBER"));
        assertTrue(report.contains("full=false equal=false kind=MIXED_NUMBER"));
        assertFalse(report.contains("1960")); assertFalse(report.contains("980")); assertFalse(report.contains("价格"));
    }
    @Test public void localGeometryDiagnosticBoundsCharacterCountAndHandlesMissingNativeFields() {
        String report = DamaiSkuAdapter.totalGeometryDiagnostic(nodes,
                List.of(amountElement("x".repeat(2000), 20, 100)), 600);
        assertTrue(report.contains("chars=64")); assertTrue(report.length() < 350);
        nodes.removeIf(i -> i.resourceId.equals(ID + "tv_price"));
        assertEquals("geometry amount=missing currency=[20,880,35,920]",
                DamaiSkuAdapter.totalGeometryDiagnostic(nodes, List.of(amountElement("hidden", 20, 100)), 600));
    }

    @Test public void selectedDateExplicitlyWithoutTicketsPreservesOnlyNegativeSessionIdentity() {
        nodes.add(node("tv_tag", "无票", 300, 220, 350, 250, false));
        var evidence = adapt().evidence;
        assertNotNull(evidence); assertTrue(evidence.sessionUnavailable);
        assertEquals("2026-11-15", evidence.date); assertEquals("19:00", evidence.time);
        assertEquals(1234, evidence.observedAtMs);
        assertNull(evidence.selectedPrice); assertNull(evidence.quantity); assertNull(evidence.totalYuan);
        assertNull(evidence.confirmTarget); assertNull(evidence.quantityIncreaseTarget);
    }
    @Test public void catalogCanNameVisuallySelectedSoldOutSessionWithoutWeakeningMonitorGate() {
        nodes.add(node("tv_tag", "无票", 300, 220, 350, 250, false));
        var result = adapt(); assertNotNull(result.evidence); assertTrue(result.evidence.sessionUnavailable);
        assertNotNull(result.catalogSelection);
        assertEquals("2026-11-15",result.catalogSelection.date);
        assertEquals("19:00",result.catalogSelection.time);
        assertEquals(1234,result.catalogSelection.observedAtMs);
    }
    @Test public void unavailableSessionStillRequiresUniqueOrangeDateSelection() {
        nodes.add(node("tv_tag", "无票", 300, 220, 350, 250, false));
        UiItem other = nodes.get(2); frame.border(other, ORANGE); frame.text(lines.get(0), ORANGE);
        assertNull(adapt().evidence); assertNull(adapt().catalogSelection);
    }
    @Test public void unavailableTagOnUnselectedDateDoesNotMarkSelectedSessionUnavailable() {
        nodes.add(node("tv_tag", "无票", 300, 120, 350, 150, false));
        var evidence = adapt().evidence;
        assertNotNull(evidence); assertFalse(evidence.sessionUnavailable);
        assertEquals(Integer.valueOf(980), evidence.selectedPrice);
    }
    @Test public void unavailableTagCannotVerifyBlackSemanticTextOrMalformedDate() {
        nodes.add(node("tv_tag", "无票", 300, 220, 350, 250, false));
        frame.text(lines.get(1), BLACK); assertNull(adapt().evidence);
        setup(); nodes.add(node("tv_tag", "无票", 300, 220, 350, 250, false));
        lines.remove(1); addText("2026-11-35周日19:00", 40, 220, 280, 250, ORANGE);
        assertNull(adapt().evidence);
    }
    @Test public void sessionOnlySelectorMergesNativeScopedDateLabelsBeforePricesExist() {
        nodes.removeIf(i -> i.resourceId.equals(ID + "project_detail_perform_price_flowlayout")
                || i == selectedPrice || i.resourceId.equals(ID + "bottom_layout"));
        frame.clear(selectedDate); frame.text(lines.get(1), BLACK);
        DamaiSkuAdapter.Result result = adapt(); assertNull(result.evidence);
        assertTrue(result.items.stream().anyMatch(i -> i.text.contains("2026-11-15")
                && i.left == selectedDate.left && i.top == selectedDate.top
                && i.right == selectedDate.right && i.bottom == selectedDate.bottom));
    }
    @Test public void sessionOnlyNegativeSelectionPreservesIdentityWithoutPriceOrConfirmation() {
        nodes.removeIf(i -> i.resourceId.equals(ID + "project_detail_perform_price_flowlayout") || i == selectedPrice);
        nodes.add(node("tv_tag", "无票", 300, 220, 350, 250, false));
        var evidence = adapt().evidence;
        assertNotNull(evidence); assertTrue(evidence.sessionUnavailable);
        assertNull(evidence.selectedPrice); assertNull(evidence.quantity); assertNull(evidence.totalYuan);
        assertNull(evidence.confirmTarget); assertNull(evidence.quantityIncreaseTarget);
    }
    @Test public void catalogStillRequiresUniqueVisualSelection() {
        frame.clear(selectedDate); frame.text(lines.get(1), BLACK);
        assertNull(adapt().catalogSelection);
        frame.border(selectedDate, ORANGE); frame.text(lines.get(1), ORANGE);
        UiItem other = nodes.get(2); frame.border(other, ORANGE); frame.text(lines.get(0), ORANGE);
        assertNull(adapt().catalogSelection);
    }
    @Test public void nativeQueueErrorSuppressesBothMonitorAndCatalogSelectionEvidence() {
        nodes.add(node("state_view_tip", "抱歉，当前排队的人数太多啦，请稍后再试哦", 20, 660, 580, 690, false));
        var result=adapt(); assertNull(result.evidence); assertNull(result.catalogSelection);
        assertEquals(lines,result.items);
    }
    @Test public void ocrQueueErrorAlsoSuppressesOtherwiseValidSelection() {
        addText("当前排队的人数太多啦",20,660,580,690,BLACK);
        var result=adapt(); assertNull(result.evidence); assertNull(result.catalogSelection);
        assertTrue(result.items.stream().anyMatch(i -> i.text.contains("排队")));
    }
    @Test public void nativeErrorContainerCannotCarrySelectedSkuEvidence() {
        nodes.add(node("error_layout", "", 0, 100, 600, 980, false));
        nodes.add(node("state_view_tip", "暂时无法展示，请稍后再试", 20, 660, 580, 690, false));
        var result=adapt(); assertNull(result.evidence); assertNull(result.catalogSelection);
    }

    // These public IDs/order derive from historical manual quantity captures, not an automated live pass.
    private UiItem addQuantityStepper() {
        nodes.add(node("layout_sku_num_ll", "", 300, 730, 580, 805, false));
        nodes.add(new UiItem("", 310, 740, 360, 790, false, false, false, UiItem.Source.NODE, ID + "img_jian"));
        UiItem plus = node("img_jia", "", 500, 740, 550, 790, true); nodes.add(plus); return plus;
    }
    @Test public void exactScopedEnabledPlusCanProposeOneToTwoOnlyWithSameFrameFacts() {
        UiItem plus = addQuantityStepper(); assertSame(plus, adapt().evidence.quantityIncreaseTarget);
        lines.removeIf(i -> i.text.equals("1张")); assertNull(adapt().evidence.quantityIncreaseTarget);
    }
    @Test public void missingStepperOrDuplicatePlusDoesNotInventAnIncreaseControl() {
        UiItem plus = addQuantityStepper();
        nodes.removeIf(i -> i.resourceId.equals(ID + "layout_sku_num_ll")); assertNull(adapt().evidence.quantityIncreaseTarget);
        nodes.add(node("layout_sku_num_ll", "", 300, 730, 580, 805, false));nodes.add(plus);
        assertNull(adapt().evidence.quantityIncreaseTarget);
    }
    @Test public void disabledOrWronglyScopedPlusCannotBeUsed() {
        UiItem plus = addQuantityStepper(); nodes.remove(plus);
        nodes.add(new UiItem("",plus.left,plus.top,plus.right,plus.bottom,false,true,false,UiItem.Source.NODE,ID+"img_jia"));
        assertNull(adapt().evidence.quantityIncreaseTarget);
        nodes.removeIf(i -> i.resourceId.equals(ID+"img_jia"));nodes.add(node("img_jia","",500,820,550,870,true));
        assertNull(adapt().evidence.quantityIncreaseTarget);
    }
    @Test public void enabledMinusOrUnverifiedPriceAmountOrConfirmBlocksIncrease() {
        addQuantityStepper();nodes.removeIf(i -> i.resourceId.equals(ID+"img_jian"));
        nodes.add(node("img_jian","",310,740,360,790,true));assertNull(adapt().evidence.quantityIncreaseTarget);
        nodes.removeIf(i -> i.resourceId.equals(ID+"img_jian"));
        nodes.add(new UiItem("",310,740,360,790,false,false,false,UiItem.Source.NODE,ID+"img_jian"));
        replaceNode("tv_price","1960");assertNull(adapt().evidence.quantityIncreaseTarget);
        replaceNode("tv_price","980");nodes.remove(confirm);assertNull(adapt().evidence.quantityIncreaseTarget);
    }

    private void replaceNode(String id, String text) {
        for (int i = 0; i < nodes.size(); i++) { UiItem n = nodes.get(i); if (n.resourceId.equals(ID + id)) {
            nodes.set(i, new UiItem(text, n.left, n.top, n.right, n.bottom, n.enabled, n.clickable, n.selected, n.source, n.resourceId)); return;
        }}
        fail("Node missing");
    }
    private void addText(String value, int l, int t, int r, int b, int color) {
        UiItem item = new UiItem(value, l, t, r, b, true, false, false, UiItem.Source.OCR);
        lines.add(item); frame.text(item, color);
    }
    private static UiItem node(String id, String text, int l, int t, int r, int b, boolean clickable) {
        return new UiItem(text, l, t, r, b, true, clickable, false, UiItem.Source.NODE, ID + id);
    }
    private static final class Frame implements DamaiSkuAdapter.Pixels {
        final int[] data = new int[600 * 1000];
        Frame() { Arrays.fill(data, 0xffffffff); }
        public int width() { return 600; } public int height() { return 1000; }
        public int argb(int x, int y) { return data[y * 600 + x]; }
        void fill(int l, int t, int r, int b, int color) { for (int y = t; y < b; y++) for (int x = l; x < r; x++) data[y * 600 + x] = color; }
        void clear(UiItem b) { fill(b.left, b.top, b.right, b.bottom, 0xffffffff); }
        void border(UiItem b, int color) {
            fill(b.left, b.top, b.right, b.top + 3, color); fill(b.left, b.bottom - 3, b.right, b.bottom, color);
            fill(b.left, b.top, b.left + 3, b.bottom, color); fill(b.right - 3, b.top, b.right, b.bottom, color);
        }
        void text(UiItem b, int color) { for (int x = b.left + 3; x < b.right - 3; x += 8) fill(x, b.top + 5, x + 2, b.bottom - 5, color); }
    }
}
