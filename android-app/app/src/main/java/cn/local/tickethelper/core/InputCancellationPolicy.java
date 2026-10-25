package cn.local.tickethelper.core;

/** Pure cancellation decisions; connection readiness never grants permission to start a monitor. */
public final class InputCancellationPolicy {
    public enum Action { KEEP_IDLE_READY, KEEP_CANCELLING, INVALIDATE_AND_CANCEL }

    private InputCancellationPolicy() { }

    /**
     * A pending input always needs invalidation, even if the transport still reports ready.
     * An earlier cancellation owns its acknowledgement; a repeated pause must not replace it.
     * A connecting/unavailable transport is not a confirmed idle connection.
     */
    public static Action decide(boolean hasPendingInput, boolean cancelling, boolean connectedReady) {
        if (hasPendingInput) return Action.INVALIDATE_AND_CANCEL;
        if (cancelling) return Action.KEEP_CANCELLING;
        if (connectedReady) return Action.KEEP_IDLE_READY;
        return Action.INVALIDATE_AND_CANCEL;
    }
}
