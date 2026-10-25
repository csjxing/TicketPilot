package cn.local.tickethelper.ai;

import cn.local.tickethelper.core.Observation;
import cn.local.tickethelper.core.UiItem;
import java.util.LinkedHashSet;
import java.util.Set;

/** One-way public label projection: never retains the original tree, coordinates, or image. */
public final class AiPageSummary {
    private AiPageSummary() { }
    public static String summarize(Observation frame) {
        if (frame == null || !"cn.damai".equals(frame.packageName)) return "未读取到大麦页面；库存未知。";
        boolean session = false, tier = false;
        for (UiItem item : frame.items) {
            String t = item.text.replaceAll("\\s+", "");
            if (t.contains("验证码") || t.contains("滑块") || t.contains("安全验证"))
                return "页面类型：系统或平台验证。需要用户手动处理；不读取或发送验证内容。库存未知。";
            if (t.contains("确认购买") || t.contains("观演人") || t.contains("证件") || t.contains("订单")
                    || t.contains("支付") || t.contains("收货") || t.contains("联系人") || t.contains("登录") || t.contains("个人信息"))
                return "页面类型：账号或订单相关页。隐私区域不采集；请用户自行核对。不能据此确认锁票。";
            session |= t.equals("场次") || t.equals("选择场次");
            tier |= t.equals("票档") || t.equals("票价") || t.equals("选择票档");
        }
        if (!session || !tier) return "页面类型：详情或未识别页面。未取得可靠场次与票档区域；不发送原始页面文字。库存未知。";
        Set<String> labels = new LinkedHashSet<>();
        for (UiItem item : frame.items) {
            String t = item.text.replaceAll("\\s+", "");
            if (t.length() > 60) continue;
            boolean price = t.matches("(?:看台|内场|VIP)?[1-9][0-9]{0,4}(?:\\.[0-9]{1,2})?元(?:缺货|缺货登记|售罄|有票)?");
            boolean date = t.matches("20[0-9]{2}[-./年][0-9]{1,2}[-./月][0-9]{1,2}日?(?:周[一二三四五六日天])?(?:[0-2][0-9]:[0-5][0-9])?");
            boolean time = t.matches("[0-2][0-9]:[0-5][0-9]");
            if (price || date || time || Set.of("缺货", "缺货登记", "售罄", "有票", "无票", "确定", "立即预订", "立即购买").contains(t)) labels.add(t);
            if (labels.size() >= 40) break;
        }
        StringBuilder result = new StringBuilder("页面类型：场次票档页。以下仅为可见标签，不能将独立缺货标签对应到某个票价；标签也不能证明可买张数。\n");
        for (String label : labels) result.append("- ").append(label).append('\n');
        if (frame.verifiedSelection != null) {
            Observation.VerifiedSelection s = frame.verifiedSelection;
            result.append("本地适配器同帧选中事实（不是订单）：单价=").append(s.selectedPrice)
                    .append("，数量=").append(s.quantity).append("，票款=").append(s.totalYuan).append("。\n");
        }
        return result.toString();
    }
}
