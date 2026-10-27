package cn.local.tickethelper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Closed public vocabulary for a local debug capture, never a general-purpose text redactor. */
final class DebugSnapshotPolicy {
    private static final String PREFIX = "cn.damai:id/";
    private static final Set<String> LABELS = Set.of(
            "场次", "票档", "数量", "购买数量", "合计", "总计", "票款", "应付", "确定", "取消", "返回",
            "预售", "售票中", "开售提醒", "缺货", "缺货登记", "无票", "售罄", "已售完", "暂不可购", "不可选",
            "立即购买", "立即预订", "选座购买", "购买", "确认购买", "确认订单", "提交订单", "立即提交",
            "待付款", "立即支付", "付款剩余", "支付成功", "预约想看场次", "开售时间", "观演人", "添加观演人",
            "实名观演人", "请选择观演人", "选择观演人", "配送方式", "电子票", "手机号码", "联系人",
            "温州", "杭州", "看台", "内场", "¥", "￥", "+", "-", "＋", "－", "−",
            "增加", "减少", "增加数量", "减少数量", "增加一张", "减少一张", "加一张", "减一张");
    private static final Pattern DATE = Pattern.compile("^(20\\d{2})[-./年](\\d{1,2})[-./月](\\d{1,2})(?:日)?(?:\\s*(?:周|星期)[一二三四五六日天])?(?:\\s*([0-2]\\d):([0-5]\\d))?$");
    private static final Pattern MONEY = Pattern.compile("^(?:(?:看台|内场|票款|合计|总计|应付)\\s*[:：]?\\s*)?[¥￥]?\\s*(\\d{1,4})(?:\\.0{1,2})?\\s*(元)?$");
    private static final Pattern TIME = Pattern.compile("^([0-2]\\d):([0-5]\\d)$");
    private static final Pattern COUNT = Pattern.compile("^(?:(?:购买数量|数量|已选)\\s*[:：]?\\s*)?([12])\\s*张$");
    private DebugSnapshotPolicy() { }

    static String publicText(CharSequence value, String rawId) {
        if (value == null || value.length() > 100) return "";
        String text = value.toString().trim();
        if (text.isEmpty() || text.chars().anyMatch(c -> c < 32 || c == 127)) return "";
        if (LABELS.contains(text) || COUNT.matcher(text).matches()) return text;
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
