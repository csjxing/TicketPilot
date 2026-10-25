package cn.local.tickethelper.checkout;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import cn.local.tickethelper.core.UiItem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Ephemeral native tree only. Never logs, serializes, screenshots, or reads protected node text. */
public final class CheckoutFrameReader {
    private static final String PACKAGE = "cn.damai";
    private static final int MAX_NODES = 500, MAX_DEPTH = 40, MAX_TEXT = 4096;
    private static final long MAX_CAPTURE_MS = 500;
    private CheckoutFrameReader() { }

    public static CheckoutFrame capture(AccessibilityService service, String appVersion, LongSupplier generation) {
        long before = generation.getAsLong(), observedAt = System.currentTimeMillis();
        long began = SystemClock.elapsedRealtime();
        int windowId = -1, displayId = -1;
        List<CheckoutFrame.Node> second = List.of();
        boolean complete = false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (validRoot(root)) {
                    windowId = root.getWindowId(); displayId = root.getWindow().getDisplayId();
                    Capture first = new Capture(windowId, began);
                    first.collect(root, "root", "", 0, false);
                    // Read again rather than treating queued accessibility events as a snapshot guarantee.
                    AccessibilityNodeInfo nextRoot = service.getRootInActiveWindow();
                    Capture next = new Capture(windowId, began);
                    if (validRoot(nextRoot) && nextRoot.getWindowId() == windowId) {
                        next.collect(nextRoot, "root", "", 0, false);
                        second = next.nodes;
                        complete = first.complete && next.complete && equalTrees(first.nodes, next.nodes)
                                && SystemClock.elapsedRealtime() - began <= MAX_CAPTURE_MS;
                    }
                }
            } catch (RuntimeException ignored) { complete = false; }
        }
        long after = generation.getAsLong();
        return new CheckoutFrame(PACKAGE, appVersion, windowId, displayId, before, after, observedAt,
                complete && before == after, second);
    }

    private static boolean validRoot(AccessibilityNodeInfo root) {
        if (root == null || !root.refresh() || !root.isVisibleToUser()
                || !PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false;
        AccessibilityWindowInfo window = root.getWindow();
        return window != null && window.isActive() && root.getWindowId() >= 0
                && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                && window.getDisplayId() == Display.DEFAULT_DISPLAY;
    }

    private static final class Capture {
        final int windowId;
        final long began;
        final List<CheckoutFrame.Node> nodes = new ArrayList<>();
        boolean complete = true;
        Capture(int windowId, long began) { this.windowId = windowId; this.began = began; }
        void collect(AccessibilityNodeInfo node, String key, String parent, int depth, boolean protectedParent) {
            if (!complete) return;
            if (node == null || depth > MAX_DEPTH || nodes.size() >= MAX_NODES
                    || SystemClock.elapsedRealtime() - began > MAX_CAPTURE_MS || !node.refresh()
                    || node.getWindowId() != windowId
                    || !PACKAGE.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                complete = false; return;
            }
            boolean sensitive = protectedParent || node.isPassword()
                    || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive());
            String text = "";
            if (!sensitive) {
                CharSequence nativeText = node.getText();
                if (nativeText == null || nativeText.toString().trim().isEmpty()) nativeText = node.getContentDescription();
                text = nativeText == null ? "" : nativeText.toString().trim();
                if (text.length() > MAX_TEXT) { complete = false; return; }
            }
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            UiItem item = new UiItem(text, bounds.left, bounds.top, bounds.right, bounds.bottom,
                    node.isEnabled(), node.isClickable(), node.isSelected(), UiItem.Source.NODE, node.getViewIdResourceName());
            nodes.add(new CheckoutFrame.Node(key, parent, item, node.isVisibleToUser(), node.isChecked(),
                    node.isCheckable(), sensitive, windowId));
            int childCount = node.getChildCount();
            if (childCount > MAX_NODES - nodes.size()) { complete = false; return; }
            for (int i = 0; complete && i < childCount; i++) {
                // Check before getChild(): argument evaluation itself can perform a Binder read.
                if (SystemClock.elapsedRealtime() - began > MAX_CAPTURE_MS) { complete = false; break; }
                collect(node.getChild(i), key + "/" + i, key, depth + 1, sensitive);
            }
        }
    }

    private static boolean equalTrees(List<CheckoutFrame.Node> first, List<CheckoutFrame.Node> second) {
        if (first.size() != second.size() || first.isEmpty()) return false;
        for (int i = 0; i < first.size(); i++) {
            CheckoutFrame.Node a = first.get(i), b = second.get(i);
            UiItem x = a.item, y = b.item;
            if (!a.key.equals(b.key) || !a.parentKey.equals(b.parentKey) || a.windowId != b.windowId
                    || a.visible != b.visible || a.checked != b.checked || a.checkable != b.checkable || a.sensitive != b.sensitive
                    || !x.text.equals(y.text) || !x.resourceId.equals(y.resourceId) || x.left != y.left || x.top != y.top
                    || x.right != y.right || x.bottom != y.bottom || x.enabled != y.enabled || x.clickable != y.clickable
                    || x.selected != y.selected || x.source != y.source) return false;
        }
        return true;
    }
}
