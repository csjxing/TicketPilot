package cn.local.tickethelper.catalog;

/** Bounded foreground handoff. READY authorizes one fresh read, never a tap or a page identity. */
public final class CatalogHandoff {
    public enum Result { WAITING, READY, TIMED_OUT, STATE_CHANGED, CANCELLED, FINISHED }
    private final long deadlineMs, settleMs;
    private long foregroundSinceMs = -1;
    private boolean cancelled, delivered;

    public CatalogHandoff(long startedAtMs, long timeoutMs, long settleMs) {
        if (startedAtMs < 0 || timeoutMs <= 0 || settleMs < 0 || settleMs >= timeoutMs
                || startedAtMs > Long.MAX_VALUE - timeoutMs) throw new IllegalArgumentException("Invalid handoff budget");
        this.deadlineMs = startedAtMs + timeoutMs;
        this.settleMs = settleMs;
    }

    public Result observe(long nowMs, boolean targetForeground, boolean unlocked, boolean monitoring) {
        if (cancelled) return Result.CANCELLED;
        if (delivered) return Result.FINISHED;
        if (nowMs >= deadlineMs) { delivered = true; return Result.TIMED_OUT; }
        if (!unlocked || monitoring) { delivered = true; return Result.STATE_CHANGED; }
        if (!targetForeground) { foregroundSinceMs = -1; return Result.WAITING; }
        if (foregroundSinceMs < 0) foregroundSinceMs = nowMs;
        if (nowMs - foregroundSinceMs < settleMs) return Result.WAITING;
        delivered = true;
        return Result.READY;
    }

    public void cancel() { cancelled = true; }
}
