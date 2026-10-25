package cn.local.tickethelper.checkout;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import cn.local.tickethelper.core.UiItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded native identity lookup. Sensitive ancestor branches are never inspected for text. */
public final class CheckoutNativeTarget {
    private CheckoutNativeTarget() { }
    public interface NativeVerifier { boolean matches(AccessibilityNodeInfo node); }

    /** Only called with a binding matched to the live adapter registry; never falls back to coordinates. */
    static AccessibilityNodeInfo findStructuralSubmit(AccessibilityService service,UiItem target,int windowId,
                                                       CheckoutSubmitBinding binding,LongSupplier generation,
                                                       long expectedGeneration,long deadline) {
        if (binding == null || !binding.matchesTarget(target) || generation == null || generation.getAsLong() != expectedGeneration)
            return null;
        try {
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if (root == null || root.getWindowId() != windowId) return null;
            AccessibilityWindowInfo window=root.getWindow();
            if (window == null || !window.isActive() || window.getDisplayId() != Display.DEFAULT_DISPLAY
                    || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) return null;
            StructuralSearch search=new StructuralSearch(windowId,deadline);
            search.visit(root,"root","",0,false);
            long after=generation.getAsLong();
            CheckoutFrame frame=new CheckoutFrame(binding.packageName,binding.appVersion,windowId,0,
                    expectedGeneration,after,System.currentTimeMillis(),search.complete,search.nodes);
            String key=binding.resolve(frame,target);
            AccessibilityNodeInfo found=search.nativeNodes.get(key);
            if (key.isEmpty() || found == null || after != expectedGeneration || !found.refresh()
                    || SystemClock.elapsedRealtime() >= deadline) return null;
            Rect bounds=new Rect(); found.getBoundsInScreen(bounds);
            CharSequence text=found.getText();
            if (text == null || text.toString().trim().isEmpty()) text=found.getContentDescription();
            return found.getWindowId() == windowId && found.isVisibleToUser() && found.isEnabled() && found.isClickable()
                    && !found.isPassword() && !(Build.VERSION.SDK_INT >= 34 && found.isAccessibilityDataSensitive())
                    && (found.getViewIdResourceName() == null || found.getViewIdResourceName().isEmpty())
                    && text != null && binding.submitLabel.equals(text.toString().trim())
                    && target.left == bounds.left && target.top == bounds.top && target.right == bounds.right && target.bottom == bounds.bottom
                    && generation.getAsLong() == expectedGeneration && SystemClock.elapsedRealtime() < deadline ? found : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    /** Ephemeral bounded structural read, never logged or serialized. */
    private static final class StructuralSearch {
        final int windowId; final long deadline;
        final List<CheckoutFrame.Node> nodes=new ArrayList<>();
        final Map<String,AccessibilityNodeInfo> nativeNodes=new HashMap<>();
        boolean complete=true;
        StructuralSearch(int windowId,long deadline) { this.windowId=windowId; this.deadline=deadline; }
        void visit(AccessibilityNodeInfo node,String key,String parent,int depth,boolean protectedParent) {
            if (!complete) return;
            if (node == null || nodes.size() >= 500 || depth > 40 || SystemClock.elapsedRealtime() >= deadline
                    || !node.refresh() || node.getWindowId() != windowId
                    || !"cn.damai".contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                complete=false; return;
            }
            boolean sensitive=protectedParent || node.isPassword()
                    || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive());
            String text="";
            if (!sensitive) {
                CharSequence value=node.getText();
                if (value == null || value.toString().trim().isEmpty()) value=node.getContentDescription();
                text=value == null ? "" : value.toString().trim();
                if (text.length() > 4096) { complete=false; return; }
            }
            Rect bounds=new Rect(); node.getBoundsInScreen(bounds);
            UiItem item=new UiItem(text,bounds.left,bounds.top,bounds.right,bounds.bottom,node.isEnabled(),node.isClickable(),
                    node.isSelected(),UiItem.Source.NODE,node.getViewIdResourceName());
            nodes.add(new CheckoutFrame.Node(key,parent,item,node.isVisibleToUser(),node.isChecked(),node.isCheckable(),sensitive,windowId));
            nativeNodes.put(key,node);
            int count=node.getChildCount();
            if (count > 500-nodes.size()) { complete=false; return; }
            for (int i=0;complete && i<count;i++) {
                if (SystemClock.elapsedRealtime() >= deadline) { complete=false; break; }
                visit(node.getChild(i),key+"/"+i,key,depth+1,sensitive);
            }
        }
    }

    public static AccessibilityNodeInfo find(AccessibilityService service, UiItem target, int windowId,
                                             long deadlineElapsedMs) {
        return find(service, target, windowId, deadlineElapsedMs, node -> target != null && !target.text.isEmpty());
    }

    /** Empty-text checkbox targets require an additional live row-identity verifier. */
    public static AccessibilityNodeInfo find(AccessibilityService service, UiItem target, int windowId,
                                             long deadlineElapsedMs, NativeVerifier verifier) {
        if (target == null || target.source != UiItem.Source.NODE || !target.hasBounds()
                || !target.resourceId.startsWith("cn.damai:id/") || windowId < 0
                || verifier == null || (!CheckoutProfile.isSubmitLabel(target.text) && !target.text.isEmpty())) return null;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null || root.getWindowId() != windowId) return null;
            AccessibilityWindowInfo window = root.getWindow();
            if (window == null || !window.isActive() || window.getDisplayId() != Display.DEFAULT_DISPLAY
                    || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) return null;
            Search search = new Search(target, windowId, deadlineElapsedMs);
            search.visit(root, 0);
            AccessibilityNodeInfo found = search.found;
            if (!search.complete || search.matches != 1 || found == null || SystemClock.elapsedRealtime() >= deadlineElapsedMs)
                return null;
            if (!verifier.matches(found)) return null;
            // The row verifier refreshes the same node. Recheck geometry after that refresh,
            // since shell input still uses the proposed coordinates.
            Rect latest = new Rect(); found.getBoundsInScreen(latest);
            boolean sameTarget = found.getWindowId() == windowId && found.isVisibleToUser()
                    && found.isEnabled() && found.isClickable() && !found.isPassword()
                    && !(Build.VERSION.SDK_INT >= 34 && found.isAccessibilityDataSensitive())
                    && target.resourceId.equals(found.getViewIdResourceName())
                    && latest.left == target.left && latest.top == target.top
                    && latest.right == target.right && latest.bottom == target.bottom
                    && (!target.text.isEmpty() || (found.isCheckable() && !found.isChecked()));
            return sameTarget && SystemClock.elapsedRealtime() < deadlineElapsedMs ? found : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    private static final class Search {
        final UiItem target;
        final int windowId;
        final long deadline;
        int visited, matches;
        boolean complete = true;
        AccessibilityNodeInfo found;
        Search(UiItem target, int windowId, long deadline) { this.target = target; this.windowId = windowId; this.deadline = deadline; }
        void visit(AccessibilityNodeInfo node, int depth) {
            if (!complete) return;
            if (node == null || ++visited > 500 || depth > 40 || SystemClock.elapsedRealtime() >= deadline || !node.refresh()) {
                complete = false; return;
            }
            if (node.getWindowId() != windowId || !"cn.damai".contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                complete = false; return;
            }
            if (node.isPassword() || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())) return;
            if (node.isVisibleToUser() && target.resourceId.equals(node.getViewIdResourceName())) {
                Rect r = new Rect(); node.getBoundsInScreen(r);
                if (r.left == target.left && r.top == target.top && r.right == target.right && r.bottom == target.bottom) {
                    boolean exact;
                    if (target.text.isEmpty()) exact = node.isCheckable() && !node.isChecked();
                    else {
                        CharSequence nativeText = node.getText();
                        if (nativeText == null || nativeText.toString().trim().isEmpty()) nativeText = node.getContentDescription();
                        exact = nativeText != null && target.text.equals(nativeText.toString().trim());
                    }
                    if (exact && node.isEnabled() && node.isClickable()) { matches++; found = node; }
                }
            }
            int count = node.getChildCount();
            if (count > 500 - visited) { complete = false; return; }
            for (int i = 0; complete && i < count; i++) {
                if (SystemClock.elapsedRealtime() >= deadline) { complete = false; break; }
                visit(node.getChild(i), depth + 1);
            }
        }
    }
}
