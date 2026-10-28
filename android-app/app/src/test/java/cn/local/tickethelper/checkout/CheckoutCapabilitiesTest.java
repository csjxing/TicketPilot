package cn.local.tickethelper.checkout;

import java.time.ZoneId;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic contracts here never enter the production adapter registry. */
public class CheckoutCapabilitiesTest {
    private static final String VERSION = "synthetic-capability-build";

    @Test public void emptyOrDifferentBuildNeverEnablesSubmission() {
        assertFalse(CheckoutCapabilities.forVersion(List.of(), VERSION).confirmationSubmission);
        assertFalse(CheckoutCapabilities.forVersion(List.of(profile(CheckoutObservation.Kind.CONFIRMATION)), "new-build").confirmationSubmission);
        assertFalse(CheckoutCapabilities.forVersion(List.of(profile(CheckoutObservation.Kind.CONFIRMATION)), "").confirmationSubmission);
    }

    @Test public void confirmationOnlyDoesNotClaimPendingOrCompleteReading() {
        CheckoutCapabilities capability = CheckoutCapabilities.forVersion(List.of(profile(CheckoutObservation.Kind.CONFIRMATION)), VERSION);
        assertTrue(capability.confirmationSubmission);
        assertFalse(capability.pendingPaymentRead);
        assertFalse(capability.completeCheckoutRead());
    }

    @Test public void pendingOnlyNeverEnablesSubmission() {
        CheckoutCapabilities capability = CheckoutCapabilities.forVersion(List.of(profile(CheckoutObservation.Kind.PAYMENT_PENDING)), VERSION);
        assertFalse(capability.confirmationSubmission);
        assertTrue(capability.pendingPaymentRead);
        assertFalse(capability.completeCheckoutRead());
    }

    @Test public void bothReviewedPageContractsEnableCompleteReadCapabilityOnlyForThatBuild() {
        CheckoutCapabilities capability = CheckoutCapabilities.forVersion(List.of(profile(CheckoutObservation.Kind.CONFIRMATION),
                profile(CheckoutObservation.Kind.PAYMENT_PENDING)), VERSION);
        assertTrue(capability.confirmationSubmission);
        assertTrue(capability.pendingPaymentRead);
        assertTrue(capability.completeCheckoutRead());
    }

    @Test public void duplicateConfirmationContractsFailClosed() {
        CheckoutProfile confirmation = profile(CheckoutObservation.Kind.CONFIRMATION);
        CheckoutCapabilities capability = CheckoutCapabilities.forVersion(List.of(confirmation, confirmation,
                profile(CheckoutObservation.Kind.PAYMENT_PENDING)), VERSION);
        assertFalse(capability.confirmationSubmission);
        assertFalse(capability.completeCheckoutRead());
    }

    @Test public void duplicatePendingDoesNotMislabelResultReaderAsVerified() {
        CheckoutProfile pending = profile(CheckoutObservation.Kind.PAYMENT_PENDING);
        CheckoutCapabilities capability = CheckoutCapabilities.forVersion(List.of(profile(CheckoutObservation.Kind.CONFIRMATION), pending, pending), VERSION);
        assertTrue(capability.confirmationSubmission);
        assertFalse(capability.pendingPaymentRead);
        assertFalse(capability.completeCheckoutRead());
    }

    private static CheckoutProfile profile(CheckoutObservation.Kind kind) {
        String root = "cn.damai:id/synthetic_capability_root", marker = "cn.damai:id/synthetic_capability_marker";
        return new CheckoutProfile.Builder("cn.damai", VERSION, ZoneId.of("Asia/Shanghai"))
                .page(kind, root, new CheckoutProfile.Selector(marker, root,
                        kind == CheckoutObservation.Kind.CONFIRMATION ? "确认购买" : "待付款")).build();
    }
}
