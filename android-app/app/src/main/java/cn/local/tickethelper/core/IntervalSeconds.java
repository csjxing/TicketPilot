package cn.local.tickethelper.core;

import java.math.BigDecimal;

/** Exact conversion for user-entered seconds; persisted intervals remain integer milliseconds. */
public final class IntervalSeconds {
    private IntervalSeconds() { }

    public static long parse(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() > 32 || !value.matches("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"))
            throw new IllegalArgumentException("间隔请填写秒数，例如 0.5 或 1.5。");
        try {
            long millis = new BigDecimal(value).movePointRight(3).longValueExact();
            if (millis < MonitorConfig.MIN_INTERVAL_MS || millis > MonitorConfig.MAX_INTERVAL_MS)
                throw new IllegalArgumentException("间隔须介于 0.5 和 600 秒。");
            return millis;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("间隔最多精确到 0.001 秒，且须介于 0.5 和 600 秒。");
        }
    }

    public static String format(long millis) {
        return BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString();
    }
}
