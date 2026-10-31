package cn.local.tickethelper.checkout;

import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/** An explicitly reviewed UI contract for one exact app version. No production IDs are guessed. */
public final class CheckoutProfile {
    public enum Role {
        EVENT_TITLE, CITY, SESSION_DATE, SESSION_TIME, SESSION_DATETIME, UNIT_PRICE, QUANTITY, UNIT_PRICE_QUANTITY, TICKET_SUBTOTAL, PAYABLE,
        VIEWER_COUNT, VIEWER_ROW, VIEWER_NAME, VIEWER_DOCUMENT, VIEWER_SELECTION,
        EXTRAS_STATUS, EXTRA_FEE, AGREEMENT, SUBMIT, ORDER_REFERENCE, ORDER_CREATED_AT,
        PAYMENT_DEADLINE, PAYMENT_COUNTDOWN
    }
    public static final class Selector {
        public enum Match { EXACT, PUBLIC_LABEL, VALUE, PRICE_QUANTITY, NODE }
        public final String resourceId, ancestorScopeId, exactLabel, relativeChildPath;
        public final Match match;
        public final Pattern extraction;
        /** EXACT labels are deliberate profile assertions, not substring matches. */
        public Selector(String resourceId, String ancestorScopeId, String exactLabel) {
            this(resourceId, ancestorScopeId, exactLabel, Match.EXACT, null);
        }
        private Selector(String id, String scope, String label, Match match, Pattern extraction) {
            this(id, scope, label, match, extraction, "");
        }
        private Selector(String id, String scope, String label, Match match, Pattern extraction, String path) {
            this.resourceId = clean(id); this.ancestorScopeId = clean(scope);
            this.exactLabel = clean(label); this.match = match; this.extraction = extraction;
            this.relativeChildPath = clean(path);
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
        /** Public no-ID text only, at one reviewed child path beneath a unique named ancestor. */
        public static Selector anchoredValue(String ancestorId, String childPath, String wholeTextRegex) {
            String path = childPath(childPath);
            Pattern pattern = Pattern.compile(wholeTextRegex);
            if (pattern.matcher("").groupCount() != 1) throw new IllegalArgumentException("one_capture_required");
            return new Selector("", ancestorId, "", Match.VALUE, pattern, path);
        }
        /** One native ticket line, with exactly two whole-text captures: unit price, then quantity. */
        public static Selector priceQuantity(String id, String scope, String wholeTextRegex) {
            return priceQuantity(id, scope, "", wholeTextRegex);
        }
        public static Selector anchoredPriceQuantity(String ancestorId, String childPath, String wholeTextRegex) {
            return priceQuantity("", ancestorId, childPath(childPath), wholeTextRegex);
        }
        private static Selector priceQuantity(String id, String scope, String path, String regex) {
            Pattern pattern = Pattern.compile(regex);
            if (pattern.matcher("").groupCount() != 2) throw new IllegalArgumentException("price_quantity_two_captures_required");
            return new Selector(id, scope, "", Match.PRICE_QUANTITY, pattern, path);
        }
        /** Exact public state/terms text at a reviewed path; this never authorizes an input. */
        public static Selector anchoredPublicLabel(String ancestorId, String childPath, String exactLabel) {
            if (clean(exactLabel).isEmpty()) throw new IllegalArgumentException("explicit_public_label_required");
            return new Selector("", ancestorId, exactLabel, Match.PUBLIC_LABEL, null, childPath(childPath));
        }
        /** The only supported no-ID action. It still needs a verified version and live rebinding. */
        public static Selector anchoredSubmit(String ancestorId, String childPath) {
            return new Selector("", ancestorId, "立即提交", Match.EXACT, null, childPath(childPath));
        }
        private static String childPath(String childPath) {
            String path = clean(childPath);
            if (!path.matches("(?:0|[1-9][0-9]{0,2})(?:/(?:0|[1-9][0-9]{0,2})){0,15}"))
                throw new IllegalArgumentException("explicit_child_path_required");
            return path;
        }
        public boolean isAnchored() { return !relativeChildPath.isEmpty(); }
        public boolean isAnchoredValue() { return isAnchored() && (match == Match.VALUE || match == Match.PRICE_QUANTITY); }
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
    /** Explicit contract of the observed native 9.0.37 confirmation layout, never a generic fallback. */
    public final boolean native937Confirmation;
    private CheckoutProfile(Builder builder) {
        packageName = builder.packageName; appVersion = builder.appVersion; zoneId = builder.zoneId;
        pages = Collections.unmodifiableMap(new EnumMap<>(builder.pages));
        fields = Collections.unmodifiableMap(new EnumMap<>(builder.fields));
        native937Confirmation = builder.native937Confirmation;
    }
    public static final class Builder {
        private final String packageName, appVersion;
        private final ZoneId zoneId;
        private final Map<CheckoutObservation.Kind, Page> pages = new EnumMap<>(CheckoutObservation.Kind.class);
        private final Map<Role, Selector> fields = new EnumMap<>(Role.class);
        private boolean native937Confirmation;
        public Builder(String packageName, String appVersion, ZoneId zoneId) {
            this.packageName = clean(packageName); this.appVersion = clean(appVersion); this.zoneId = zoneId;
            if (this.packageName.isEmpty() || this.appVersion.isEmpty() || zoneId == null)
                throw new IllegalArgumentException("profile_identity_missing");
        }
        public Builder page(CheckoutObservation.Kind kind, String rootResourceId, Selector marker) {
            if (kind != CheckoutObservation.Kind.CONFIRMATION && kind != CheckoutObservation.Kind.PAYMENT_PENDING)
                throw new IllegalArgumentException("unsupported_page_kind");
            requireId(rootResourceId); validate(marker);
            if (marker.match != Selector.Match.EXACT || marker.exactLabel.isEmpty() || marker.isAnchored())
                throw new IllegalArgumentException("explicit_page_label_required");
            if (pages.put(kind, new Page(kind, rootResourceId, marker)) != null)
                throw new IllegalArgumentException("duplicate_page_kind");
            return this;
        }
        public Builder field(Role role, Selector selector) {
            if (role == null) throw new IllegalArgumentException("role_missing");
            validate(selector);
            if ((role == Role.UNIT_PRICE_QUANTITY) != (selector.match == Selector.Match.PRICE_QUANTITY))
                throw new IllegalArgumentException("price_quantity_role_required");
            if (selector.isAnchoredValue() && !publicValueRole(role))
                throw new IllegalArgumentException("anchored_selector_public_value_only");
            if (selector.isAnchored() && !selector.isAnchoredValue()) {
                boolean submit = role == Role.SUBMIT && selector.match == Selector.Match.EXACT;
                boolean publicLabel = (role == Role.EXTRAS_STATUS || role == Role.AGREEMENT)
                        && selector.match == Selector.Match.PUBLIC_LABEL;
                if (!submit && !publicLabel) throw new IllegalArgumentException("anchored_action_submit_only");
            }
            if (role == Role.SUBMIT && (selector.match != Selector.Match.EXACT || !isSubmitLabel(selector.exactLabel)))
                throw new IllegalArgumentException("explicit_submit_label_required");
            if (fields.put(role, selector) != null) throw new IllegalArgumentException("duplicate_role");
            return this;
        }
        Builder native937Confirmation() {
            if (!"cn.damai".equals(packageName) || !"9.0.37#109003700".equals(appVersion))
                throw new IllegalArgumentException("native_confirmation_version_mismatch");
            native937Confirmation = true;
            return this;
        }
        public CheckoutProfile build() {
            if (pages.isEmpty()) throw new IllegalArgumentException("pages_missing");
            if (fields.containsKey(Role.PAYMENT_DEADLINE) && fields.containsKey(Role.PAYMENT_COUNTDOWN))
                throw new IllegalArgumentException("choose_one_deadline_representation");
            if (fields.containsKey(Role.SESSION_DATETIME)
                    && (fields.containsKey(Role.SESSION_DATE) || fields.containsKey(Role.SESSION_TIME)))
                throw new IllegalArgumentException("choose_one_session_representation");
            if (fields.containsKey(Role.UNIT_PRICE_QUANTITY)
                    && (fields.containsKey(Role.UNIT_PRICE) || fields.containsKey(Role.QUANTITY)))
                throw new IllegalArgumentException("choose_one_price_quantity_representation");
            return new CheckoutProfile(this);
        }
        private void validate(Selector selector) {
            if (selector == null) throw new IllegalArgumentException("selector_missing");
            requireId(selector.ancestorScopeId);
            if (!selector.isAnchored()) requireId(selector.resourceId);
            else if (!selector.resourceId.isEmpty() || (selector.match != Selector.Match.VALUE
                    && selector.match != Selector.Match.PRICE_QUANTITY
                    && ((selector.match != Selector.Match.EXACT && selector.match != Selector.Match.PUBLIC_LABEL)
                    || selector.exactLabel.isEmpty())))
                throw new IllegalArgumentException("anchored_selector_unverified");
        }
        private void requireId(String id) {
            if (id == null || !id.startsWith(packageName + ":id/") || id.length() <= packageName.length() + 4)
                throw new IllegalArgumentException("explicit_package_resource_id_required");
        }
    }
    /** Supported labels remain explicit order actions; payment and broad substring matches are excluded. */
    public static boolean isSubmitLabel(String value) {
        return "提交订单".equals(value) || "立即提交".equals(value);
    }
    private static boolean publicValueRole(Role role) {
        switch (role) {
            case EVENT_TITLE: case CITY: case SESSION_DATE: case SESSION_TIME: case SESSION_DATETIME:
            case UNIT_PRICE: case QUANTITY: case UNIT_PRICE_QUANTITY: case TICKET_SUBTOTAL: case PAYABLE: case EXTRA_FEE:
                return true;
            default: return false;
        }
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
