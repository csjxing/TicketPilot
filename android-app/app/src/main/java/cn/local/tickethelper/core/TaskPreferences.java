package cn.local.tickethelper.core;

/** Independently editable preferences; saving them never validates or starts a purchase target. */
public final class TaskPreferences {
    private TaskPreferences() { }

    public static MonitorConfig withPace(MonitorConfig previous, long minMs, long maxMs) {
        if (previous == null || minMs < MonitorConfig.MIN_INTERVAL_MS || maxMs < minMs || maxMs > MonitorConfig.MAX_INTERVAL_MS)
            throw new IllegalArgumentException("Invalid operation interval");
        return copy(previous, minMs, maxMs, previous.mode);
    }

    public static MonitorConfig withMode(MonitorConfig previous, MonitorConfig.Mode mode) {
        if (previous == null || mode == null) throw new IllegalArgumentException("Missing preference");
        return copy(previous, previous.minIntervalMs, previous.maxIntervalMs, mode);
    }

    private static MonitorConfig copy(MonitorConfig c, long minMs, long maxMs, MonitorConfig.Mode mode) {
        return new MonitorConfig(c.eventKeyword, c.city, c.date, c.time, c.prices, c.quantity, c.maxTotal,
                c.deadline, minMs, maxMs, mode);
    }
}
