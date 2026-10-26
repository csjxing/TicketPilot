package cn.local.tickethelper.core;

/**
 * Local navigation guidance from a stopped monitor, never current inventory or order evidence.
 * Even a persisted ORDER_PENDING event only asks the user to check Damai again.
 */
public enum MonitorStopState {
    NONE, PAUSED, NEEDS_ATTENTION, REVIEW_DAMAI, REVIEW_ORDER;

    public static MonitorStopState fromStopEvent(String event, boolean alert) {
        if ("CHECKOUT".equals(event) || "IN_STOCK".equals(event)) return REVIEW_DAMAI;
        if (event != null && event.startsWith("ORDER_")) return REVIEW_ORDER;
        if ("PAUSED".equals(event) && !alert) return PAUSED;
        return NEEDS_ATTENTION;
    }

    /** Opening helper screens while already stopped must not erase a problem still to inspect. */
    public static MonitorStopState afterStop(MonitorStopState previous, MonitorStopState next,
                                             boolean wasRunning) {
        if (!wasRunning && next == PAUSED && previous != null && previous != NONE) return previous;
        return next == null ? NEEDS_ATTENTION : next;
    }

    public static MonitorStopState restore(String saved, long savedRevision, long currentRevision) {
        if (savedRevision != currentRevision || saved == null || saved.isEmpty()) return NONE;
        try { return valueOf(saved); }
        catch (IllegalArgumentException ignored) { return NEEDS_ATTENTION; }
    }
}
