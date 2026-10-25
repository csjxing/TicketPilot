package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;

/** Ephemeral proposal. A durable one-shot store reservation is still required before any input. */
public final class VerifiedSubmitProposal {
    public final String grantId,configFingerprint,targetFingerprint;
    public final OrderTarget target;
    public final long observedAtMs,frameGeneration;
    public final int windowId;
    public final UiItem submitTarget;
    public final CheckoutSubmitBinding submitBinding;
    VerifiedSubmitProposal(LockAuthorization authorization,OrderTarget target,CheckoutObservation observation) {
        grantId=authorization.grantId; configFingerprint=authorization.configFingerprint;
        this.target=target; targetFingerprint=target.fingerprint(); observedAtMs=observation.observedAtMs;
        frameGeneration=observation.frameGeneration; windowId=observation.windowId; submitTarget=observation.submitTarget;
        submitBinding=observation.submitBinding;
    }
}
