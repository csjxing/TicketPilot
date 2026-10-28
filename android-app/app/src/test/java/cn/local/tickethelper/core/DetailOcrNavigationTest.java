package cn.local.tickethelper.core;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic footer geometry is a contract test, not a live native-button adaptation. */
public final class DetailOcrNavigationTest {
    private UiItem label(String text, int y, UiItem.Source source) {
        return new UiItem(text, 200, y, 500, y + 40, true, false, false, source);
    }
    private Decision proposal(UiItem target) {
        return new Decision(Decision.State.READY, Decision.Action.OPEN_TICKETS, target,
                "verified_return_detail_entry", 1000);
    }
    @Test public void onlyExactScopedOcrNavigationContractIsAccepted() {
        UiItem target = label("缺货登记", 900, UiItem.Source.OCR);
        assertTrue(DetailOcrNavigation.isProposal(proposal(target)));
        for (Decision.Action action : Decision.Action.values()) if (action != Decision.Action.OPEN_TICKETS)
            assertFalse(DetailOcrNavigation.isProposal(new Decision(Decision.State.READY, action, target,
                    "verified_return_detail_entry", 1000)));
        assertFalse(DetailOcrNavigation.isProposal(new Decision(Decision.State.READY, Decision.Action.OPEN_TICKETS,
                target, "verified_detail_entry", 1000)));
        assertFalse(DetailOcrNavigation.isProposal(new Decision(Decision.State.PROBING, Decision.Action.OPEN_TICKETS,
                target, "verified_return_detail_entry", 1000)));
        assertFalse(DetailOcrNavigation.isProposal(proposal(label("缺货登记", 900, UiItem.Source.NODE))));
        assertFalse(DetailOcrNavigation.isProposal(proposal(label("确认登记", 900, UiItem.Source.OCR))));
    }
    @Test public void entireFooterLabelMustBeInsideBottomTwentyPercentOfLiveRoot() {
        assertTrue(DetailOcrNavigation.footerContained(proposal(label("缺货登记", 800, UiItem.Source.OCR)), 0, 0, 700, 1000));
        assertFalse(DetailOcrNavigation.footerContained(proposal(label("缺货登记", 799, UiItem.Source.OCR)), 0, 0, 700, 1000));
        assertFalse(DetailOcrNavigation.footerContained(proposal(label("缺货登记", 980, UiItem.Source.OCR)), 0, 0, 700, 1000));
        assertFalse(DetailOcrNavigation.footerContained(proposal(label("缺货登记", 900, UiItem.Source.OCR)), 210, 0, 700, 1000));
        assertFalse(DetailOcrNavigation.footerContained(proposal(label("缺货登记", 900, UiItem.Source.OCR)), 0, 0, 400, 1000));
        assertFalse(DetailOcrNavigation.footerContained(proposal(label("缺货登记", 900, UiItem.Source.OCR)), 0, 1000, 700, 0));
    }
    @Test public void duplicateFooterTextAndRegistrationModalNeverProduceEntry() {
        UiItem target = label("缺货登记", 900, UiItem.Source.OCR);
        assertSame(target, DetailOcrNavigation.uniqueEntry(List.of(target)));
        assertNull(DetailOcrNavigation.uniqueEntry(List.of(label("缺货登记", 100, UiItem.Source.OCR), target)));
        for (String modal : List.of("确认登记", "提交登记", "登记信息", "选择观演人", "登记手机号码"))
            assertNull(DetailOcrNavigation.uniqueEntry(List.of(target, label(modal, 300, UiItem.Source.NODE))));
    }
    @Test public void multilineModalTextIsVetoedWhileNormalMultilineDetailStillWorks() {
        UiItem target = label("缺货登记", 900, UiItem.Source.OCR);
        for (String modal : List.of("提示\n确认登记\n请检查信息", "登记页\n提交登记", "标题\r\n选择观演人\r\n说明"))
            for (UiItem.Source source : List.of(UiItem.Source.NODE, UiItem.Source.OCR))
                assertNull(DetailOcrNavigation.uniqueEntry(List.of(target, label(modal, 300, source))));
        assertSame(target, DetailOcrNavigation.uniqueEntry(List.of(target,
                label("演出介绍\n请选择对应日期", 300, UiItem.Source.OCR))));
    }
    @Test public void skuNativeFieldsRejectEvenWhenFooterTextLooksCorrect() {
        UiItem target = label("缺货登记", 900, UiItem.Source.OCR);
        for (String id : List.of("project_detail_perform_flowlayout", "project_detail_perform_price_flowlayout", "layout_num", "btn_buy_view")) {
            UiItem field = new UiItem("", 0, 100, 700, 400, true, false, false,
                    UiItem.Source.NODE, "cn.damai:id/" + id);
            assertNull(DetailOcrNavigation.uniqueEntry(List.of(target, field)));
        }
    }
    @Test public void nativeLeafAloneDoesNotBecomeOcrAuthority() {
        assertNull(DetailOcrNavigation.uniqueEntry(List.of(label("缺货登记", 900, UiItem.Source.NODE))));
    }
}
