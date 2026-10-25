package cn.local.tickethelper.checkout;

import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/** An explicitly reviewed UI contract for one exact app version. No production IDs are guessed. */
public final class CheckoutProfile {
    public enum Role {
        EVENT_TITLE, CITY, SESSION_DATE, SESSION_TIME, UNIT_PRICE, QUANTITY, TICKET_SUBTOTAL, PAYABLE,
        VIEWER_COUNT, VIEWER_ROW, VIEWER_NAME, VIEWER_DOCUMENT, VIEWER_SELECTION,
        EXTRAS_STATUS, EXTRA_FEE, AGREEMENT, SUBMIT, ORDER_REFERENCE, ORDER_CREATED_AT,
        PAYMENT_DEADLINE, PAYMENT_COUNTDOWN
    }
    public static final class Selector {
        public enum Match { EXACT, VALUE, NODE }
        public final String resourceId, ancestorScopeId, exactLabel;
        public final Match match;
        public final Pattern extraction;
        /** EXACT labels are deliberate profile assertions, not substring matches. */
        public Selector(String resourceId, String ancestorScopeId, String exactLabel) {
            this(resourceId, ancestorScopeId, exactLabel, Match.EXACT, null);
        }
        private Selector(String id, String scope, String label, Match match, Pattern extraction) {
            this.resourceId = clean(id); this.ancestorScopeId = clean(scope);
            this.exactLabel = clean(label); this.match = match; this.extraction = extraction;
        }
        /** The regex must match the WHOLE native text and have one captured value. */
        public static Selector value(String id, String scope, String wholeTextRegex) {
            Pattern pattern = Pattern.compile(wholeTextRegex);
            if (pattern.matcher("").groupCount() != 1) throw new IllegalArgumentException("one_capture_required");
            return new Selector(id, scope, "", Match.VALUE, pattern);
        }
        /** Structural native row/checkbox selectors; never interpret their text as a fact. */
        public static Selector node(String id, String scope) {
            return new Selector(id, scope, "", Match.NODE, null);
        }
    }
    public static final class Page {
        public final CheckoutObservation.Kind kind;
        public final String rootResourceId;
        public final Selector marker;
        private Page(CheckoutObservation.Kind kind, String root, Selector marker) {
            this.kind = kind; rootResourceId = root; this.marker = marker;
        }
    }
    public final String packageName, appVersion;
    public final ZoneId zoneId;
    public final Map<CheckoutObservation.Kind, Page> pages;
    public final Map<Role, Selector> fields;
    private CheckoutProfile(Builder builder) {
        packageName = builder.packageName; appVersion = builder.appVersion; zoneId = builder.zoneId;
        pages = Collections.unmodifiableMap(new EnumMap<>(builder.pages));
        fields = Collections.unmodifiableMap(new EnumMap<>(builder.fields));
    }
    public static final class Builder {
        private final String packageName, appVersion;
        private final ZoneId zoneId;
        private final Map<CheckoutObservation.Kind, Page> pages = new EnumMap<>(CheckoutObservation.Kind.class);
        private final Map<Role, Selector> fields = new EnumMap<>(Role.class);
        public Builder(String packageName, String appVersion, ZoneId zoneId) {
            this.packageName = clean(packageName); this.appVersion = clean(appVersion); this.zoneId = zoneId;
            if (this.packageName.isEmpty() || this.appVersion.isEmpty() || zoneId == null)
                throw new IllegalArgumentException("profile_identity_missing");
        }
        public Builder page(CheckoutObservation.Kind kind, String rootResourceId, Selector marker) {
            if (kind != CheckoutObservation.Kind.CONFIRMATION && kind != CheckoutObservation.Kind.PAYMENT_PENDING)
                throw new IllegalArgumentException("unsupported_page_kind");
            requireId(rootResourceId); validate(marker);
            if (marker.match != Selector.Match.EXACT || marker.exactLabel.isEmpty())
                throw new IllegalArgumentException("explicit_page_label_required");
            if (pages.put(kind, new Page(kind, rootResourceId, marker)) != null)
                throw new IllegalArgumentException("duplicate_page_kind");
            return this;
        }
        public Builder field(Role role, Selector selector) {
            if (role == null) throw new IllegalArgumentException("role_missing");
            validate(selector);
            if (fields.put(role, selector) != null) throw new IllegalArgumentException("duplicate_role");
            return this;
        }
        public CheckoutProfile build() {
            if (pages.isEmpty()) throw new IllegalArgumentException("pages_missing");
            if (fields.containsKey(Role.PAYMENT_DEADLINE) && fields.containsKey(Role.PAYMENT_COUNTDOWN))
                throw new IllegalArgumentException("choose_one_deadline_representation");
            return new CheckoutProfile(this);
        }
        private void validate(Selector selector) {
            if (selector == null) throw new IllegalArgumentException("selector_missing");
            requireId(selector.resourceId); requireId(selector.ancestorScopeId);
        }
        private void requireId(String id) {
            if (id == null || !id.startsWith(packageName + ":id/") || id.length() <= packageName.length() + 4)
                throw new IllegalArgumentException("explicit_package_resource_id_required");
        }
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
