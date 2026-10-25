package cn.local.tickethelper.core;

/** A bounded UI proposal. No action in this type can submit or pay for an order. */
public final class Decision {
    public enum State { CONFIG_INVALID, EXPIRED, BLOCKED, UNRELATED, UNKNOWN, READY, PROBING, OUT_OF_STOCK, IN_STOCK, TIER_SELECTED, CHECKOUT }
    public enum Action { NONE, BACK, OPEN_TICKETS, PROBE_TIER, SELECT_TIER, CONFIRM_TIER }
    public final State state;
    public final Action action;
    public final UiItem target;
    /** Generic reason code, suitable for sanitized history; contains no page text. */
    public final String reason;
    public final long observedAtMs;
    public Decision(State state, Action action, UiItem target, String reason) {
        this(state, action, target, reason, 0);
    }
    public Decision(State state, Action action, UiItem target, String reason, long observedAtMs) {
        this.state = state; this.action = action; this.target = target; this.reason = reason;
        this.observedAtMs = observedAtMs;
    }
}
