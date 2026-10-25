package cn.local.tickethelper.core;

/** Routine polling stays paced; an already verified purchase-selection step can advance promptly. */
public final class ActionPacing {
    private ActionPacing() { }

    public static boolean mayDispatch(Decision decision, long nowMillis, long nowElapsed, long nextActionAt, boolean retryPending) {
        if (decision == null || decision.action == null || decision.action == Decision.Action.NONE) return false;
        if (nowElapsed >= nextActionAt) return true;
        // Only a fresh verified selection can skip idle waiting. The service still rebinds and checks
        // the live target, foreground, generation and single-input/transition barriers before dispatch.
        long age = nowMillis - decision.observedAtMs;
        return !retryPending && decision.action == Decision.Action.CONFIRM_TIER && decision.state == Decision.State.TIER_SELECTED
                && decision.observedAtMs > 0 && age >= 0 && age < 3000
                && decision.target != null && decision.target.hasBounds();
    }
}
