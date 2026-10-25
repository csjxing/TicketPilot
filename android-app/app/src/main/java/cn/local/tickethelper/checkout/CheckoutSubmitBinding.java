package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;
import java.util.Objects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Ephemeral structural contract, never an order authorization or a persisted click coordinate. */
public final class CheckoutSubmitBinding {
    public final String packageName, appVersion, pageRootId, markerId, markerScopeId, markerLabel,
            anchorId, childPath, submitLabel;

    private CheckoutSubmitBinding(CheckoutProfile profile, CheckoutProfile.Page page, CheckoutProfile.Selector selector) {
        packageName=profile.packageName; appVersion=profile.appVersion; pageRootId=page.rootResourceId;
        markerId=page.marker.resourceId; markerScopeId=page.marker.ancestorScopeId; markerLabel=page.marker.exactLabel;
        anchorId=selector.ancestorScopeId; childPath=selector.relativeChildPath; submitLabel=selector.exactLabel;
    }

    static CheckoutSubmitBinding from(CheckoutProfile profile) {
        if (profile == null || !"cn.damai".equals(profile.packageName)) return null;
        CheckoutProfile.Page page=profile.pages.get(CheckoutObservation.Kind.CONFIRMATION);
        CheckoutProfile.Selector submit=profile.fields.get(CheckoutProfile.Role.SUBMIT);
        if (page == null || submit == null || !submit.isAnchored() || submit.match != CheckoutProfile.Selector.Match.EXACT
                || !submit.resourceId.isEmpty() || !"立即提交".equals(submit.exactLabel)) return null;
        return new CheckoutSubmitBinding(profile,page,submit);
    }

    public boolean matchesProfile(CheckoutProfile profile) { return equals(from(profile)); }
    public boolean matchesTarget(UiItem target) {
        return target != null && target.source == UiItem.Source.NODE && target.resourceId.isEmpty()
                && target.hasBounds() && target.enabled && target.clickable && submitLabel.equals(target.text);
    }
    /** Pure final structural match. The full checkout facts are revalidated separately before this. */
    String resolve(CheckoutFrame frame,UiItem expected) {
        if (!matchesTarget(expected) || frame == null || !frame.complete || !packageName.equals(frame.packageName)
                || !appVersion.equals(frame.appVersion) || frame.windowId < 0 || frame.displayId != 0
                || frame.generationBefore < 0 || frame.generationBefore != frame.generationAfter
                || frame.nodes.isEmpty() || frame.nodes.size() > 500) return "";
        Map<String,CheckoutFrame.Node> nodes=new HashMap<>(); int roots=0;
        for (CheckoutFrame.Node node:frame.nodes) {
            if (node == null || node.key.isEmpty() || node.item == null || node.item.source != UiItem.Source.NODE
                    || node.windowId != frame.windowId || nodes.put(node.key,node) != null) return "";
            if (node.parentKey.isEmpty()) roots++;
        }
        if (roots != 1) return "";
        for (CheckoutFrame.Node node:frame.nodes) {
            Set<String> seen=new HashSet<>(); CheckoutFrame.Node current=node;
            while (current != null) {
                if (!seen.add(current.key) || seen.size() > 41) return "";
                if (current.parentKey.isEmpty()) break;
                current=nodes.get(current.parentKey); if (current == null) return "";
            }
        }
        CheckoutFrame.Node page=unique(nodes,pageRootId,null);
        if (page == null || !usable(nodes,page)) return "";
        CheckoutFrame.Node marker=unique(nodes,markerId,page);
        if (marker == null || !usable(nodes,marker) || !markerLabel.equals(marker.item.text)
                || !hasAncestor(nodes,marker,markerScopeId,page)) return "";
        CheckoutFrame.Node current=unique(nodes,anchorId,page);
        if (current == null || !usable(nodes,current)) return "";
        for (String part:childPath.split("/")) {
            CheckoutFrame.Node next=nodes.get(current.key+"/"+part);
            if (next == null || !next.parentKey.equals(current.key)) return "";
            current=next;
        }
        UiItem actual=current.item;
        return usable(nodes,current) && matchesTarget(actual)
                && expected.left == actual.left && expected.top == actual.top
                && expected.right == actual.right && expected.bottom == actual.bottom ? current.key : "";
    }
    private static CheckoutFrame.Node unique(Map<String,CheckoutFrame.Node> nodes,String id,CheckoutFrame.Node scope) {
        CheckoutFrame.Node match=null;
        for (CheckoutFrame.Node node:nodes.values()) {
            if (node.visible && id.equals(node.item.resourceId) && (scope == null || descendant(nodes,node,scope))) {
                if (match != null) return null; match=node;
            }
        }
        return match;
    }
    private static boolean descendant(Map<String,CheckoutFrame.Node> nodes,CheckoutFrame.Node node,CheckoutFrame.Node scope) {
        for (CheckoutFrame.Node current=node;current != null;current=nodes.get(current.parentKey))
            if (current.key.equals(scope.key)) return true;
        return false;
    }
    private static boolean hasAncestor(Map<String,CheckoutFrame.Node> nodes,CheckoutFrame.Node node,String id,CheckoutFrame.Node scope) {
        for (CheckoutFrame.Node current=nodes.get(node.parentKey);current != null;current=nodes.get(current.parentKey)) {
            if (id.equals(current.item.resourceId)) return true;
            if (current.key.equals(scope.key)) break;
        }
        return false;
    }
    private static boolean usable(Map<String,CheckoutFrame.Node> nodes,CheckoutFrame.Node node) {
        if (!node.item.hasBounds()) return false;
        for (CheckoutFrame.Node current=node;current != null;current=nodes.get(current.parentKey))
            if (!current.visible || current.sensitive) return false;
        return true;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof CheckoutSubmitBinding)) return false;
        CheckoutSubmitBinding b=(CheckoutSubmitBinding)other;
        return packageName.equals(b.packageName) && appVersion.equals(b.appVersion) && pageRootId.equals(b.pageRootId)
                && markerId.equals(b.markerId) && markerScopeId.equals(b.markerScopeId) && markerLabel.equals(b.markerLabel)
                && anchorId.equals(b.anchorId) && childPath.equals(b.childPath) && submitLabel.equals(b.submitLabel);
    }
    @Override public int hashCode() {
        return Objects.hash(packageName,appVersion,pageRootId,markerId,markerScopeId,markerLabel,anchorId,childPath,submitLabel);
    }
}
