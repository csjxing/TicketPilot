package cn.local.tickethelper.core;

/** Screenshot cadence, not a server polling interval. OCR processing consumes this wait. */
public final class CapturePacing {
    public static final long INTERVAL_MS = 400;
    private CapturePacing() {}
    public static long remainingMs(long lastCapture, long now) {
        if (lastCapture <= 0) return 0;
        if (now < lastCapture) return INTERVAL_MS;
        return Math.max(0, INTERVAL_MS - (now-lastCapture));
    }
}
