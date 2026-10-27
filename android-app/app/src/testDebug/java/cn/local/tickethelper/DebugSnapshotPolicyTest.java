package cn.local.tickethelper;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DebugSnapshotPolicyTest {
    @Test public void retainsOnlyPublicSkuAndOrderLabels() {
        for (String text : new String[]{"数量", "确定", "立即提交", "待付款", "+", "减少数量", "预售", "缺货登记"})
            assertEquals(text, DebugSnapshotPolicy.publicText(text, ""));
    }
    @Test public void rejectsNamesDocumentsAccountsTokensAndMixedPageText() {
        for (String text : new String[]{"测试观演人甲", "张三 证件尾号1234", "13900000000", "110101199001011234",
                "订单号123456789012345678", "sk-private-test-value", "https://service.example/?token=secret",
                "当前观演人：张三", "已选2张 张三", "980元 联系人张三", "1张\n订单12345"})
            assertEquals("", DebugSnapshotPolicy.publicText(text, "cn.damai:id/tv_price"));
    }
    @Test public void bareNumericTextNeedsExactKnownPublicNativeField() {
        assertEquals("", DebugSnapshotPolicy.publicText("1680", ""));
        assertEquals("", DebugSnapshotPolicy.publicText("1680", "cn.damai:id/order_id"));
        assertEquals("1680", DebugSnapshotPolicy.publicText("1680", "cn.damai:id/tv_price"));
        assertEquals("1", DebugSnapshotPolicy.publicText("1", "cn.damai:id/tv_num"));
        assertEquals("", DebugSnapshotPolicy.publicText("3", "cn.damai:id/tv_num"));
        assertEquals("", DebugSnapshotPolicy.publicText("3361", "cn.damai:id/tv_price"));
    }
    @Test public void preservesBoundedMoneyAndTwoTicketQuantity() {
        for (String text : new String[]{"看台980元", "内场1680元", "¥3360", "合计 ¥3360", "1680.00元", "1张", "2张", "购买数量 2 张"})
            assertEquals(text, DebugSnapshotPolicy.publicText(text, ""));
        for (String text : new String[]{"0元", "3361元", "99999999元", "1680.01元", "3张", "数量 18 张"})
            assertEquals("", DebugSnapshotPolicy.publicText(text, ""));
    }
    @Test public void acceptsValidPublicDateTimeButNotMalformedOrExtraContent() {
        for (String text : new String[]{"2026-10-31", "2026年10月31日", "2026-10-31 周六 19:00", "19:00"})
            assertEquals(text, DebugSnapshotPolicy.publicText(text, ""));
        for (String text : new String[]{"2026-02-30", "2026-10-31 29:00", "29:00", "2026-10-31 曹某", "202610311900"})
            assertEquals("", DebugSnapshotPolicy.publicText(text, ""));
    }
    @Test public void staticMetadataCannotBecomeAPrivateTextChannel() {
        assertEquals("cn.damai:id/tv_num", DebugSnapshotPolicy.resourceId("cn.damai:id/tv_num"));
        assertEquals("cn.damai:id/home_v2", DebugSnapshotPolicy.resourceId("cn.damai:id/home_v2"));
        for (String id : new String[]{"other.app:id/tv_num", "cn.damai:id/person/姓名", "cn.damai:id/order_123456789", "cn.damai:id/evil\ntext"})
            assertEquals("", DebugSnapshotPolicy.resourceId(id));
        assertEquals("android.widget.ImageView", DebugSnapshotPolicy.className("android.widget.ImageView"));
        assertEquals("", DebugSnapshotPolicy.className("android.widget.ImageView secret"));
        assertEquals("", DebugSnapshotPolicy.className("姓名"));
    }
}
