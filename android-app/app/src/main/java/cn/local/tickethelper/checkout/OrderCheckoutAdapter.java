package cn.local.tickethelper.checkout;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Bridges native trees into version-specific, offline-testable checkout parsing.
 * Only reviewed real-device profiles may enter this registry; synthetic fixtures stay in tests.
 */
public final class OrderCheckoutAdapter {
    private OrderCheckoutAdapter() { }
    private static final List<CheckoutProfile> VERIFIED_PROFILES = List.of();

    /** Complete page-reading capability; this is not evidence that an order has been created. */
    public static boolean isLiveVerified() {
        return VERIFIED_PROFILES.stream().anyMatch(profile -> supportedVersion(profile.appVersion));
    }

    public static boolean supportsInstalledVersion(Context context) {
        return supportedVersion(installedVersion(context));
    }

    /** Reviewed confirmation only. A missing pending profile must remain an unknown result. */
    public static boolean isConfirmationSubmissionVerified() {
        return VERIFIED_PROFILES.stream().anyMatch(profile -> confirmationSupportedVersion(profile.appVersion));
    }

    public static boolean supportsInstalledConfirmationSubmission(Context context) {
        return confirmationSupportedVersion(installedVersion(context));
    }

    public static boolean supportsInstalledPendingPaymentRead(Context context) {
        return CheckoutCapabilities.forVersion(VERIFIED_PROFILES, installedVersion(context)).pendingPaymentRead;
    }

    /** Match the frozen no-ID contract back to one verified current-version profile before live rebinding. */
    public static AccessibilityNodeInfo findSubmitTarget(AccessibilityService service,VerifiedSubmitProposal proposal,
                                                          LongSupplier generation,long deadlineElapsedMs) {
        if (proposal == null || generation == null || generation.getAsLong() != proposal.frameGeneration) return null;
        String version=installedVersion(service);
        if (!confirmationSupportedVersion(version)) return null;
        if (proposal.submitBinding == null)
            return CheckoutNativeTarget.find(service,proposal.submitTarget,proposal.windowId,deadlineElapsedMs);
        int matches=0;
        for (CheckoutProfile profile:VERIFIED_PROFILES)
            if (version.equals(profile.appVersion) && proposal.submitBinding.matchesProfile(profile)) matches++;
        if (matches != 1) return null;
        return CheckoutNativeTarget.findStructuralSubmit(service,proposal.submitTarget,proposal.windowId,
                proposal.submitBinding,generation,proposal.frameGeneration,deadlineElapsedMs);
    }

    /** Final row binding after locating the actual checkbox, before any selection input. */
    public static boolean viewerControlMatches(Context context, AccessibilityNodeInfo control,
                                                String name, String suffix, long deadlineElapsedMs) {
        return viewerControlMatches(context, control, List.of(new ViewerMatcher.RequestedViewer(name, suffix)), deadlineElapsedMs);
    }

    public static boolean viewerControlMatches(Context context, AccessibilityNodeInfo control,
                                                List<ViewerMatcher.RequestedViewer> viewers, long deadlineElapsedMs) {
        if (!ViewerMatcher.validRequests(viewers)) return false;
        String version = installedVersion(context);
        if (!confirmationSupportedVersion(version)) return false;
        int matching = 0;
        for (CheckoutProfile profile : VERIFIED_PROFILES) {
            if (!profile.appVersion.equals(version) || !profile.pages.containsKey(CheckoutObservation.Kind.CONFIRMATION)) continue;
            int identities = 0;
            for (ViewerMatcher.RequestedViewer viewer : viewers)
                if (CheckoutViewerBinding.matches(control, profile, viewer.displayName, viewer.documentSuffix, deadlineElapsedMs)) identities++;
            if (identities > 1) return false;
            if (identities == 1) matching++;
        }
        return matching == 1;
    }

    public static CheckoutParseResult read(AccessibilityService service, LongSupplier generation,
                                           String relatedAttemptId, String viewerName, String viewerSuffix) {
        return read(service, generation, relatedAttemptId, List.of(new ViewerMatcher.RequestedViewer(viewerName, viewerSuffix)));
    }

    public static CheckoutParseResult read(AccessibilityService service, LongSupplier generation,
                                           String relatedAttemptId, List<ViewerMatcher.RequestedViewer> viewers) {
        if (!ViewerMatcher.validRequests(viewers)) return unavailable("invalid_viewer_config", relatedAttemptId);
        List<ViewerMatcher.RequestedViewer> requested = List.copyOf(viewers);
        String version = installedVersion(service);
        if (!confirmationSupportedVersion(version)) return unavailable("checkout_version_not_verified", relatedAttemptId);
        CheckoutFrame frame = CheckoutFrameReader.capture(service, version, generation);
        CheckoutPageParser.ViewerResolver resolver = (rows, complete) -> ViewerMatcher.matchMany(requested, rows, complete);
        CheckoutParseResult selected = null;
        for (CheckoutProfile profile : VERIFIED_PROFILES) {
            if (!profile.appVersion.equals(version)) continue;
            CheckoutParseResult result = CheckoutPageParser.parse(frame, profile, System.currentTimeMillis(), resolver, relatedAttemptId);
            if (result.status == CheckoutParseResult.Status.UNSUPPORTED) continue;
            if (selected != null) return unavailable("checkout_profile_ambiguous", relatedAttemptId);
            selected = result;
        }
        return selected == null ? unavailable("checkout_page_not_supported", relatedAttemptId) : selected;
    }

    private static boolean supportedVersion(String version) {
        return CheckoutCapabilities.forVersion(VERIFIED_PROFILES, version).completeCheckoutRead();
    }

    private static boolean confirmationSupportedVersion(String version) {
        return CheckoutCapabilities.forVersion(VERIFIED_PROFILES, version).confirmationSubmission;
    }

    private static String installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("cn.damai", 0);
            return info.versionName == null ? "" : info.versionName + "#" + info.getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) { return ""; }
    }

    private static CheckoutParseResult unavailable(String reason, String attemptId) {
        CheckoutObservation observation = new CheckoutObservation.Builder(CheckoutObservation.Kind.UNKNOWN, System.currentTimeMillis())
                .relatedAttempt(attemptId).build();
        return new CheckoutParseResult(CheckoutParseResult.Status.UNSUPPORTED, observation, reason, null);
    }
}
