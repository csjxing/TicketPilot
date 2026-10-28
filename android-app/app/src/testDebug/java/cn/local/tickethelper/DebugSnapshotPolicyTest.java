package cn.local.tickethelper;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DebugSnapshotPolicyTest {
    @Test public void retainsOnlyPublicSkuAndOrderLabels() {
        for (String text : new String[]{"数量", "确定", "立即提交", "待付款", "+", "减少数量", "预售", "缺货登记",
                "价格明细", "商品总额", "交易关闭", "订单详情", "需付"})
            assertEquals(text, DebugSnapshotPolicy.publicText(text, ""));
    }
    @Test public void retainsOnlyStrictBoundedUnitPriceAndOneOrTwoTicketExpression() {
        for (String text : new String[]{"¥980.00票档 ×2张", "￥1180.00票档 ×1张", "¥1票档×1张", "¥3360.00票档 ×2张",
                "¥980票档×2张", "￥980.0 票档 × 2 张"})
            assertEquals(text, DebugSnapshotPolicy.publicText(text, ""));
        for (String text : new String[]{"¥0.00票档 ×2张", "¥3361.00票档 ×2张", "¥980.00票档 ×3张", "¥980.00票档 ×0张",
                "¥980.00票档 ×12张", "¥99999.00票档 ×2张", "¥980.01票档 ×2张", "¥980.000票档 ×2张",
                "980.00票档 ×2张", "¥980.00票档 x2张", "¥980.00票档 *2张", "¥980.00票档 ×-1张"})
            assertEquals("", DebugSnapshotPolicy.publicText(text, "cn.damai:id/tv_price"));
    }
    @Test public void combinedPriceAndNewLabelsCannotCarryNamesDocumentsUrlsOrAgreementText() {
        for (String text : new String[]{"¥980.00票档 ×2张 张三", "张三 ¥980.00票档 ×2张", "¥980.00票档 ×2张 13900000000",
                "¥980.00票档 ×2张 110101199001011234", "¥980.00票档 ×2张\n姓名", "¥980.00票档 ×2张 https://example.test/?token=x",
                "订单详情：123456789012345678", "交易关闭 用户张三", "商品总额 1960元 联系人张三", "需付 https://example.test",
                "价格明细 请阅读并同意完整购票服务协议和个人信息保护条款", "¥980.00票档 ×2张 同意协议"})
            assertEquals("", DebugSnapshotPolicy.publicText(text, "cn.damai:id/tv_price"));
        // The new public labels do not turn arbitrary numeric fields into prices.
        assertEquals("", DebugSnapshotPolicy.publicText("1960", "cn.damai:id/order_id"));
        assertEquals("", DebugSnapshotPolicy.publicText("需付1960", ""));
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
    @Test public void packageClassificationNeverRetainsAnArbitraryPackageString() {
        assertEquals(DebugSnapshotPolicy.PackageKind.DAMAI, DebugSnapshotPolicy.packageKind("cn.damai"));
        assertEquals(DebugSnapshotPolicy.PackageKind.SYSTEM, DebugSnapshotPolicy.packageKind("android"));
        assertEquals(DebugSnapshotPolicy.PackageKind.SYSTEM, DebugSnapshotPolicy.packageKind("com.android.systemui"));
        assertEquals(DebugSnapshotPolicy.PackageKind.EMPTY, DebugSnapshotPolicy.packageKind(null));
        assertEquals(DebugSnapshotPolicy.PackageKind.EMPTY, DebugSnapshotPolicy.packageKind(""));
        for (String name : new String[]{"cn.damai.other", "android.private", "com.example.private.account", "示例姓名", "sk-private-test"})
            assertEquals(DebugSnapshotPolicy.PackageKind.OTHER, DebugSnapshotPolicy.packageKind(name));
    }
    @Test public void exclusionRequiresEveryPositiveInvisibleSystemLeafGuard() {
        assertTrue(DebugSnapshotPolicy.mayExcludeSystemLeaf(DebugSnapshotPolicy.PackageKind.SYSTEM, true, true, false, 0));
        for (DebugSnapshotPolicy.PackageKind kind : new DebugSnapshotPolicy.PackageKind[]{DebugSnapshotPolicy.PackageKind.DAMAI,
                DebugSnapshotPolicy.PackageKind.EMPTY, DebugSnapshotPolicy.PackageKind.OTHER, null})
            assertFalse(DebugSnapshotPolicy.mayExcludeSystemLeaf(kind, true, true, false, 0));
        assertFalse(DebugSnapshotPolicy.mayExcludeSystemLeaf(DebugSnapshotPolicy.PackageKind.SYSTEM, false, true, false, 0));
        assertFalse(DebugSnapshotPolicy.mayExcludeSystemLeaf(DebugSnapshotPolicy.PackageKind.SYSTEM, true, false, false, 0));
        assertFalse(DebugSnapshotPolicy.mayExcludeSystemLeaf(DebugSnapshotPolicy.PackageKind.SYSTEM, true, true, true, 0));
        assertFalse(DebugSnapshotPolicy.mayExcludeSystemLeaf(DebugSnapshotPolicy.PackageKind.SYSTEM, true, true, false, 1));
        assertFalse(DebugSnapshotPolicy.mayExcludeSystemLeaf(DebugSnapshotPolicy.PackageKind.SYSTEM, true, true, false, -1));
    }
    @Test public void failureMetadataKeepsOnlyBoundedStructureAndSanitizedParentId() {
        DebugSnapshotPolicy.FailureMetadata failure = new DebugSnapshotPolicy.FailureMetadata(
                DebugSnapshotPolicy.FailureReason.NULL_NODE, 48, 7, 2, "cn.damai:id/layout_num",
                DebugSnapshotPolicy.PackageKind.EMPTY, null);
        assertEquals(DebugSnapshotPolicy.FailureReason.NULL_NODE, failure.reason);
        assertEquals(48, failure.parentIndex); assertEquals(7, failure.depth); assertEquals(2, failure.childIndex);
        assertEquals("cn.damai:id/layout_num", failure.parentId); assertNull(failure.visible);
        DebugSnapshotPolicy.FailureMetadata untrusted = new DebugSnapshotPolicy.FailureMetadata(null,
                Integer.MAX_VALUE, 1000000, -12345, "cn.damai:id/order_123456789",
                null, false);
        assertEquals(DebugSnapshotPolicy.FailureReason.NODE_ACCESS_FAILED, untrusted.reason);
        assertEquals(-1, untrusted.parentIndex); assertEquals(-1, untrusted.depth); assertEquals(-1, untrusted.childIndex);
        assertEquals("", untrusted.parentId); assertEquals(DebugSnapshotPolicy.PackageKind.EMPTY, untrusted.packageKind);
        assertEquals(Boolean.FALSE, untrusted.visible);
    }
    @Test public void detailEntryScopeIsExplicitAndDoesNotAcceptArbitraryQueries() {
        assertEquals(DebugSnapshotPolicy.CaptureScope.NATIVE_STRUCTURE, DebugSnapshotPolicy.captureScope(null));
        assertEquals(DebugSnapshotPolicy.CaptureScope.DETAIL_ENTRY, DebugSnapshotPolicy.captureScope("detail_entry"));
        for (String value : new String[]{"", "DETAIL_ENTRY", "detail_entry private", "detail_entry\n", "姓名", "提交订单"})
            assertNull(DebugSnapshotPolicy.captureScope(value));
    }
    @Test public void detailEntryLabelRequiresExactFixedTextOrDescription() {
        assertEquals(DebugSnapshotPolicy.DetailLabelSource.TEXT, DebugSnapshotPolicy.detailLabelSource("缺货登记", null));
        assertEquals(DebugSnapshotPolicy.DetailLabelSource.DESCRIPTION, DebugSnapshotPolicy.detailLabelSource(null, "缺货登记"));
        assertEquals(DebugSnapshotPolicy.DetailLabelSource.BOTH, DebugSnapshotPolicy.detailLabelSource("缺货登记", "缺货登记"));
        for (String text : new String[]{" 缺货登记", "缺货登记 ", "缺货登记\n", "当前缺货登记", "缺货登记 观演人张三",
                "缺货登记https://example.test", "立即提交", "", "sk-synthetic-private"}) {
            assertEquals(DebugSnapshotPolicy.DetailLabelSource.NONE, DebugSnapshotPolicy.detailLabelSource(text, null));
            assertEquals(DebugSnapshotPolicy.DetailLabelSource.NONE, DebugSnapshotPolicy.detailLabelSource(null, text));
        }
        assertEquals(DebugSnapshotPolicy.DetailLabelSource.NONE, DebugSnapshotPolicy.detailLabelSource(null, null));
    }
    @Test public void detailLabelCannotBeReadBeforeFreshUnprotectedSameWindowRootChainIsComplete() {
        assertTrue(DebugSnapshotPolicy.mayReadDetailLabel(true, true, true, false, true, 1, false));
        assertTrue(DebugSnapshotPolicy.mayReadDetailLabel(true, true, true, false, true, 20, false));
        assertFalse(DebugSnapshotPolicy.mayReadDetailLabel(false, true, true, false, true, 1, false));
        assertFalse(DebugSnapshotPolicy.mayReadDetailLabel(true, false, true, false, true, 1, false));
        assertFalse(DebugSnapshotPolicy.mayReadDetailLabel(true, true, false, false, true, 1, false));
        assertFalse(DebugSnapshotPolicy.mayReadDetailLabel(true, true, true, true, true, 1, false));
        assertFalse(DebugSnapshotPolicy.mayReadDetailLabel(true, true, true, false, false, 1, false));
        assertFalse(DebugSnapshotPolicy.mayReadDetailLabel(true, true, true, false, true, 0, false));
        assertFalse(DebugSnapshotPolicy.mayReadDetailLabel(true, true, true, false, true, 21, false));
        assertFalse(DebugSnapshotPolicy.mayReadDetailLabel(true, true, true, false, true, 1, true));
    }
    @Test public void failureRefreshFactsPreserveFalseAndDoNotInventSuccessForUnknownOrExceptions() {
        assertEquals(Boolean.TRUE, DebugSnapshotPolicy.DetailRefresh.SUCCEEDED.rawResult());
        assertEquals(Boolean.FALSE, DebugSnapshotPolicy.DetailRefresh.RETURNED_FALSE.rawResult());
        assertNull(DebugSnapshotPolicy.DetailRefresh.THREW.rawResult());
        assertNull(DebugSnapshotPolicy.DetailRefresh.NOT_ATTEMPTED.rawResult());
        // A cached ID or clickable flag cannot replace the failed freshness prerequisite.
        assertFalse(DebugSnapshotPolicy.mayReadDetailLabel(false, true, true, false, true, 4, false));
    }
    @Test public void failureChainLayerIsBoundedAndMetadataFiltersStayClosed() {
        assertEquals(0, DebugSnapshotPolicy.detailChainLevel(0));
        assertEquals(19, DebugSnapshotPolicy.detailChainLevel(19));
        for (int invalid : new int[]{-1, -123, 20, Integer.MAX_VALUE}) assertEquals(-1, DebugSnapshotPolicy.detailChainLevel(invalid));
        assertEquals("cn.damai:id/btn_buy", DebugSnapshotPolicy.resourceId("cn.damai:id/btn_buy"));
        for (String value : new String[]{"cn.damai:id/order_123456789", "other.app:id/bottom_entry", "cn.damai:id/姓名"})
            assertEquals("", DebugSnapshotPolicy.resourceId(value));
        assertEquals("", DebugSnapshotPolicy.className("android.widget.TextView arbitrary page text"));
    }
}
