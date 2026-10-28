package cn.local.tickethelper.core;

import java.util.List;

/** One fixed, reversible detail-settling gesture. Never a generic swipe or input command builder. */
public final class DetailScrollPolicy {
    public static final int DURATION_MS = 280;
    public static final long SETTLE_READ_DELAY_MS = 350;
    private DetailScrollPolicy() {}

    public static final class Plan {
        public final int x, startY, endY;
        private Plan(int x, int startY, int endY) { this.x = x; this.startY = startY; this.endY = endY; }
        public String[] command() {
            return new String[]{"/system/bin/input", "-d", "0", "swipe", Integer.toString(x),
                    Integer.toString(startY), Integer.toString(x), Integer.toString(endY), Integer.toString(DURATION_MS)};
        }
    }

    /** Only a full application frame on the default display can supply relative coordinates. */
    public static Plan forRoot(int left, int top, int right, int bottom, int displayWidth, int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0 || displayWidth > 16384 || displayHeight > 16384
                || left < 0 || top < 0 || right > displayWidth || bottom > displayHeight
                || right <= left || bottom <= top || right - left < 200 || bottom - top < 320
                || (long) (right - left) * 5 < (long) displayWidth * 4
                || (long) (bottom - top) * 5 < (long) displayHeight * 4) return null;
        int height = bottom - top;
        return new Plan(left + (right - left) / 2, top + height * 70 / 100, top + height * 38 / 100);
    }

    /** Content-only rebasing is usable exclusively for the one scroll, never a click/stock fact. */
    public static boolean mayPrepareFrame(long originalPage, long currentPage, long originalBoundary,
                                           long currentBoundary, int originalWindow, int currentWindow,
                                           long ageMs, List<UiItem> originalNative, List<UiItem> currentNative) {
        return currentPage >= originalPage && originalBoundary == currentBoundary
                && originalWindow >= 0 && originalWindow == currentWindow
                && ageMs >= 0 && ageMs < ReadStability.MAX_FRAME_AGE_MS
                && ReadStability.sameNative(originalNative, currentNative);
    }

    /** Conservative decoded-modal veto; unreadable/ambiguous identities never obtain a permit. */
    public static boolean unsafePage(List<UiItem> items) {
        if (items == null || items.isEmpty() || PageFailureSignals.blocksSelection(items)) return true;
        for (UiItem item : items) {
            String id = item.resourceId.toLowerCase(java.util.Locale.ROOT), text = item.text.trim();
            if (id.contains("dialog") || id.contains("popup") || id.contains("permission") || id.contains("modal")
                    || id.equals("cn.damai:id/project_detail_perform_flowlayout")
                    || id.equals("cn.damai:id/project_detail_perform_price_flowlayout")
                    || id.equals("cn.damai:id/btn_buy_view") || id.equals("cn.damai:id/layout_num")) return true;
            if (text.matches("(?s).*(?:验证码|验证|登录|登陆|确认购买|确认订单|提交订单|立即提交|待付款|立即支付|付款剩余|支付成功|确认登记|提交登记|登记成功|登记信息|填写|选择观演人|手机号码|手机号|选择场次|预约想看|票档|选择票价|购买数量).*")) return true;
            if (text.matches("(?:取消|确定|确认|提示|允许|拒绝|我知道了|知道了|稍后再说|暂不|同意|不同意)")) return true;
        }
        return false;
    }
}
