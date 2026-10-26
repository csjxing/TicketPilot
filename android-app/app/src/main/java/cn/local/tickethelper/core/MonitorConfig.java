package cn.local.tickethelper.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Local user settings. Money is expressed in whole yuan. */
public final class MonitorConfig {
    public static final int MIN_INTERVAL_MS = 500, MAX_INTERVAL_MS = 600000;
    public enum Mode { ALERT, PREPARE, LOCK_ORDER }
    public final String eventKeyword, city, date, time;
    public final List<Integer> prices;
    public final int quantity, maxTotal;
    public final Instant deadline;
    public final long minIntervalMs, maxIntervalMs;
    public final Mode mode;

    public MonitorConfig(String eventKeyword, String city, String date, String time,
                         List<Integer> prices, int quantity, int maxTotal, Instant deadline,
                         long minIntervalMs, long maxIntervalMs, Mode mode) {
        this.eventKeyword = clean(eventKeyword);
        this.city = clean(city);
        this.date = clean(date);
        this.time = clean(time);
        this.prices = Collections.unmodifiableList(new ArrayList<>(prices == null ? Collections.emptyList() : prices));
        this.quantity = quantity;
        this.maxTotal = maxTotal;
        this.deadline = deadline;
        this.minIntervalMs = minIntervalMs;
        this.maxIntervalMs = maxIntervalMs;
        this.mode = mode;
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    /** Final order submission is separately authorized and never performed by the SKU engine. */
    public boolean canPreparePurchase() { return mode == Mode.PREPARE || mode == Mode.LOCK_ORDER; }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (eventKeyword.length() < 2) errors.add("演出关键词至少需要两个字符");
        if (city.isEmpty()) errors.add("请填写城市");
        try {
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}") || !LocalDate.parse(date).toString().equals(date))
                errors.add("日期应为有效的 YYYY-MM-DD");
        } catch (DateTimeParseException e) { errors.add("日期应为有效的 YYYY-MM-DD"); }
        try {
            if (!time.matches("\\d{2}:\\d{2}") || !LocalTime.parse(time).toString().equals(time))
                errors.add("场次时间应为有效的 HH:mm");
        } catch (DateTimeParseException e) { errors.add("场次时间应为有效的 HH:mm"); }
        if (prices.isEmpty() || prices.size() > 20) errors.add("请设置 1 至 20 个票价");
        if (prices.stream().anyMatch(p -> p == null || p <= 0 || p > 100000)) errors.add("票价必须为 1 至 100000 元");
        if (new HashSet<>(prices).size() != prices.size()) errors.add("票价不能重复");
        if (quantity < 1 || quantity > 2) errors.add("请选择 1 至 2 张，最终提交前核对对应观演人");
        if (maxTotal <= 0 || maxTotal > 100000) errors.add("总预算必须为 1 至 100000 元");
        if (prices.stream().filter(p -> p != null).anyMatch(p -> (long) p * quantity > maxTotal))
            errors.add("总预算不能低于任一接受票价的票款");
        if (deadline == null) errors.add("请设置停止时间");
        else try { deadline.toEpochMilli(); } catch (ArithmeticException e) { errors.add("停止时间超出有效范围"); }
        if (minIntervalMs < MIN_INTERVAL_MS || maxIntervalMs < minIntervalMs || maxIntervalMs > MAX_INTERVAL_MS)
            errors.add("操作间隔须介于 0.5 秒与 10 分钟，最大值不能小于最小值");
        if (mode == null) errors.add("请选择提醒、准备或锁单模式");
        return Collections.unmodifiableList(errors);
    }
}
