package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only matching of actual native attendee rows. Does not read a screen or execute a click.
 * The caller must provide the complete attendee scope from one current, verified native window;
 * names, document text and explicit checkbox facts must come from that scope, never configuration.
 * These input strings are ephemeral local data and must not be logged or exported.
 */
public final class ViewerMatcher {
    public enum State { VERIFIED_SELECTED, SELECT_PROPOSAL, UNCERTAIN }

    /** rowKey is a unique ephemeral node/row identity, not a name or document number. */
    public static final class ViewerRow {
        public final String rowKey, visibleName, visibleDocument;
        public final UiItem selectionTarget;
        public final boolean checked, selectionKnown, protectedControl;

        public ViewerRow(String rowKey, String visibleName, String visibleDocument, UiItem selectionTarget,
                         boolean checked, boolean selectionKnown, boolean protectedControl) {
            this.rowKey = clean(rowKey);
            this.visibleName = clean(visibleName);
            this.visibleDocument = clean(visibleDocument);
            this.selectionTarget = selectionTarget;
            this.checked = checked;
            this.selectionKnown = selectionKnown;
            this.protectedControl = protectedControl;
        }
    }

    /** Only a digest, a fixed reason code and a redacted native target leave this matcher. */
    public static final class Result {
        public final State state;
        public final String bindingHash, reason;
        /** Non-null only for SELECT_PROPOSAL. Text is deliberately empty to avoid retaining PII. */
        public final UiItem selectionTarget;

        private Result(State state, String bindingHash, String reason, UiItem selectionTarget) {
            this.state = state;
            this.bindingHash = bindingHash;
            this.reason = reason;
            this.selectionTarget = selectionTarget;
        }
    }

    private static final int MAX_ROWS = 128;
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{8,}");
    private static final Pattern DOCUMENT = Pattern.compile(
            "^(?:(?:身份证尾号|证件尾号|证件号码|身份证号码|身份证|护照|证件号|证件|尾号)[：:\\s]*)?([0-9A-Z*＊●•·×]{2,64})$");
    private static final Pattern TRAILING_VISIBLE = Pattern.compile("[0-9A-Z]+$");

    private ViewerMatcher() { }

    /**
     * Recheck only this actual native row's identity, e.g. after resolving a checkbox again.
     * True is NOT evidence that the list is complete/unique, the row is selected or input is safe.
     * A configured suffix must still be visibly present; no missing field is filled from settings.
     */
    public static boolean matchesExactRow(String configuredName, String configuredSuffix,
                                          String visibleName, String visibleDocument) {
        String expectedName = clean(configuredName), actualName = clean(visibleName);
        String suffix = clean(configuredSuffix).toUpperCase(Locale.ROOT);
        if (!completeName(expectedName) || !completeName(actualName) || !expectedName.equals(actualName)
                || (!suffix.isEmpty() && !suffix.matches("[0-9A-Z]{2,6}"))) return false;
        if (suffix.isEmpty()) return true;
        String tail = documentTail(visibleDocument);
        return tail.length() >= suffix.length() && tail.endsWith(suffix);
    }

