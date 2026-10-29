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
        return x.text.equals(y.text) && x.left == y.left && x.top == y.top
                && x.right == y.right && x.bottom == y.bottom;
    }
}
