package cn.local.tickethelper.catalog;

/** Fixed, public instructions derived from the controller phase; never predicts an unread result. */
public final class CatalogGuidance {
    public final String title, instruction, after, button;
    private CatalogGuidance(String title, String instruction, String after, String button) {
        this.title = title; this.instruction = instruction; this.after = after; this.button = button;
    }

    public static CatalogGuidance busy(String status) {
        String value = status == null ? "" : status;
        if (value.contains("预约抢票")) return new CatalogGuidance(
                "在大麦打开场次页", "点一下「预约抢票」", "停在展开的场次页，助手会继续读取；无需提交抢票预约。", "收起，去大麦选场次");
        if (value.contains("页面底部的选票按钮")) return new CatalogGuidance(
                "在大麦打开场次页", "点页面底部的选票按钮", "按钮名称以大麦为准；打开场次页后停住，无需提交预约。", "收起，去大麦选场次");
        if (value.contains("立即预订")) return new CatalogGuidance(
                "在大麦打开场次页", "点一下「立即预订」", "停在展开的场次页，助手会继续读取日期。", "收起，去大麦点预订");
        if (value.contains("正在切回大麦")) return new CatalogGuidance(
                "正在返回大麦", "等待大麦页面出现", "助手会先确认大麦在前台，再读取；若原页面未保留，会提示重新打开并读取。", "收起，查看大麦");
        if (value.contains("请返回") || value.contains("切回")) return new CatalogGuidance(
                "下一步在大麦完成", "切回你要读取的大麦页面", "保持页面不动，助手会尝试读取演出与场次。", "收起，切回大麦");
        if (value.contains("搜索")) return new CatalogGuidance(
                "正在准备大麦搜索", "切回大麦，等待搜索结果", "助手会在真实大麦里搜索；完成后通知你回来选演出。", "收起，查看大麦搜索");
        if (value.contains("已检测到") || value.contains("正在核实") || value.contains("正在读取")) return new CatalogGuidance(
                "正在读取大麦页面", "保持当前大麦页面可见", "读完后，你将在这里选择刚刚读取的内容。", "收起，等待读取完成");
        return new CatalogGuidance("这一步正在大麦进行", "切回大麦，保持页面可见",
                "等待读取结果通知；如果出现验证或登录提示，请先在大麦处理。", "收起，切回大麦");
    }
}
