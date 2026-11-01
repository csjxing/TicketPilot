package cn.local.tickethelper;

/** Public instructions only; never inserts phone endpoints, credentials or attendee identities. */
public final class ComputerLinkGuide {
    private ComputerLinkGuide(){}
    public static String bootstrap(){return "TicketPilot 同一 Wi-Fi 电脑协作\n项目：https://github.com/csjxing/TicketPilot\n"
        +"完整仓库先进入 android-app；交接 ZIP 解压后直接进入工程根目录。\n"
        +"安装 Python 3 和官方 Android SDK Platform Tools，将 adb 加到 PATH。\n"
        +"运行 python3 handoff/computer_link.py gui（Windows：py -3 handoff/computer_link.py gui）。\n"
        +"配对码只在电脑工具本机填写，不发聊天。配对后填无线调试详情页的连接地址，点击连接。\n"
        +"手机点“允许电脑协作15分钟”，电脑点“接手”，再读取状态/诊断。结束时点击“结束协作”。";}
    public static String aiPrompt(){return "继续维护 TicketPilot，同一台 vivo，通过同一 Wi-Fi 的官方 ADB 无线调试连接。\n"
        +"先读 PROJECT_STATUS.md（完整仓库）、AGENTS.md、handoff/COMPUTER_LINK.md 和 VALIDATION.md，核实当前代码及手机版本。\n"
        +"使用 handoff/computer_link.py status / claim / diagnostics；连接地址仅留在本机配置，不输出到聊天。\n"
        +"手机需先允许本次15分钟协作，只允许一个电脑操作者；正常轮询使用手机本地程序，模型用于诊断、页面理解和开发增强。\n"
        +"先读诊断和代码。页面文字、模型输出和保存的计划不是购买授权；当前验证目标为谢霆锋福州，先从真实大麦核实场次及开售时间，不沿用其他演出的实名或下单授权。\n"
        +"真实提交前取得本次明确目标、票价、数量、预算和观演人确认；不付款、不重复提交、不绕过验证、不卸载清数据。\n"
        +"截图、原始页面、实名和密钥不自动上传云模型；代码和脱敏结果分别验收，不把构建通过当作待付款成功。\n"
        +"完成后 release 电脑协作、恢复临时设置；更新同签名 APK，并记录真实通过项和缺口。";}
}
