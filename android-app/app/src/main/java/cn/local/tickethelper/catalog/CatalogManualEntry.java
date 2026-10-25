package cn.local.tickethelper.catalog;

import cn.local.tickethelper.core.UiItem;
import java.util.List;

/** Read-only handoff rules. A ready result authorizes a new strict read, never a tap. */
public final class CatalogManualEntry {
    public enum State { WAIT, READY, WRONG_PROJECT, RESTRICTED }
    private static final long TOTAL_LIMIT_MS = 45_000, MIN_USER_TIME_MS = 30_000;
    private CatalogManualEntry() { }

    public static long deadline(long operationStartedAt, long now) {
        if (operationStartedAt < 0 || now < operationStartedAt
                || now - operationStartedAt > TOTAL_LIMIT_MS - MIN_USER_TIME_MS) return -1;
        return operationStartedAt + TOTAL_LIMIT_MS;
    }

    public static State inspect(List<UiItem> nodes, CatalogProject expected) {
        if (!CatalogParser.blockedReason(nodes).isEmpty()) return State.RESTRICTED;
        CatalogProject current = CatalogParser.projectFromPage(nodes, "");
        if (current != null && !CatalogParser.sameProject(expected, current)) return State.WRONG_PROJECT;
        long regions = nodes.stream().filter(item -> item.source == UiItem.Source.NODE && item.hasBounds()
                && item.resourceId.equals("cn.damai:id/project_detail_perform_flowlayout")).count();
        return current != null && regions == 1 ? State.READY : State.WAIT;
    }
}
