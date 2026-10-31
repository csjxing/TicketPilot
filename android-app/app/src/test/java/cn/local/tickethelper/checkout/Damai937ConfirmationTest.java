package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic mutation coverage of real observed native paths; never a real-device acceptance receipt. */
public final class Damai937ConfirmationTest {
    private static final String ID = "cn.damai:id/";
    private static final long NOW = 1_793_439_000_000L;
    private List<CheckoutFrame.Node> nodes;
    private void node(String key, String parent, String id, String text, boolean checkable, boolean checked, boolean clickable) {
        nodes.add(new CheckoutFrame.Node(key, parent,
                new UiItem(text, 20, 100 + nodes.size() * 20, 500, 115 + nodes.size() * 20,
                        true, clickable, false, UiItem.Source.NODE, id.isEmpty() ? "" : ID + id),
                true, checked, checkable, false, 1));
    }
    private void fixture() {
        nodes = new ArrayList<>();
        node("root", "", "action_bar_root", "", false, false, false);
        node("root/0", "root", "order_activity_title", "确认购买", false, false, false);
        node("root/1", "root", "recycler_view", "", false, false, false);
        node("root/1/0", "root/1", "", "", false, false, false);
        node("root/1/0/1", "root/1/0", "", "【杭州】测试歌手巡回演唱会-杭州站", false, false, false);
        node("root/1/0/2", "root/1/0", "", "杭州 | 测试体育馆", false, false, false);
        node("root/1/0/3", "root/1/0", "", "2026.12.19 19:00", false, false, false);
        node("root/1/0/5", "root/1/0", "", "￥980.00票档", false, false, false);
        node("root/1/0/6", "root/1/0", "", "×2张", false, false, false);
        node("root/1/0/14", "root/1/0", "", "条件退", false, false, false);
        node("root/1/1", "root/1", "recycler_main", "", false, false, false);
        for (int i = 0; i < 2; i++) {
            String row = "root/1/1/" + i;
            node(row, "root/1/1", "layout_main", "", false, false, false);
            node(row + "/0", row, "text_name", i == 0 ? "测试甲" : "测试乙", false, false, false);
            node(row + "/1", row, "checkbox", "", true, true, true);
            node(row + "/2", row, "text_num", i == 0 ? "***123" : "***456", false, false, false);
        }
        node("root/2", "root", "bottom_layout", "", false, false, false);
        node("root/2/0", "root/2", "", "", false, false, false);
        node("root/2/0/2", "root/2/0", "", "", false, false, false);
        node("root/2/0/2/0", "root/2/0/2", "", "¥ 1960.00", false, false, false);
        node("root/2/0/4", "root/2/0", "", "立即提交", false, false, true);
    }
    private CheckoutParseResult read() { return read(true, Damai937Confirmation.VERSION); }
    private CheckoutParseResult read(boolean complete, String version) {
        return CheckoutPageParser.parse(new CheckoutFrame("cn.damai", version, 1, 0, 4, 4, NOW, complete, nodes),
                Damai937Confirmation.profile(), NOW, (rows, all) -> ViewerMatcher.matchMany(List.of(
                        new ViewerMatcher.RequestedViewer("测试甲", ""), new ViewerMatcher.RequestedViewer("测试乙", "")), rows, all));
    }
    private void changeText(String key, String text) {
        for (int i=0; i<nodes.size(); i++) {
            CheckoutFrame.Node n=nodes.get(i); if (!n.key.equals(key)) continue;
            UiItem a=n.item;
            nodes.set(i,new CheckoutFrame.Node(n.key,n.parentKey,new UiItem(text,a.left,a.top,a.right,a.bottom,
                    a.enabled,a.clickable,a.selected,a.source,a.resourceId),n.visible,n.checked,n.checkable,n.sensitive,n.windowId,n.scrollable));
        }
    }
    @Test public void realLayoutPathsReadTwoTicketsWithoutInventedNativeFields() {
        fixture(); CheckoutParseResult result=read();
        assertEquals(CheckoutParseResult.Status.PARSED,result.status);
        assertEquals(Long.valueOf(196000),result.observation.payableFen);
        assertEquals(Integer.valueOf(2),result.observation.quantity);
        assertTrue(result.observation.viewerSelectionVerified); assertTrue(result.observation.agreementsAccepted);
        assertTrue(result.observation.uniqueNativeSubmitTarget); assertNotNull(result.observation.submitBinding);
    }
    @Test public void changedVersionOrIncompleteCaptureCannotUseNativeContract() {
        fixture(); assertNotEquals(CheckoutParseResult.Status.PARSED,read(false,Damai937Confirmation.VERSION).status);
        assertEquals(CheckoutParseResult.Status.UNSUPPORTED,read(true,"9.0.38#109003800").status);
    }
    @Test public void additionalChargeOrDiscountCannotBeSilentlyApplied() {
        for(String amount:List.of("¥ 1961.00","¥ 1959.00")) {
            fixture();changeText("root/2/0/2/0",amount);assertEquals("amount_relationship_unverified",read().reason);
        }
    }
    @Test public void titleAndVenueCitiesMustAgree() {
        fixture();changeText("root/1/0/2","上海 | 测试体育馆");
        assertEquals("native_confirmation_city_conflict",read().reason);
    }
    @Test public void rowOutsideVisibleNativeScopeIsNotACompleteList() {
        fixture();
        for(int i=0;i<nodes.size();i++) { CheckoutFrame.Node n=nodes.get(i); if(n.item.resourceId.equals(ID+"recycler_main"))
            nodes.set(i,new CheckoutFrame.Node(n.key,n.parentKey,n.item,n.visible,n.checked,n.checkable,n.sensitive,n.windowId,true)); }
        assertEquals("viewer_scope_scrollable",read().reason);
    }
    @Test public void extraTermsCheckboxOrInsuranceStopsSubmissionReadiness() {
        fixture();node("root/3","root","new_agreement","同意新的协议",true,false,true);
        assertFalse(read().observation.agreementsAccepted);
        fixture();node("root/3","root","new_extra","票务保险",false,false,false);
        assertFalse(read().observation.agreementsAccepted);
    }
    @Test public void unexpectedRefundRuleOrSecondSubmitDoesNotMatch() {
        fixture();changeText("root/1/0/14","新的规则");assertNotEquals(CheckoutParseResult.Status.PARSED,read().status);
        fixture();node("root/3","root","bottom_layout","",false,false,false);
        assertNotEquals(CheckoutParseResult.Status.PARSED,read().status);
    }
    @Test public void confirmationReaderNeverEstablishesPendingPaymentCapability() {
        CheckoutCapabilities cap=CheckoutCapabilities.forVersion(List.of(Damai937Confirmation.profile()),Damai937Confirmation.VERSION);
        assertTrue(cap.confirmationSubmission);assertFalse(cap.pendingPaymentRead);assertFalse(cap.completeCheckoutRead());
    }
}
