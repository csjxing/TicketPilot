# 同一 Wi-Fi 的电脑协作

手机底栏点「电脑」。本模块用于任意一台已由用户配对的电脑接手同一台 vivo：检查、读取脱敏诊断、正常更新及继续开发。当前验证目标是谢霆锋福州站；先在真实大麦确认演出、日期及开售时间。历史票星球公告不等于大麦已核实。试选与实际提交分开授权，不沿用庾澄庆、温州、长沙或广州订单授权。

## 最短路径

1. 完整仓库进入 `android-app`；手机导出的电脑交接 ZIP 解压后即为工程根目录。安装 Python 3 和官方 Android SDK Platform Tools，把 `adb` 加到 PATH。
2. 手机与电脑连接同一 Wi-Fi，手机解锁。在系统「无线调试」打开「使用配对码配对设备」。手机助手点「电脑 → 打开无线调试设置」可定位系统设置；助手不会替用户开启权限。
3. 电脑运行 `python3 handoff/computer_link.py gui`，Windows 可用 `py -3`。点「查找同一 Wi-Fi 的调试地址」，选择与手机当前页面一致的地址，填6位配对码后配对。配对码只进 adb 标准输入，不写入参数或文件。
4. 使用无线调试**详情页**的连接地址连接。配对端口和连接端口不同；端口变化后在 GUI 重新选择。连接会核对 TicketPilot 包及已登记手机身份，拒绝自动切到另一台手机。
5. 手机点「允许电脑协作15分钟」，电脑点「接手」。助手本地监控暂停，只接受一台电脑的协作权；到期或手机重启后需手机重新允许。电脑可读状态/诊断，结束后点「结束协作」。

图形界面使用本机浏览器，无需 Tk。GUI 服务仅绑定127.0.0.1随机端口，校验Host、Origin及一次启动随机令牌，不接受其他网站命令。关闭终端服务会停止电脑界面；手机会话仍需结束或到期。需要 CLI 时：`--adb /path/to/adb` 和 `--state-dir /private/path` 放在子命令前。地址仅在电脑本机填写，不贴入聊天。

```sh
python3 handoff/computer_link.py pair
python3 handoff/computer_link.py connect
python3 handoff/computer_link.py status
python3 handoff/computer_link.py claim
python3 handoff/computer_link.py diagnostics --output /private/path/diagnostics.json
python3 handoff/computer_link.py pause
python3 handoff/computer_link.py release
```

`status/diagnostics` 回执带 request ID 对应校验；结果不明不推断成功。连接记录位于电脑 `~/.ticketpilot/computer-link.json`，含私有地址、手机身份摘要和电脑操作标识，不进入源码、交接包或公开 Release。配对密钥由 Android adb 官方工具保存，不迁移或自动复制给另一台电脑。

## 给电脑 AI 的工作流

手机「复制给电脑 AI 的说明」提供公开接手提示，不含连接地址或凭据。新电脑先读本文件、AGENTS.md、VALIDATION.md 和完整仓库的 PROJECT_STATUS.md；`claim` 成功后读取 `status` 和 `diagnostics`。在电脑完成诊断、代码修改、检查和同签名覆盖安装。普通刷票仍在手机本地，模型不按库存轮询频率重复请求。

工具只提供 `status/claim/pause/diagnostics/release` 固定操作。没有任意 shell、坐标、提交订单、付款或远程执行模型代码的入口。已有 ADB 系统权限仍可由操作者正常开发使用；协作权约束 TicketPilot 工具和手机监控，**不能阻止独立 ADB 客户端或用户手动操作**。同一台手机必须单人控制。

读取实时原生页面或截图时保持本地，必要时先向用户说明使用范围。不得自动将截图、实名、订单或密钥发送到外部模型。模型对页面变化的解释需由新鲜原生证据验证，不能据此移除订单防重和页面适配。实际提交必须有本次明确购票条件及观演人确认；仅官方待付款页核实后才报告完成，停止且不支付、不重试。

## 连接与退出边界

本版仅实现同一 Wi-Fi。无线调试本身不是异地远控，文件中转站也不是实时点击通道。未开放公网 ADB、路由器端口映射、无密码调试或额外云端控制服务。同网 AP 隔离、mDNS 不可达、锁屏或无线调试关闭时如实报告离线。

手机的15分钟开关不撤销电脑已取得的 Android 系统 ADB 配对。要撤销系统访问，用户应在「无线调试 → 已配对设备」中忘记该电脑，或关闭无线调试。本模块不替用户删除系统配对、不解锁手机、不清订单记录。

## 验收

代码测试验证端口校验、配对码不落参数/磁盘、手机身份不符拒绝、回执防串线、协作权互斥、到期/时钟变化拒绝，以及本机网页的来源校验。code41 已在原 Mac/vivo 通过 CLI 接手、读取脱敏诊断、拒绝另一逻辑客户端、释放及释放后拒绝；code42 浏览器界面已实际显示，真机安装摘要、顶部布局及 CLI 状态已核对。第二台实体电脑首次配对和浏览器按钮到手机的完整链路仍待验证；自动待付款未完成。通过构建不代表抢票完成。

官方依据：https://developer.android.com/tools/adb#wireless
