package cn.local.tickethelper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Closed public vocabulary for a local debug capture, never a general-purpose text redactor. */
final class DebugSnapshotPolicy {
    static final String DETAIL_ENTRY_LABEL = "缺货登记";
    static final int MAX_DETAIL_CANDIDATES = 10, MAX_DETAIL_CHAIN = 20;
    enum CaptureScope { NATIVE_STRUCTURE, DETAIL_ENTRY }
    enum DetailLabelSource { NONE, TEXT, DESCRIPTION, BOTH }
    enum DetailFailure { NONE, LOOKUP_FAILED, CANDIDATE_LIMIT, ENTRY_NOT_FOUND, CHAIN_NULL,
        CHAIN_LIMIT, REFRESH_FAILED, WINDOW_MISMATCH, PACKAGE_MISMATCH, PROTECTED_CHAIN,
        ROOT_NOT_REACHED, CAPTURE_EXPIRED, NODE_ACCESS_FAILED }
    enum DetailRefresh {
        SUCCEEDED, RETURNED_FALSE, THREW, NOT_ATTEMPTED;
        Boolean rawResult() { return this == SUCCEEDED ? Boolean.TRUE : this == RETURNED_FALSE ? Boolean.FALSE : null; }
    }
    enum DetailTraceEnd { NONE, ROOT_REACHED, PARENT_NULL, PARENT_ACCESS_FAILED, CHAIN_LIMIT, CAPTURE_EXPIRED, SERVICE_CHANGED }
    static int detailChainLevel(int level) { return level >= 0 && level < MAX_DETAIL_CHAIN ? level : -1; }
    enum FailureReason { NONE, NULL_NODE, DEPTH_LIMIT, NODE_LIMIT, CAPTURE_EXPIRED, REFRESH_FAILED,
        WINDOW_MISMATCH, PACKAGE_MISMATCH, CHILD_COUNT_INVALID, CHILD_COUNT_LIMIT,
        CHILD_ACCESS_FAILED, NODE_ACCESS_FAILED }
    enum PackageKind { DAMAI, SYSTEM, EMPTY, OTHER }
    /** No arbitrary package, error, class or page strings enter a failure report. */
    static final class FailureMetadata {
        final FailureReason reason;
        final int parentIndex, depth, childIndex;
        final String parentId;
        final PackageKind packageKind;
        final Boolean visible;
        FailureMetadata(FailureReason reason, int parentIndex, int depth, int childIndex,
                String parentId, PackageKind packageKind, Boolean visible) {
            this.reason = reason == null ? FailureReason.NODE_ACCESS_FAILED : reason;
            this.parentIndex = bounded(parentIndex, 1600); this.depth = bounded(depth, 61);
            this.childIndex = bounded(childIndex, 1600); this.parentId = resourceId(parentId);
            this.packageKind = packageKind == null ? PackageKind.EMPTY : packageKind; this.visible = visible;
        }
        private static int bounded(int value, int max) { return value >= -1 && value <= max ? value : -1; }
    }
    private static final String PREFIX = "cn.damai:id/";
    private static final Set<String> LABELS = Set.of(
            "场次", "票档", "数量", "购买数量", "合计", "总计", "票款", "应付", "确定", "取消", "返回",
            "预售", "售票中", "开售提醒", "缺货", "缺货登记", "无票", "售罄", "已售完", "暂不可购", "不可选",
            "立即购买", "立即预订", "选座购买", "购买", "确认购买", "确认订单", "提交订单", "立即提交",
            "待付款", "立即支付", "付款剩余", "支付成功", "预约想看场次", "开售时间", "观演人", "添加观演人",
            "价格明细", "商品总额", "交易关闭", "订单详情", "需付",
            "实名观演人", "请选择观演人", "选择观演人", "配送方式", "电子票", "手机号码", "联系人",
            "温州", "杭州", "看台", "内场", "¥", "￥", "+", "-", "＋", "－", "−",
            "增加", "减少", "增加数量", "减少数量", "增加一张", "减少一张", "加一张", "减一张");
    private static final Pattern DATE = Pattern.compile("^(20\\d{2})[-./年](\\d{1,2})[-./月](\\d{1,2})(?:日)?(?:\\s*(?:周|星期)[一二三四五六日天])?(?:\\s*([0-2]\\d):([0-5]\\d))?$");
    private static final Pattern MONEY = Pattern.compile("^(?:(?:看台|内场|票款|合计|总计|应付)\\s*[:：]?\\s*)?[¥￥]?\\s*(\\d{1,4})(?:\\.0{1,2})?\\s*(元)?$");
    private static final Pattern TIME = Pattern.compile("^([0-2]\\d):([0-5]\\d)$");
    private static final Pattern COUNT = Pattern.compile("^(?:(?:购买数量|数量|已选)\\s*[:：]?\\s*)?([12])\\s*张$");
    private static final Pattern UNIT_PRICE_QUANTITY = Pattern.compile("^[¥￥]\\s*(\\d{1,4})(?:\\.0{1,2})?\\s*票档\\s*×\\s*([12])\\s*张$");
    private DebugSnapshotPolicy() { }

