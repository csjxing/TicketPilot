package cn.local.tickethelper.checkout;

import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/** Rebind a final checkbox to its actual native attendee row, never coordinates alone. */
public final class CheckoutViewerBinding {
    private CheckoutViewerBinding() { }

    public static boolean matches(AccessibilityNodeInfo control, CheckoutProfile profile,
                                  String name, String suffix, long deadline) {
        try {
            CheckoutProfile.Selector selection = profile.fields.get(CheckoutProfile.Role.VIEWER_SELECTION);
            CheckoutProfile.Selector rowRule = profile.fields.get(CheckoutProfile.Role.VIEWER_ROW);
            CheckoutProfile.Page page = profile.pages.get(CheckoutObservation.Kind.CONFIRMATION);
            if (selection == null || rowRule == null || page == null || control == null
                    || !safe(control, control.getWindowId(), deadline) || !control.isCheckable() || control.isChecked()
                    || !selection.resourceId.equals(control.getViewIdResourceName())) return false;
            int window = control.getWindowId();
            AccessibilityNodeInfo row = ancestor(control, rowRule.resourceId, window, deadline);
            if (row == null || ancestor(row, rowRule.ancestorScopeId, window, deadline) == null
                    || ancestor(row, page.rootResourceId, window, deadline) == null
                    || ancestorWithin(control, selection.ancestorScopeId, row, window, deadline) == null) return false;
            Fields fields = new Fields(profile, window, deadline, row);
            fields.read(row, 0);
            if (!fields.complete || fields.names.size() != 1
                    || (fields.documentRule != null && fields.documents.size() != 1)) return false;
            String document = fields.documentRule == null ? "" : fields.documents.get(0);
            return ViewerMatcher.matchesExactRow(name, suffix, fields.names.get(0), document)
                    && safe(control, window, deadline) && control.isCheckable() && !control.isChecked()
                    && SystemClock.elapsedRealtime() < deadline;
        } catch (RuntimeException unavailable) { return false; }
    }

    private static boolean safe(AccessibilityNodeInfo node, int window, long deadline) {
        if (node == null || SystemClock.elapsedRealtime() >= deadline || !node.refresh()
                || node.getWindowId() != window || !node.isVisibleToUser() || node.isPassword()
                || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())
                || !"cn.damai".contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) return false;
        return SystemClock.elapsedRealtime() < deadline;
    }

    private static AccessibilityNodeInfo ancestor(AccessibilityNodeInfo node, String id, int window, long deadline) {
        AccessibilityNodeInfo parent = node.getParent();
        for (int depth = 0; parent != null && depth < 40; depth++) {
            if (!safe(parent, window, deadline)) return null;
            if (id.equals(parent.getViewIdResourceName())) return parent;
            parent = parent.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo ancestorWithin(AccessibilityNodeInfo node, String id,
                                                        AccessibilityNodeInfo row, int window, long deadline) {
        AccessibilityNodeInfo parent = node.getParent();
        for (int depth = 0; parent != null && depth < 40; depth++) {
            if (!safe(parent, window, deadline)) return null;
            if (id.equals(parent.getViewIdResourceName())) return parent;
            if (parent.equals(row)) return null;
            parent = parent.getParent();
        }
        return null;
    }

    private static final class Fields {
        final CheckoutProfile.Selector nameRule, documentRule;
        final int window;
        final long deadline;
        final AccessibilityNodeInfo row;
        final List<String> names = new ArrayList<>(), documents = new ArrayList<>();
        int visited;
        boolean complete = true;
        Fields(CheckoutProfile profile, int window, long deadline, AccessibilityNodeInfo row) {
            nameRule = profile.fields.get(CheckoutProfile.Role.VIEWER_NAME);
            documentRule = profile.fields.get(CheckoutProfile.Role.VIEWER_DOCUMENT);
            this.window = window; this.deadline = deadline; this.row = row;
            if (nameRule == null || nameRule.match != CheckoutProfile.Selector.Match.VALUE
                    || (documentRule != null && documentRule.match != CheckoutProfile.Selector.Match.VALUE)) complete = false;
        }
        void read(AccessibilityNodeInfo node, int depth) {
            if (!complete) return;
            if (++visited > 128 || depth > 20 || !safe(node, window, deadline)) { complete = false; return; }
            if (nameRule.resourceId.equals(node.getViewIdResourceName())) extract(node, nameRule, names);
            if (documentRule != null && documentRule.resourceId.equals(node.getViewIdResourceName())) extract(node, documentRule, documents);
            int count = node.getChildCount();
            if (count > 128 - visited) { complete = false; return; }
            for (int i = 0; complete && i < count; i++) {
                if (SystemClock.elapsedRealtime() >= deadline) { complete = false; break; }
                read(node.getChild(i), depth + 1);
            }
        }
        void extract(AccessibilityNodeInfo node, CheckoutProfile.Selector rule, List<String> values) {
            if (ancestorWithin(node, rule.ancestorScopeId, row, window, deadline) == null) { complete = false; return; }
            CharSequence text = node.getText();
            if (text == null || text.toString().trim().isEmpty()) text = node.getContentDescription();
            String value = text == null ? "" : text.toString().trim();
            if (value.length() > 4096) { complete = false; return; }
            Matcher matcher = rule.extraction.matcher(value);
            if (!matcher.matches() || matcher.group(1) == null || matcher.group(1).trim().isEmpty()) { complete = false; return; }
            values.add(matcher.group(1).trim());
        }
    }
}
