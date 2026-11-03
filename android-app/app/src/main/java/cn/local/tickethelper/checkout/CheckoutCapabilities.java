package cn.local.tickethelper.checkout;

import java.util.List;

/**
 * Capabilities of an explicitly reviewed registry, for one exact package build.
 * A confirmation contract permits only one journalled submission. It does not establish
 * that the resulting official pending-payment page can be read or verified.
 */
public final class CheckoutCapabilities {
    public final boolean confirmationSubmission, pendingPaymentRead, confirmationOnly;

    private CheckoutCapabilities(boolean confirmation, boolean pending) {
        confirmationSubmission = confirmation;
        pendingPaymentRead = pending;
        confirmationOnly = confirmation && !pending;
    }

    public boolean completeCheckoutRead() { return confirmationSubmission && pendingPaymentRead; }

    public static CheckoutCapabilities forVersion(List<CheckoutProfile> reviewedProfiles, String appVersion) {
        if (reviewedProfiles == null || appVersion == null || appVersion.isEmpty())
            return new CheckoutCapabilities(false, false);
        int confirmation = 0, pending = 0;
        for (CheckoutProfile profile : reviewedProfiles) {
            if (profile == null || !"cn.damai".equals(profile.packageName)) continue;
            if (!appVersion.equals(profile.appVersion) && !matchesMajorVersion(appVersion, profile.appVersion)) continue;
            if (profile.pages.containsKey(CheckoutObservation.Kind.CONFIRMATION)) confirmation++;
            if (profile.pages.containsKey(CheckoutObservation.Kind.PAYMENT_PENDING)) pending++;
        }
        // An ambiguous contract is unavailable, even before any native tree is inspected.
        return new CheckoutCapabilities(confirmation == 1, pending == 1);
    }

    /** Match if the installed version shares the same major.minor.patch prefix. */
    private static boolean matchesMajorVersion(String installed, String profile) {
        if (installed == null || profile == null || installed.isEmpty() || profile.isEmpty()) return false;
        String instVer = installed.contains("#") ? installed.substring(0, installed.indexOf('#')) : installed;
        String profVer = profile.contains("#") ? profile.substring(0, profile.indexOf('#')) : profile;
        if (instVer.isEmpty() || profVer.isEmpty()) return false;
        String[] instParts = instVer.split("\\.");
        String[] profParts = profVer.split("\\.");
        if (instParts.length < 3 || profParts.length < 3) return false;
        return instParts[0].equals(profParts[0]) && instParts[1].equals(profParts[1]) && instParts[2].equals(profParts[2]);
    }
}
