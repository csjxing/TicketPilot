package cn.local.tickethelper.core;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ObservationMergeTest {
    private UiItem text(String text, UiItem.Source source) {
        return new UiItem(text, 200, 900, 500, 940, true, false, false, source);
    }
    @Test public void nativeFooterAppearingMustNotEraseIndependentOcrProof() {
        UiItem ocr = text("缺货登记", UiItem.Source.OCR);
        assertSame(ocr, DetailOcrNavigation.uniqueEntry(ObservationMerge.merge(List.of(), List.of(ocr))));
        assertSame(ocr, DetailOcrNavigation.uniqueEntry(ObservationMerge.merge(
                List.of(text("缺货登记", UiItem.Source.NODE)), List.of(ocr))));
        assertNull(DetailOcrNavigation.uniqueEntry(ObservationMerge.merge(
                List.of(text("缺货登记", UiItem.Source.NODE)), List.of())));
    }
    @Test public void duplicateOcrAndModalStillRejectNavigation() {
        UiItem nativeFooter = text("缺货登记", UiItem.Source.NODE), ocr = text("缺货登记", UiItem.Source.OCR);
        assertNull(DetailOcrNavigation.uniqueEntry(ObservationMerge.merge(List.of(nativeFooter), List.of(ocr, ocr))));
        assertNull(DetailOcrNavigation.uniqueEntry(ObservationMerge.merge(
                List.of(nativeFooter, text("确认登记", UiItem.Source.NODE)), List.of(ocr))));
    }
    @Test public void priceAndCheckoutDeduplicationAreUnchanged() {
        for (String value : List.of("1680元", "2张", "提交订单", "立即购买")) {
            UiItem nativeText = text(value, UiItem.Source.NODE);
            List<UiItem> merged = ObservationMerge.merge(List.of(nativeText), List.of(text(value, UiItem.Source.OCR)));
            assertEquals(1, merged.size()); assertSame(nativeText, merged.get(0));
        }
    }
}
