package cn.local.tickethelper.checkout;

/** Any persisted attempt is a submission barrier, including after configuration or process changes. */
public final class OrderAttempt {
    public enum State { RESERVED, AWAITING_RESULT, CONFIRMED_PENDING, INVENTORY_REJECTED, OUTCOME_UNKNOWN }
    public final String attemptId,grantId,configFingerprint,targetFingerprint,orderReferenceHash,paymentDeadlineText;
    public final OrderTarget target;
    public final long reservedAtMs,updatedAtMs;
    public final State state;
    public final Long paymentDeadlineMs;
    /** Durable consumption evidence; UNKNOWN alone does not imply any submission was authorized. */
    public final boolean submissionAuthorized;
    public OrderAttempt(String attemptId,String grantId,String configFingerprint,String targetFingerprint,OrderTarget target,
                        long reservedAtMs,long updatedAtMs,State state,String orderReferenceHash,Long paymentDeadlineMs,String paymentDeadlineText) {
        this(attemptId,grantId,configFingerprint,targetFingerprint,target,reservedAtMs,updatedAtMs,state,
                orderReferenceHash,paymentDeadlineMs,paymentDeadlineText,
                state == State.AWAITING_RESULT || state == State.CONFIRMED_PENDING);
    }
    public OrderAttempt(String attemptId,String grantId,String configFingerprint,String targetFingerprint,OrderTarget target,
                        long reservedAtMs,long updatedAtMs,State state,String orderReferenceHash,Long paymentDeadlineMs,String paymentDeadlineText,
                        boolean submissionAuthorized) {
        this.attemptId=CheckoutIdentity.clean(attemptId); this.grantId=CheckoutIdentity.clean(grantId);
        this.configFingerprint=CheckoutIdentity.clean(configFingerprint); this.targetFingerprint=CheckoutIdentity.clean(targetFingerprint);
        this.target=target; this.reservedAtMs=reservedAtMs; this.updatedAtMs=updatedAtMs; this.state=state;
        this.orderReferenceHash=CheckoutIdentity.clean(orderReferenceHash); this.paymentDeadlineMs=paymentDeadlineMs;
        this.paymentDeadlineText=CheckoutIdentity.deadlineText(paymentDeadlineText);
        this.submissionAuthorized=submissionAuthorized;
    }
    public static OrderAttempt reserved(String attemptId,VerifiedSubmitProposal proposal,long nowMs) {
        return new OrderAttempt(attemptId,proposal.grantId,proposal.configFingerprint,proposal.targetFingerprint,
                proposal.target,nowMs,nowMs,State.RESERVED,"",null,"");
    }
}
