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

    /** Empty until both page types have real-device evidence for one exact package build. */
    public static boolean isLiveVerified() {
        return VERIFIED_PROFILES.stream().anyMatch(profile -> supportedVersion(profile.appVersion));
    }

    public static boolean supportsInstalledVersion(Context context) {
        return supportedVersion(installedVersion(context));
    }

    /** Final row binding after locating the actual checkbox, before any selection input. */
    public static boolean viewerControlMatches(Context context, AccessibilityNodeInfo control,
                                                String name, String suffix, long deadlineElapsedMs) {
        String version = installedVersion(context);
        if (!supportedVersion(version)) return false;
        int matching = 0;
        for (CheckoutProfile profile : VERIFIED_PROFILES) {
            if (profile.appVersion.equals(version) && profile.pages.containsKey(CheckoutObservation.Kind.CONFIRMATION)
                    && CheckoutViewerBinding.matches(control, profile, name, suffix, deadlineElapsedMs)) matching++;
        }
        return matching == 1;
    }

    public static CheckoutParseResult read(AccessibilityService service, LongSupplier generation,
                                           String relatedAttemptId, String viewerName, String viewerSuffix) {
        String version = installedVersion(service);
        if (!supportedVersion(version)) return unavailable("checkout_version_not_verified", relatedAttemptId);
        CheckoutFrame frame = CheckoutFrameReader.capture(service, version, generation);
        CheckoutPageParser.ViewerResolver resolver = (rows, complete) -> ViewerMatcher.match(viewerName, viewerSuffix, rows, complete);
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
        if (version.isEmpty()) return false;
        boolean confirmation = false, pending = false;
        for (CheckoutProfile profile : VERIFIED_PROFILES) {
            if (!"cn.damai".equals(profile.packageName) || !version.equals(profile.appVersion)) continue;
            confirmation |= profile.pages.containsKey(CheckoutObservation.Kind.CONFIRMATION);
            pending |= profile.pages.containsKey(CheckoutObservation.Kind.PAYMENT_PENDING);
        }
        return confirmation && pending;
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