    /**
     * A suffix is optional, but when supplied it must be visibly present at the document's end.
     * Missing or too-short suffixes on any same-name row cannot exclude that possible duplicate.
     * Without a configured suffix, only one complete, exact visible name can be accepted.
     * A proposal is not a verified selection: the caller must reread the page after any later input.
     */
    public static Result match(String configuredName, String configuredSuffix,
                               List<ViewerRow> rows, boolean completeNativeRows) {
        String name = clean(configuredName);
        String suffix = clean(configuredSuffix).toUpperCase(Locale.ROOT);
        if (!completeName(name) || (!suffix.isEmpty() && !suffix.matches("[0-9A-Z]{2,6}")))
            return uncertain("invalid_viewer_config");
        if (!completeNativeRows) return uncertain("incomplete_native_viewer_scope");
        if (rows == null || rows.isEmpty()) return uncertain("viewer_rows_missing");
        if (rows.size() > MAX_ROWS) return uncertain("viewer_scope_too_large");

        Set<String> rowKeys = new HashSet<>();
        Set<String> controls = new HashSet<>();
        List<ViewerRow> sameName = new ArrayList<>();
        ViewerRow selected = null;
        int selectedCount = 0;
        for (ViewerRow row : rows) {
            if (row == null || row.rowKey.isEmpty()) return uncertain("viewer_row_identity_missing");
            if (!rowKeys.add(row.rowKey)) return uncertain("duplicate_viewer_row");
            if (row.protectedControl) return uncertain("protected_viewer_control");
            // A masked/absent name could be another instance of the requested attendee.
            if (!completeName(row.visibleName)) return uncertain("viewer_name_incomplete");
            UiItem node = row.selectionTarget;
            if (node == null || node.source != UiItem.Source.NODE || !node.hasBounds())
                return uncertain("native_selection_control_missing");
            if (!row.selectionKnown) return uncertain("viewer_selection_unknown");
            if (node.selected != row.checked) return uncertain("viewer_selection_conflict");
            // Two alleged rows sharing a control cannot be independently selected/verified.
            String control = node.left + ":" + node.top + ":" + node.right + ":" + node.bottom;
            if (!controls.add(control)) return uncertain("duplicate_viewer_control");
            if (row.checked) { selected = row; selectedCount++; }
            if (row.visibleName.equals(name)) sameName.add(row);
        }
        if (selectedCount > 1) return uncertain("multiple_viewers_selected");
        if (sameName.isEmpty()) return uncertain("viewer_not_found");

        ViewerRow target = null;
        String observedSuffix = "";
        for (ViewerRow row : sameName) {
            String candidateSuffix = "";
            if (!suffix.isEmpty()) {
                String visibleTail = documentTail(row.visibleDocument);
                if (visibleTail.length() < suffix.length()) return uncertain("viewer_suffix_not_visible");
                candidateSuffix = visibleTail.substring(visibleTail.length() - suffix.length());
                if (!candidateSuffix.equals(suffix)) continue;
            }
            if (target != null) return uncertain("ambiguous_viewer_match");
            target = row;
            observedSuffix = candidateSuffix;
        }
        if (target == null) return uncertain("viewer_suffix_mismatch");
        if (selected != null && selected != target) return uncertain("different_viewer_selected");

        // Derive the binding from the matched page's name and observed tail, not expected facts.
        String binding = LockAuthorization.viewerBindingHash(target.visibleName, observedSuffix);
        if (binding.isEmpty()) return uncertain("viewer_binding_invalid");
        if (target.checked) return new Result(State.VERIFIED_SELECTED, binding, "unique_viewer_selected", null);
        UiItem node = target.selectionTarget;
        if (!node.enabled || !node.clickable) return uncertain("viewer_control_not_actionable");
        UiItem redacted = new UiItem("", node.left, node.top, node.right, node.bottom,
                node.enabled, node.clickable, false, UiItem.Source.NODE, node.resourceId);
        return new Result(State.SELECT_PROPOSAL, binding, "unique_unselected_viewer", redacted);
    }

    private static Result uncertain(String reason) {
        return new Result(State.UNCERTAIN, "", reason, null);
    }

    private static String documentTail(String value) {
        Matcher document = DOCUMENT.matcher(clean(value).toUpperCase(Locale.ROOT));
        if (!document.matches()) return "";
        Matcher tail = TRAILING_VISIBLE.matcher(document.group(1));
        return tail.find() ? tail.group() : "";
    }

    private static boolean completeName(String name) {
        if (name.isEmpty() || name.length() > 80 || LONG_DIGITS.matcher(name).find()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || "*＊●•×?？…_".indexOf(c) >= 0) return false;
        }
        return true;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
