package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.MonitorConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One explicit user grant for this exact configuration. Saving LOCK_ORDER does not create a grant. */
public final class LockAuthorization {
    public final String grantId, eventTitle, viewerBindingHash, configFingerprint;
    public final MonitorConfig config;
    public final long authorizedAtMs, expiresAtMs;
    public LockAuthorization(String grantId, MonitorConfig config, String eventTitle, String viewerBindingHash,
                             long authorizedAtMs, long expiresAtMs) {
        this.grantId = CheckoutIdentity.clean(grantId); this.config = config;
        this.eventTitle = CheckoutIdentity.clean(eventTitle); this.viewerBindingHash = CheckoutIdentity.clean(viewerBindingHash);
        this.authorizedAtMs = authorizedAtMs; this.expiresAtMs = expiresAtMs;
        this.configFingerprint = configFingerprint(config);
    }
    public boolean validFor(MonitorConfig current, long nowMs) {
        if (current == null || config == null || !current.validate().isEmpty()
                || current.mode != MonitorConfig.Mode.LOCK_ORDER || !grantId.matches("[a-zA-Z0-9_.:-]{1,128}")
                || eventTitle.length() < 5 || eventTitle.length() > 180 || !viewerBindingHash.matches("[0-9a-f]{64}")
                || authorizedAtMs <= 0 || nowMs < authorizedAtMs || nowMs >= expiresAtMs
                || expiresAtMs > current.deadline.toEpochMilli() || !configFingerprint.equals(configFingerprint(current))) return false;
        return CheckoutIdentity.title(eventTitle,current.city).contains(CheckoutIdentity.title(current.eventKeyword,""))
                && !LocalDate.parse(current.date).isBefore(Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
    }
    public static String configFingerprint(MonitorConfig config) {
        if (config == null) return "";
        return CheckoutIdentity.hash(CheckoutIdentity.part(config.eventKeyword) + CheckoutIdentity.part(config.city)
                + CheckoutIdentity.part(config.date) + CheckoutIdentity.part(config.time) + CheckoutIdentity.part(config.prices.toString())
                + "|" + config.quantity + "|" + config.maxTotal + "|" + config.deadline + "|"
                + config.minIntervalMs + "|" + config.maxIntervalMs + "|" + config.mode);
    }
    /** Exact visible name and user-specified suffix; masked-name guesses are not supported. */
    public static String viewerBindingHash(String displayName, String documentSuffix) {
        String name = CheckoutIdentity.clean(displayName), suffix = CheckoutIdentity.clean(documentSuffix).toUpperCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > 80 || (!suffix.isEmpty() && !suffix.matches("[0-9A-Z]{2,6}"))) return "";
        return CheckoutIdentity.hash(CheckoutIdentity.part(name) + CheckoutIdentity.part(suffix));
    }

    /** Same single-person digest as older versions; a two-person party is order-independent. */
    public static String viewerBindingsHash(List<ViewerMatcher.RequestedViewer> viewers) {
        if (!ViewerMatcher.validRequests(viewers)) return "";
        List<String> bindings = new ArrayList<>();
        for (ViewerMatcher.RequestedViewer viewer : viewers)
            bindings.add(viewerBindingHash(viewer.displayName, viewer.documentSuffix));
        if (bindings.size() == 1) return bindings.get(0);
        Collections.sort(bindings);
        return CheckoutIdentity.hash("tickethelper-viewers-v1|" + bindings.size() + "|"
                + CheckoutIdentity.part(bindings.get(0)) + CheckoutIdentity.part(bindings.get(1)));
    }
}
