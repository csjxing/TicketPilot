package cn.local.tickethelper.checkout;

public final class CheckoutDecision {
    public enum State { NEEDS_REVIEW, READY_TO_RESERVE, WAIT_RESULT, CONFIRMED_PENDING, MATCHING_PENDING_REVIEW, INVENTORY_REJECTED, OUTCOME_UNKNOWN }
    public final State state;
    public final String reason;
    public final VerifiedSubmitProposal proposal;
    public final VerifiedPendingOrder pendingOrder;
    CheckoutDecision(State state,String reason,VerifiedSubmitProposal proposal,VerifiedPendingOrder pendingOrder) {
        this.state=state; this.reason=reason; this.proposal=proposal; this.pendingOrder=pendingOrder;
    }
}
