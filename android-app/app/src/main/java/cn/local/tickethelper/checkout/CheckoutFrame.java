package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ephemeral native window evidence. Never serialize this tree: it may contain attendee data. */
public final class CheckoutFrame {
    public final String packageName, appVersion;
    public final int windowId, displayId;
    public final long generationBefore, generationAfter, observedAtMs;
    public final boolean complete;
    public final List<Node> nodes;

    public CheckoutFrame(String packageName, String appVersion, int windowId, int displayId,
                         long generationBefore, long generationAfter, long observedAtMs,
                         boolean complete, List<Node> nodes) {
        this.packageName = clean(packageName); this.appVersion = clean(appVersion);
        this.windowId = windowId; this.displayId = displayId;
        this.generationBefore = generationBefore; this.generationAfter = generationAfter;
        this.observedAtMs = observedAtMs; this.complete = complete;
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes == null ? List.of() : nodes));
    }

    /** checked and checkable are direct native flags, independent of UiItem.selected. */
    public static final class Node {
        public final String key, parentKey;
        public final UiItem item;
        public final boolean visible, checked, checkable, sensitive;
        public final int windowId;
        public Node(String key, String parentKey, UiItem item, boolean visible, boolean checked,
                    boolean checkable, boolean sensitive, int windowId) {
            this.key = clean(key); this.parentKey = clean(parentKey); this.item = item;
            this.visible = visible; this.checked = checked; this.checkable = checkable;
            this.sensitive = sensitive; this.windowId = windowId;
        }
    }
    private static String clean(String text) { return text == null ? "" : text.trim(); }
}
