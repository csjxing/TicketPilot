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
        boolean verifiedAdvance = decision.action == Decision.Action.CONFIRM_TIER && decision.state == Decision.State.TIER_SELECTED;
        boolean quantityAdvance = QuantityTransition.isIncreaseProposal(decision);
        // This reason is emitted only after the engine verifies the configured event/session and
        // an explicit available label. A plain price or trial PROBE remains ordinarily paced.
        boolean availableSelection = decision.action == Decision.Action.SELECT_TIER && decision.state == Decision.State.IN_STOCK
                && "explicit_available_label".equals(decision.reason) && decision.target != null && decision.target.enabled;
        boolean sameRoundReentry = decision.action == Decision.Action.OPEN_TICKETS && decision.state == Decision.State.READY
                && "verified_return_detail_entry".equals(decision.reason) && decision.target != null && decision.target.enabled;
        boolean verifiedSession = decision.action == Decision.Action.SELECT_SESSION && decision.state == Decision.State.PROBING
                && "target_session_cell_verified".equals(decision.reason);
        boolean firstEntry = decision.action == Decision.Action.OPEN_TICKETS && decision.state == Decision.State.READY
                && "verified_initial_detail_entry".equals(decision.reason);
        boolean verifiedProbe = decision.action == Decision.Action.PROBE_TIER && decision.state == Decision.State.PROBING
                && "trial_selection_inventory_unknown".equals(decision.reason)
                && decision.selectionEvidence != null && !decision.selectionEvidence.sessionUnavailable
                && decision.selectionEvidence.observedAtMs == decision.observedAtMs;
        return !retryPending && (verifiedAdvance || quantityAdvance || availableSelection || sameRoundReentry
                || verifiedSession || firstEntry || verifiedProbe)
                && decision.observedAtMs > 0 && age >= 0 && age < 3000
                && decision.target != null && decision.target.hasBounds();
    }
}
