package cn.local.tickethelper.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Local user settings. Money is expressed in whole yuan. */
public final class MonitorConfig {
    public static final int MIN_INTERVAL_MS = 500, MAX_INTERVAL_MS = 600000;
    public static final int MAX_FOLLOW_UP_MINUTES = 1440;
    public enum Mode { ALERT, PREPARE, LOCK_ORDER }
    public final String eventKeyword, city, date, time;
    public final List<Integer> prices;
    public final int quantity, maxTotal;
    public final Instant deadline;
    public final long minIntervalMs, maxIntervalMs;
    public final Mode mode;
    /** Explicit local schedule; null/zero preserves the legacy deadline-only behavior. */
    public final Instant saleAt;
    public final int followUpMinutes;
    public final boolean timingSpecified;
    public final int openingPrice;
    private final boolean openingPriceSpecified;

    public MonitorConfig(String eventKeyword, String city, String date, String time,
                         List<Integer> prices, int quantity, int maxTotal, Instant deadline,
                         long minIntervalMs, long maxIntervalMs, Mode mode) {
        this(eventKeyword, city, date, time, prices, quantity, maxTotal, deadline,
                minIntervalMs, maxIntervalMs, mode, null, 0, false, 0, false);
    }

    public MonitorConfig(String eventKeyword, String city, String date, String time,
                         List<Integer> prices, int quantity, int maxTotal, Instant deadline,
                         long minIntervalMs, long maxIntervalMs, Mode mode, Instant saleAt,
                         int followUpMinutes) {
        this(eventKeyword, city, date, time, prices, quantity, maxTotal, deadline,
                minIntervalMs, maxIntervalMs, mode, saleAt, followUpMinutes, true, 0, false);
    }

    private MonitorConfig(String eventKeyword, String city, String date, String time,
                          List<Integer> prices, int quantity, int maxTotal, Instant deadline,
                          long minIntervalMs, long maxIntervalMs, Mode mode, Instant saleAt,
                          int followUpMinutes, boolean timingSpecified, int openingPrice, boolean openingPriceSpecified) {
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
        this.saleAt = saleAt;
        this.followUpMinutes = followUpMinutes;
        this.timingSpecified = timingSpecified;
        this.openingPrice=openingPrice;this.openingPriceSpecified=openingPriceSpecified;
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    /** Final order submission is separately authorized and never performed by the SKU engine. */
    public boolean canPreparePurchase() { return mode == Mode.PREPARE || mode == Mode.LOCK_ORDER; }

    public MonitorConfig withTiming(Instant saleAt, int followUpMinutes) {
        return new MonitorConfig(eventKeyword, city, date, time, prices, quantity, maxTotal,
                deadline, minIntervalMs, maxIntervalMs, mode, saleAt, followUpMinutes, true, openingPrice, openingPriceSpecified);
    }

    /** Older screens omit new schedule fields; retain them only for the identical event/session. */
    public MonitorConfig withOpeningPrice(int price) {
        return new MonitorConfig(eventKeyword,city,date,time,prices,quantity,maxTotal,deadline,minIntervalMs,maxIntervalMs,
                mode,saleAt,followUpMinutes,timingSpecified,price,true);
    }
    public MonitorConfig resolveLegacyTiming(MonitorConfig previous) {
        if(previous==null || !eventKeyword.equals(previous.eventKeyword) || !city.equals(previous.city)
                || !date.equals(previous.date) || !time.equals(previous.time))return this;
        MonitorConfig result=timingSpecified?this:withTiming(previous.saleAt,previous.followUpMinutes);
        if(!openingPriceSpecified)result=result.withOpeningPrice(prices.contains(previous.openingPrice)?previous.openingPrice:0);
        return result;
    }

    /** All current events are in China; never interpret a phone's travel time zone as the venue. */
    public Instant eventStartsAt() {
        try { return LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant(); }
        catch (RuntimeException invalid) { return null; }
    }

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
        if (openingPrice<0 || (openingPrice>0 && !prices.contains(openingPrice))) errors.add("首抢票价须在接受票价中");
        if (mode == null) errors.add("请选择提醒、准备或锁单模式");
        if (followUpMinutes < 0 || followUpMinutes > MAX_FOLLOW_UP_MINUTES)
            errors.add("持续监控时长应为 0 至 1440 分钟，0 表示沿用停止时间");
        if (saleAt != null) {
            try {
                saleAt.toEpochMilli();
                Instant eventStart = eventStartsAt();
                if (eventStart != null && !saleAt.isBefore(eventStart)) errors.add("开售时间必须早于演出时间");
                if (deadline != null && !saleAt.isBefore(deadline)) errors.add("停止时间必须晚于开售时间");
            } catch (ArithmeticException invalid) { errors.add("开售时间超出有效范围"); }
        }
        return Collections.unmodifiableList(errors);
    }
}
