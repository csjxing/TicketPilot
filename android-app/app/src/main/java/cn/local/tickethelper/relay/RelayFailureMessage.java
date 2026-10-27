package cn.local.tickethelper.relay;

import java.util.Set;

/** Only exact, locally defined messages may reach the UI; never echoes HTTP bodies or causes. */
public final class RelayFailureMessage {
    private RelayFailureMessage() { }
    public static final String UNKNOWN = "未能确认完成。请检查网络、中转站地址和管理 Key 后重试；未确认的记录保留。";
    public static final String CANCELLED = "已停止。未确认的诊断留在手机，可稍后继续。";
    private static final Set<String> SAFE = Set.of(
            "请填写文件中转站的 HTTPS 网站地址，不含账号、参数或其他路径。",
            "文件服务返回的上传地址不安全，尚未发送文件。",
            "请输入网站颁发的完整 Key。",
            "此 Key 没有文件中转站的管理权限，请在远见启用中转站并选择管理权限。",
            "文件中转站 Key 已失效或被撤销，请重新连接。",
            "文件中转站拒绝此操作，请核对 Key 的中转站管理权限。",
            "本次中转记录与服务器不一致，请先核查中转站；没有另建记录。",
            "中转站未确认本次操作，诊断保留在手机；稍后可继续核实。",
            "中转站返回的数据无法可靠核实，诊断仍保留在手机。",
            "本次中转已取消，待发送诊断保留；可稍后继续核实。",
            "网络中断或超时，未确认的中转记录保留；请稍后继续核实。",
            "无法安全连接文件中转站，请检查 HTTPS 地址与网络。",
            "诊断文件超出允许范围，尚未上传。",
            "诊断附件发送未确认，请保留本次记录，稍后点“继续上传”核实。",
            "上次中转记录无法读取，请先核查文件中转站；没有另建或删除记录。",
            "中转进度未保存，已停止网络操作；请稍后核查。",
            "本机待发送诊断尚未清除。",
            "上传会话不匹配，保留本次记录待核查。",
            "上传分片不匹配，保留本次记录待核查。",
            "无法核实中转结果，保留同一记录；稍后继续上传会先读取进度。",
            "待发送诊断已变化，请重新预览后再发送；没有上传新的内容。",
            "中转站尚未确认诊断附件就绪，请保留同一记录并稍后核实。",
            "请先连接文件中转站。",
            "上次诊断属于旧连接，请先在中转站核查，再明确清除本机待发送副本。",
            "请先预览并准备诊断。",
            "连接已变化，没有向新接收方发送旧诊断，请先核查待发送记录。",
            "中转站连接无法读取，请重新连接；没有上传或清除任何诊断。",
            "中转站连接未保存，请重试。",
            "中转站连接已变化，请重新核对后上传。",
            "中转站授权尚未移除，请稍后重试。",
            "本机诊断身份未能保存或读取，请重启应用后再试；购票资料未改变。",
            "诊断序号已满。",
            "诊断序号未保存。",
            "无法生成诊断，请检查手机日期和版本。",
            "无法读取本应用版本，诊断没有生成。",
            "本机诊断历史未保存。"
    );
    public static String display(Throwable error, boolean cancelled) {
        if (cancelled) return CANCELLED;
        if (error == null) return UNKNOWN;
        String message = error.getMessage();
        return SAFE.contains(message == null ? "" : message) ? message : UNKNOWN;
    }
}
