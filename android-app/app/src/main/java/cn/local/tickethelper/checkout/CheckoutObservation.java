package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;

/** Same-frame facts from a strict visible-page adapter. Missing facts remain missing. */
public final class CheckoutObservation {
    public enum Kind { CONFIRMATION, PAYMENT_PENDING, INVENTORY_REJECTED, VERIFICATION, PAYMENT_OR_PAID, UNKNOWN }
    public final Kind kind;
    public final long observedAtMs, frameGeneration;
    public final int windowId;
    public final String packageName, eventTitle, city, date, time, selectedViewerBindingHash, relatedAttemptId;
    public final Long unitPriceFen, ticketSubtotalFen, payableFen, paymentDeadlineMs, orderCreatedAtMs;
    public final Integer quantity;
    public final boolean sameFrame, nativeWindowVerified, viewerSelectionVerified, noUnapprovedExtras,
            agreementsAccepted, uniqueNativeSubmitTarget, orderReferenceVerified, authoritativeInventoryRejection,
            orderCreationTimeVerified;
    public final UiItem submitTarget;
    public final CheckoutSubmitBinding submitBinding;
    /** Raw reference is ephemeral. Only its digest may enter persistent attempt storage. */
    public final String orderReference, paymentDeadlineText;
    private CheckoutObservation(Builder b) {
        kind=b.kind; observedAtMs=b.observedAtMs; frameGeneration=b.frameGeneration; windowId=b.windowId;
        packageName=b.packageName; eventTitle=b.eventTitle; city=b.city; date=b.date; time=b.time;
        selectedViewerBindingHash=b.selectedViewerBindingHash; relatedAttemptId=b.relatedAttemptId;
        unitPriceFen=b.unitPriceFen; ticketSubtotalFen=b.ticketSubtotalFen; payableFen=b.payableFen; quantity=b.quantity;
        paymentDeadlineMs=b.paymentDeadlineMs; orderCreatedAtMs=b.orderCreatedAtMs;
        orderCreationTimeVerified=b.orderCreationTimeVerified;
        sameFrame=b.sameFrame; nativeWindowVerified=b.nativeWindowVerified;
        viewerSelectionVerified=b.viewerSelectionVerified; noUnapprovedExtras=b.noUnapprovedExtras;
        agreementsAccepted=b.agreementsAccepted; uniqueNativeSubmitTarget=b.uniqueNativeSubmitTarget;
        orderReferenceVerified=b.orderReferenceVerified; authoritativeInventoryRejection=b.authoritativeInventoryRejection;
        submitTarget=b.submitTarget; submitBinding=b.submitBinding; orderReference=b.orderReference; paymentDeadlineText=b.paymentDeadlineText;
    }
    public static final class Builder {
        private final Kind kind; private final long observedAtMs;
        private long frameGeneration=-1; private int windowId=-1;
        private String packageName="",eventTitle="",city="",date="",time="",selectedViewerBindingHash="",relatedAttemptId="",
                orderReference="",paymentDeadlineText="";
        private Long unitPriceFen,ticketSubtotalFen,payableFen,paymentDeadlineMs,orderCreatedAtMs; private Integer quantity;
        private boolean sameFrame,nativeWindowVerified,viewerSelectionVerified,noUnapprovedExtras,agreementsAccepted,
                uniqueNativeSubmitTarget,orderReferenceVerified,authoritativeInventoryRejection,orderCreationTimeVerified;
        private UiItem submitTarget;
        private CheckoutSubmitBinding submitBinding;
        public Builder(Kind kind,long observedAtMs) { this.kind=kind==null?Kind.UNKNOWN:kind; this.observedAtMs=observedAtMs; }
        public Builder frame(String pkg,int windowId,long generation,boolean sameFrame,boolean verified) {
            packageName=CheckoutIdentity.clean(pkg); this.windowId=windowId; frameGeneration=generation;
            this.sameFrame=sameFrame; nativeWindowVerified=verified; return this;
        }
        public Builder target(String title,String city,String date,String time) {
            eventTitle=CheckoutIdentity.clean(title); this.city=CheckoutIdentity.clean(city);
            this.date=CheckoutIdentity.clean(date); this.time=CheckoutIdentity.clean(time); return this;
        }
        public Builder amounts(Long unit,Integer quantity,Long subtotal,Long payable) {
            unitPriceFen=unit; this.quantity=quantity; ticketSubtotalFen=subtotal; payableFen=payable; return this;
        }
        public Builder viewer(String hash,boolean uniqueSelected) {
            selectedViewerBindingHash=CheckoutIdentity.clean(hash); viewerSelectionVerified=uniqueSelected; return this;
        }
        public Builder extras(boolean noneUnapproved) { noUnapprovedExtras=noneUnapproved; return this; }
        public Builder agreements(boolean accepted) { agreementsAccepted=accepted; return this; }
        public Builder submit(UiItem target,boolean unique) { return submit(target,unique,null); }
        public Builder submit(UiItem target,boolean unique,CheckoutSubmitBinding binding) {
            submitTarget=target; uniqueNativeSubmitTarget=unique; submitBinding=binding; return this;
        }
        public Builder relatedAttempt(String id) { relatedAttemptId=CheckoutIdentity.clean(id); return this; }
        public Builder pendingOrder(String ref,boolean verified,Long deadline,String text) {
            orderReference=CheckoutIdentity.clean(ref); orderReferenceVerified=verified;
            paymentDeadlineMs=deadline; paymentDeadlineText=CheckoutIdentity.deadlineText(text); return this;
        }
        /** Only an exact creation timestamp independently read from the official order page. */
        public Builder orderCreatedAt(Long timestampMs,boolean verified) {
            orderCreatedAtMs=timestampMs; orderCreationTimeVerified=verified; return this;
        }
        public Builder inventoryRejection(boolean authoritative) { authoritativeInventoryRejection=authoritative; return this; }
        public CheckoutObservation build() { return new CheckoutObservation(this); }
    }
}
