package cn.local.tickethelper.core;

import java.util.List;

/** Narrow public-detail navigation contract; never a native-button or inventory claim. */
public final class DetailOcrNavigation {
    private DetailOcrNavigation() {}

    public static boolean isProposal(Decision decision) {
        return decision != null && decision.state == Decision.State.READY
                && decision.action == Decision.Action.OPEN_TICKETS
                && ("verified_return_detail_entry".equals(decision.reason)
                    || "verified_initial_detail_entry".equals(decision.reason))
                && decision.target != null && decision.target.source == UiItem.Source.OCR
                && decision.target.enabled && decision.target.hasBounds()
                && List.of("缺货登记", "立即购买", "立即预订", "购买").contains(decision.target.text.trim());
    }

    /** The full Engine return-context checks must precede this unique visible-label lookup. */
    public static UiItem uniqueEntry(List<UiItem> items) {
        if (items == null) return null;
        UiItem found = null;
        for (UiItem item : items) {
            if (item == null) continue;
            String text = item.text.trim();
            if (text.matches("(?s).*(?:确认登记|提交登记|登记成功|登记信息|填写登记|填写观演人|选择观演人|登记手机号|登记手机号码).*")) return null;
            if (item.resourceId.equals("cn.damai:id/project_detail_perform_flowlayout")
                    || item.resourceId.equals("cn.damai:id/project_detail_perform_price_flowlayout")
                    || item.resourceId.equals("cn.damai:id/layout_num")
                    || item.resourceId.equals("cn.damai:id/btn_buy_view")) return null;
            if (item.source == UiItem.Source.OCR && "缺货登记".equals(text)) {
                if (found != null || !item.enabled || !item.hasBounds()) return null;
                found = item;
            }
        }
        return found;
    }

    /** Same-frame label must be completely inside the live root's footer (bottom 20 percent). */
    public static boolean footerContained(Decision decision, int left, int top, int right, int bottom) {
        if (!isProposal(decision) || right <= left || bottom <= top) return false;
        UiItem label = decision.target;
        return label.left >= left && label.right <= right && label.top >= top && label.bottom <= bottom
                && (long) (label.top - top) * 5 >= (long) (bottom - top) * 4;
    }
}
