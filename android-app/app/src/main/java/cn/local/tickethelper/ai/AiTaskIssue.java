package cn.local.tickethelper.ai;

/** One-way classification: no original status text or interpolated personal data may escape. */
public final class AiTaskIssue {
    private AiTaskIssue() { }
    public static String describe(String status) {
        if (status == null) return "";
        if (status.contains("验证码") || status.contains("安全验证")) return "遇到平台验证，需要用户在大麦处理。";
        if (status.contains("锁屏") || status.contains("熄屏")) return "手机锁屏或熄屏，任务已暂停。";
        if (status.contains("不在大麦") || status.contains("切换应用") || status.contains("打开系统界面")) return "大麦未保持前台，任务暂停。";
        if (status.contains("调试输入") || status.contains("点击服务")) return "本机点击服务需要检查连接。";
        if (status.contains("无障碍") || status.contains("页面读取未连接")) return "页面读取服务需要检查连接。";
        if (status.contains("通知")) return "提醒通知设置需要检查。";
        if (status.contains("截止") || status.contains("日期已过")) return "目标时间或停止时间需要重新核对。";
        if (status.contains("提交结果无法确认")) return "订单提交结果不明，需在大麦手动核查，不能重复提交。";
        if (status.contains("订单") || status.contains("确认购买")) return "到达订单相关步骤，需在大麦核对；此摘要不确认锁票。";
        if (status.contains("超时")) return "页面读取或操作超时，任务暂停。";
        if (status.contains("未保存")) return "有未保存的配置修改。";
        if (status.contains("手动暂停")) return "用户主动暂停了任务。";
        return "";
    }
}