    static CaptureScope captureScope(String value) {
        if (value == null) return CaptureScope.NATIVE_STRUCTURE;
        return "detail_entry".equals(value) ? CaptureScope.DETAIL_ENTRY : null;
    }
    static DetailLabelSource detailLabelSource(CharSequence text, CharSequence description) {
        boolean t = DETAIL_ENTRY_LABEL.contentEquals(text == null ? "" : text);
        boolean d = DETAIL_ENTRY_LABEL.contentEquals(description == null ? "" : description);
        return t ? d ? DetailLabelSource.BOTH : DetailLabelSource.TEXT : d ? DetailLabelSource.DESCRIPTION : DetailLabelSource.NONE;
    }
    static boolean mayReadDetailLabel(boolean fresh, boolean sameWindow, boolean damai,
            boolean protectedChain, boolean rootReached, int chainLength, boolean expired) {
        return fresh && sameWindow && damai && !protectedChain && rootReached && !expired
                && chainLength >= 1 && chainLength <= MAX_DETAIL_CHAIN;
    }

    static PackageKind packageKind(CharSequence value) {
        if (value == null || value.length() == 0) return PackageKind.EMPTY;
        if ("cn.damai".contentEquals(value)) return PackageKind.DAMAI;
        if ("android".contentEquals(value) || "com.android.systemui".contentEquals(value)) return PackageKind.SYSTEM;
        return PackageKind.OTHER;
    }
    /** Only a positively refreshed, same-window invisible system leaf is out of scope. */
    static boolean mayExcludeSystemLeaf(PackageKind kind, boolean refreshed, boolean sameWindow,
            boolean visible, int childCount) {
        return kind == PackageKind.SYSTEM && refreshed && sameWindow && !visible && childCount == 0;
    }

    static String publicText(CharSequence value, String rawId) {
        if (value == null || value.length() > 100) return "";
        String text = value.toString().trim();
        if (text.isEmpty() || text.chars().anyMatch(c -> c < 32 || c == 127)) return "";
        if (LABELS.contains(text) || COUNT.matcher(text).matches()) return text;
        Matcher priceQuantity = UNIT_PRICE_QUANTITY.matcher(text);
        if (priceQuantity.matches()) {
            int unitPrice = Integer.parseInt(priceQuantity.group(1));
            return unitPrice >= 1 && unitPrice <= 3360 ? text : "";
        }
        Matcher date = DATE.matcher(text);
        if (date.matches()) {
            try {
                LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
                if (date.group(4) != null) LocalTime.of(Integer.parseInt(date.group(4)), Integer.parseInt(date.group(5)));
                return text;
            } catch (RuntimeException invalid) { return ""; }
        }
        Matcher time = TIME.matcher(text);
        if (time.matches()) {
            try { LocalTime.of(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2))); return text; }
            catch (RuntimeException invalid) { return ""; }
        }
        String id = resourceId(rawId);
        if ((PREFIX + "tv_num").equals(id) && ("1".equals(text) || "2".equals(text))) return text;
        Matcher money = MONEY.matcher(text);
        if (money.matches()) {
            int amount;
            try { amount = Integer.parseInt(money.group(1)); } catch (NumberFormatException invalid) { return ""; }
            boolean labelled = text.indexOf('¥') >= 0 || text.indexOf('￥') >= 0 || money.group(2) != null
                    || text.startsWith("票款") || text.startsWith("合计") || text.startsWith("总计") || text.startsWith("应付");
            // A bare digit sequence is not a price: allow it only on the known amount field.
            if (amount >= 1 && amount <= 3360 && (labelled || (PREFIX + "tv_price").equals(id))) return text;
        }
        return "";
    }

    static String resourceId(String value) {
        if (value == null || !value.startsWith(PREFIX)) return "";
        String name = value.substring(PREFIX.length());
        return name.matches("[A-Za-z_][A-Za-z0-9_]{0,119}") && !Pattern.compile("\\d{5}").matcher(name).find() ? value : "";
    }

    static String className(CharSequence value) {
        if (value == null || value.length() > 180) return "";
        String name = value.toString();
        return name.matches("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*")
                && !Pattern.compile("\\d{5}").matcher(name).find() ? name : "";
    }
}
