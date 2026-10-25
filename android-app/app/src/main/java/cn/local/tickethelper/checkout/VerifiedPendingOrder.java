package cn.local.tickethelper.checkout;

public final class VerifiedPendingOrder {
    public final OrderTarget target;
    public final String orderReferenceHash,paymentDeadlineText;
    public final Long paymentDeadlineMs;
    public final Long orderCreatedAtMs;
    public final boolean orderCreationTimeVerified;
    public final long observedAtMs;
    public VerifiedPendingOrder(OrderTarget target,String orderReferenceHash,Long paymentDeadlineMs,String paymentDeadlineText,long observedAtMs) {
        this(target,orderReferenceHash,paymentDeadlineMs,paymentDeadlineText,observedAtMs,null,false);
    }
    public VerifiedPendingOrder(OrderTarget target,String orderReferenceHash,Long paymentDeadlineMs,String paymentDeadlineText,long observedAtMs,
                                Long orderCreatedAtMs,boolean orderCreationTimeVerified) {
        this.target=target; this.orderReferenceHash=CheckoutIdentity.clean(orderReferenceHash);
        this.paymentDeadlineMs=paymentDeadlineMs; this.paymentDeadlineText=CheckoutIdentity.deadlineText(paymentDeadlineText);
        this.observedAtMs=observedAtMs;
        this.orderCreatedAtMs=orderCreatedAtMs; this.orderCreationTimeVerified=orderCreationTimeVerified;
    }
}
