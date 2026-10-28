package cn.local.tickethelper.checkout;

import java.util.List;

/**
 * Capabilities of an explicitly reviewed registry, for one exact package build.
 * A confirmation contract permits only one journalled submission. It does not establish
 * that the resulting official pending-payment page can be read or verified.
 */
public final class CheckoutCapabilities {
    public final boolean confirmationSubmission, pendingPaymentRead;

    private CheckoutCapabilities(boolean confirmation, boolean pending) {
        confirmationSubmission = confirmation;
        pendingPaymentRead = pending;
    }

    public boolean completeCheckoutRead() { return confirmationSubmission && pendingPaymentRead; }

    public static CheckoutCapabilities forVersion(List<CheckoutProfile> reviewedProfiles, String appVersion) {
        if (reviewedProfiles == null || appVersion == null || appVersion.isEmpty())
            return new CheckoutCapabilities(false, false);
        int confirmation = 0, pending = 0;
        for (CheckoutProfile profile : reviewedProfiles) {
            if (profile == null || !"cn.damai".equals(profile.packageName) || !appVersion.equals(profile.appVersion)) continue;
            if (profile.pages.containsKey(CheckoutObservation.Kind.CONFIRMATION)) confirmation++;
            if (profile.pages.containsKey(CheckoutObservation.Kind.PAYMENT_PENDING)) pending++;
        }
        // An ambiguous contract is unavailable, even before any native tree is inspected.
        return new CheckoutCapabilities(confirmation == 1, pending == 1);
    }
}
