package cn.local.tickethelper.core;

import java.util.List;
import java.util.regex.Pattern;

/** Visible failures outrank both an order-page title and remembered SKU selection. */
public final class PageFailureSignals {
    private static final String ID = "cn.damai:id/";
    private static final Pattern QUEUE_BUSY = Pattern.compile("排队(?:的)?人数(?:太多|过多)|系统繁忙|服务繁忙");

    private PageFailureSignals() {}

    public static boolean queueBusy(List<UiItem> items) {
        StringBuilder text = new StringBuilder();
        for (UiItem item : items) text.append(item.text).append('\n');
        return QUEUE_BUSY.matcher(text.toString().replaceAll("\\s+", "")).find();
    }

    /** The observed native error container plus its visible message/return control is an error shell. */
    public static boolean nativeErrorPage(List<UiItem> items) {
        for (UiItem parent : items) {
            if (!nativeId(parent, "error_layout")) continue;
            for (UiItem child : items) {
                if (parent.left > child.left || parent.top > child.top
                        || parent.right < child.right || parent.bottom < child.bottom) continue;
                if (nativeId(child, "state_view_tip") && !child.text.trim().isEmpty()) return true;
                if (nativeId(child, "state_view_btn") && child.text.trim().equals("返回")) return true;
            }
        }
        return false;
    }

    public static boolean blocksSelection(List<UiItem> items) {
        return queueBusy(items) || nativeErrorPage(items);
    }

    private static boolean nativeId(UiItem item, String id) {
        return item.source == UiItem.Source.NODE && item.hasBounds() && item.resourceId.equals(ID + id);
    }
}
