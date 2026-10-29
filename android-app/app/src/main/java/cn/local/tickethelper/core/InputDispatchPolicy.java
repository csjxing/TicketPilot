package cn.local.tickethelper.core;

/** Closed, public reason codes. No page text, exception message or private device data is retained. */
public final class InputDispatchPolicy {
    private InputDispatchPolicy() {}

    public enum Rejection {
        NONE,
        REMOTE_GUARD_REJECTED,
        CALLER_UID_INVALID, GUARD_ALREADY_USED, DEADLINE_EXPIRED,
        BRIDGE_BINDING_CHANGED, BRIDGE_GENERATION_CHANGED, BRIDGE_PENDING_CHANGED,
        BRIDGE_RESULT_DELIVERED, BRIDGE_NOT_READY, BRIDGE_SCREEN_UNVERIFIED,
        MAIN_GUARD_TIMEOUT, MAIN_GUARD_INTERRUPTED, CALLER_GUARD_EXCEPTION, CALLER_GUARD_REJECTED,
        MONITOR_STOPPED, MONITOR_GENERATION_CHANGED, PAGE_CONTENT_CHANGED, PAGE_BOUNDARY_CHANGED,
        SCREEN_LOCKED, FOREGROUND_CHANGED, TIMING_DISALLOWED,
        OBSERVATION_STALE, OBSERVATION_FROM_FUTURE,
        TARGET_REFRESH_FAILED, TARGET_HIDDEN, TARGET_DISABLED, TARGET_WINDOW_CHANGED,
        TARGET_BOUNDS_CHANGED, TARGET_WINDOW_MISSING, TARGET_WINDOW_INACTIVE, TARGET_DISPLAY_CHANGED,
        SCROLL_NATIVE_CHANGED,
        QUANTITY_CHANGED, SENSITIVE_TARGET, TARGET_PATH_UNVERIFIED;

        public String code() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /**
     * A returned guard rejection proves the remote service did not start input. Only harmless
     * trial/price selections may discard that stale proposal and obtain a wholly new observation.
     * This never authorizes the old target or retries a plus, confirmation or order submission.
     */
    public static boolean mayReobserve(Decision.Action action, boolean success, boolean outcomeUnknown,
                                       String resultCode, Rejection reason) {
        return !success && !outcomeUnknown && "dispatch_guard_rejected".equals(resultCode)
                && (reason == Rejection.PAGE_CONTENT_CHANGED || reason == Rejection.PAGE_BOUNDARY_CHANGED)
                && (action == Decision.Action.PROBE_TIER || action == Decision.Action.SELECT_TIER
                    || action == Decision.Action.SELECT_SESSION);
    }

    /** A rejected reversible entry can start a fresh read, never replay old coordinates. */
    public static boolean mayReobserveNavigation(Decision decision, boolean success, boolean unknown,
                                                 String code, Rejection reason) {
        return DetailOcrNavigation.isProposal(decision) && !success && !unknown
                && "dispatch_guard_rejected".equals(code)
                && (reason == Rejection.PAGE_CONTENT_CHANGED || reason == Rejection.MAIN_GUARD_TIMEOUT);
    }

    /** Only this exact service result proves that the scroll command was never started. */
    public static boolean mayReobserveDetailScroll(Decision.Action action, boolean success, boolean outcomeUnknown,
                                                    String resultCode, Rejection reason, DetailScrollPolicy.FrameCheck frame) {
        return action == Decision.Action.SCROLL_DETAIL && !success && !outcomeUnknown
                && "dispatch_guard_rejected".equals(resultCode) && reason == Rejection.SCROLL_NATIVE_CHANGED
                && DetailScrollPolicy.retryableNativeChange(frame);
    }
}
