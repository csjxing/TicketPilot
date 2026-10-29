package cn.local.tickethelper.core;

import cn.local.tickethelper.catalog.CatalogParser;
import cn.local.tickethelper.catalog.CatalogProject;
import java.util.List;

/** Two complete fresh reads may agree on reversible navigation despite moving video pixels.
 * This contract never validates SKU selection, inventory, viewers, amount or order submission. */
public final class DetailNavigationRead {
    private DetailNavigationRead() {}

    public static boolean agrees(MonitorConfig config, Decision first, List<UiItem> firstItems,
                                 Decision second, List<UiItem> secondItems, long nowMs) {
        if (config == null || !DetailOcrNavigation.isProposal(first) || !DetailOcrNavigation.isProposal(second)
                || !first.reason.equals(second.reason) || first.observedAtMs <= 0
                || second.observedAtMs <= first.observedAtMs || nowMs < second.observedAtMs
                || nowMs - first.observedAtMs >= 3000 || DetailScrollPolicy.unsafePage(firstItems)
                || DetailScrollPolicy.unsafePage(secondItems)) return false;
        CatalogProject a = CatalogParser.projectFromPage(firstItems, config.eventKeyword);
        CatalogProject b = CatalogParser.projectFromPage(secondItems, config.eventKeyword);
        if (a == null || b == null || !config.city.equals(a.city) || !CatalogParser.sameProject(a, b)) return false;
        UiItem x = first.target, y = second.target;
        return sameFooterLabel(x, y);
    }

    /** OCR may move an edge a few pixels between full reads of an unchanged label.
     * Both centers must remain inside both boxes; final root/footer validation remains required. */
    static boolean sameFooterLabel(UiItem x, UiItem y) {
        if (x == null || y == null || !x.text.equals(y.text) || !x.hasBounds() || !y.hasBounds()) return false;
        if (Math.abs(x.left-y.left)>4 || Math.abs(x.top-y.top)>4
                || Math.abs(x.right-y.right)>4 || Math.abs(x.bottom-y.bottom)>4) return false;
        int left=Math.max(x.left,y.left), right=Math.min(x.right,y.right);
        int top=Math.max(x.top,y.top), bottom=Math.min(x.bottom,y.bottom);
        return x.centerX()>left && x.centerX()<right && y.centerX()>left && y.centerX()<right
                && x.centerY()>top && x.centerY()<bottom && y.centerY()>top && y.centerY()<bottom;
    }
}
