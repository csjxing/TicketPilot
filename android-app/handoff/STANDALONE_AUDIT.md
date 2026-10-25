# 手机脱离电脑运行审计

记录日期：2026-10-25。范围：当前 Android 工程、已保存的本台 vivo 证据、已安装 Shizuku 版本对应的官方源码。此审计未操作手机、未启动监控、未提交订单。

**当前结论：代码没有发现需要电脑持续提供的运行服务；本台 vivo 物理拔线后的完整 PREPARE 流程仍未验收。旧测试已确认助手被 Shizuku server 强行停止，但触发该停止的具体原因未确定。** 后续必须用新的真实证据更新验收状态，不能用源码审计、构建通过或“已连接”替代。

## 1. 哪些工作在手机上完成

| 能力 | 当前实现 | 证据边界 |
| --- | --- | --- |
| 配置、知识库、执行记录 | 助手本地 Activity、资源和应用私有文件 | 无需电脑提供服务 |
| 大麦可见页面读取、文字识别 | 手机无障碍服务、本地打包的 ML Kit OCR | 大麦联网查票仍依赖手机网络；助手不调用远端模型 |
| 规则判断和轮询 | 手机 `MonitorService`、本地 Handler 和规则引擎 | 没有电脑轮询进程或远端决策接口依赖 |
| Shizuku 点击 | 手机上的 shell UID 2000 UserService，调用本地系统输入命令，受前台/锁屏/目标/本次派发门禁约束 | USB 连接状态下 PREPARE 已有成功证据；物理拔线后仍待验证 |
| Shizuku 启动/恢复 | 当前既有证据为官方 ADB 启动；工程接受 shell UID 2000，不区分 USB 或无线启动 | Android 11+ 的官方无线调试启动可在手机完成，本台完整恢复流程尚未验证 |
| 自动锁单、付款 | 本审计不增加适配或权限 | PREPARE 不证明已锁票；本测试不选择观演人、不提交、不付款 |

检查范围为 `app/src/main` 和依赖声明：未发现电脑地址、socket、ADB 主机调用或持续电脑服务；Manifest 明确移除助手的 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限。`ShellTapUserService` 的 `ProcessBuilder` 在手机进程内执行 `/system/bin/dumpsys` 和受限制的 `/system/bin/input`，不经过电脑端 ADB。电脑目前用于开发、安装、最初启动 Shizuku 和诊断取证。

“无电脑运行时依赖”是代码层结论，不代表系统不会结束助手或 Shizuku。服务丢失时当前程序暂停并要求用户重新连接、主动开始，不会自动恢复抢票。

官方支持 Android 11+ 通过无线调试在手机启动 Shizuku；手机重启后仍需重新启动服务。这是官方能力，不是本台 vivo 已通过的结果：[Shizuku 用户手册](https://shizuku.rikka.app/zh-hans/guide/setup/)。

## 2. 旧物理拔线测试实际证明了什么

原始证据目录：项目根目录 `.runtime/android-device-test/`。下表时间均为北京时间。

| 2026-10-25 时间 | 已确认事实 | 文件 |
| --- | --- | --- |
| 11:11:00.730 | 电脑端观察到 USB ADB 可用 | `v016-usb-presence.json` |
| 11:11:31.311 | 助手主进程退出，`USER REQUESTED / FORCE STOP`，发起 PID 为 19687；现场已核对该 PID 为 `shizuku_server` | `v016-exit-info.txt`、已有现场进程核对 |
| 11:11:31.333–334 | 助手通知被系统取消 | `v016-notification-events.txt` |
| 11:11:55.555 | 电脑端首次观察到 USB ADB 不可用 | `v016-usb-presence.json` |
| 重连后 | `armed=false`、已准备、没有本轮 `START_RECEIVED` 或 `ARMED` | `v016-untethered-result.json` |

物理拔线发生在 11:11:00.730 与 11:11:55.555 两次采样之间；force-stop 也处于此区间。**无法确定实际拔线与 force-stop 的先后。** 已知停止和通知消失足以解释为何通知开始入口没有留下启动事件，但现有记录不能证明用户点击的精确时刻，也不能证明“拔线导致点击失败”。

目前未保存能够区分 Shizuku server 停止分支的同步日志，不能据此认定是权限撤销、provider 死亡、解绑或其他具体条件导致。

## 3. `unbind(remove=true)` 与 `destroy()` 的实际语义

当前 `ShizukuInputBridge` 为每次连接使用独立 tag，`daemon(false)`，进程后缀 `ticket_input`。断开时先取消该会话操作，再调用 `unbindUserService(..., true)`。在对应官方实现中，该调用找到指定 UserService 记录、移除记录、向其 Binder 发送 destroy 事务；没有在这条直接路径上调用主应用 `forceStopPackage`。

当前 `ShellTapUserService.destroy()` 验证调用身份、作废会话、结束正在执行的输入子进程、关闭 worker，再 `System.exit(0)`。它运行于 Shizuku 创建的 shell UserService 进程；这不能直接等同于对助手主包的系统 force-stop。`daemon(false)` 关联的是客户端 Binder 连接生命周期，不是 USB 线的连接状态。

因此，本审计**不建议凭猜测改成 `remove=false`、`daemon(true)` 或删除 `System.exit(0)`**。这些修改不能证明能修复旧 force-stop，还会改变已取消输入会话的回收语义。

工程按 UID 2000 验证调试身份，USB 与无线启动都可能符合。把错误提示中的“仅支持无线调试”改成“调试模式”属于准确性修正，不是脱机行为修复。

## 4. 精确官方源码依据

保存的安装来源为官方 `shizuku-v13.6.0.r1086.2650830c-release.apk`，来源记录为 `.runtime/android-device-test/shizuku/source.json`。

- Shizuku `v13.6.0` tag 对应 commit：`2650830c5b099ae0dd34fedf614d4f592ca05d65`。
- 该版本的 `api` submodule 对应 Shizuku-API commit：`510fc988c02c3475d8c25db170f96792f105bdf8`。
- 下述 API 源码用于核对安装版 server 的实现；不要把它误写成已经核对了助手所依赖的客户端 AAR 13.1.5 的全部源码。

| 官方路径 | 结论 |
| --- | --- |
| [ShizukuService.java L381–392](https://github.com/RikkaApps/Shizuku/blob/2650830c5b099ae0dd34fedf614d4f592ca05d65/server/src/main/java/rikka/shizuku/server/ShizukuService.java#L381-L392) | 权限标志变为不允许时，对相应客户端调用 `forceStopPackageNoThrow` |
| [ShizukuService.java L535–550](https://github.com/RikkaApps/Shizuku/blob/2650830c5b099ae0dd34fedf614d4f592ca05d65/server/src/main/java/rikka/shizuku/server/ShizukuService.java#L535-L550) | 发送 Binder 时如果 provider 的 Binder 已死亡，首次尝试会 force-stop 目标包并重试；这条分支输出 `provider is dead` 和 `kill ... and try again` |
| [UserServiceManager.java L62–98](https://github.com/RikkaApps/Shizuku-API/blob/510fc988c02c3475d8c25db170f96792f105bdf8/server-shared/src/main/java/rikka/shizuku/server/UserServiceManager.java#L62-L98) | `remove=true` 删除特定 UserService 记录并调用其 destroy |
| [UserServiceRecord.java L121–140](https://github.com/RikkaApps/Shizuku-API/blob/510fc988c02c3475d8c25db170f96792f105bdf8/server-shared/src/main/java/rikka/shizuku/server/UserServiceRecord.java#L121-L140) | destroy 对该 UserService Binder 发送清理事务，再释放回调列表 |
| [UserServiceRecord.java L20–30](https://github.com/RikkaApps/Shizuku-API/blob/510fc988c02c3475d8c25db170f96792f105bdf8/server-shared/src/main/java/rikka/shizuku/server/UserServiceRecord.java#L20-L30) | 非 daemon 服务在全部客户端回调死亡后回收 |
| [ShizukuUserServiceManager.java L27–38](https://github.com/RikkaApps/Shizuku/blob/2650830c5b099ae0dd34fedf614d4f592ca05d65/server/src/main/java/rikka/shizuku/server/ShizukuUserServiceManager.java#L27-L38) | UserService 由手机 `/system/bin/app_process` 启动 |
| [Service.java L43](https://github.com/RikkaApps/Shizuku-API/blob/510fc988c02c3475d8c25db170f96792f105bdf8/server-shared/src/main/java/rikka/shizuku/server/Service.java#L43) | 上述 ShizukuService 分支继承的日志 tag 为 `Service` |

已定位至少两条与旧 force-stop 现象相容的官方源码路径；**找到路径不等于旧测试命中了该路径**。只有带时间和目标包身份的对应运行证据才可进一步定因。

## 5. 最小物理断线 PREPARE 验证计划

不增加新大功能，不改变输入权限或 UserService 生命周期。使用新 APK 的 Main 前台开始入口，绕开旧通知入口这一独立待验收变量。目标为当前用户选定的谢霆锋杭州测试演出，实际日期、票价和数量须在手机真实页面重新核对，不能照搬旧日期。

### 测试前

1. 安装后确认新版本、手机解锁、助手无障碍服务开启；在助手中确认 Shizuku 正在运行、已授权且点击服务已连接。安装或服务重启后不能仅凭旧状态认定可用。
2. 确认当前目标和配置模式为 **PREPARE**；不选择观演人，不创建订单。保存配置不自动开始。先让真实大麦停在可识别的测试票档页面，再回助手 Main。
3. 记录手机当前时间、测试 APK 版本、助手与 Shizuku server PID，以及 `poll-history.jsonl` 的现有末尾位置/最后事件时间。仅读取必要状态白名单：模式、armed、输入模式、连接状态。不要导出全部 preferences、订单或实名信息。
4. 不清空系统日志，不重启 Shizuku，不强停助手，以免抹去失败前因或引入变量。只记本轮起始时间，重连后读取这个时间之后的增量。

### 用户在手机执行

1. 用户物理拔掉 USB，明确在拔线完成之后从助手 Main 点开始，核对弹窗后主动开始 PREPARE。拔线期间电脑不执行点击、不发启动指令，也不提供无线 ADB 控制。
2. 让程序尝试选票并进入确认购买页；看到确认购买页后保持不选观演人、不点提交、不付款。若弹出登录、验证码或阻塞，停止测试并记录界面说明。
3. 若短时间内没有前进，先记录手机显示的状态；不要反复点击开始或重新连接 Shizuku。重连 USB 后由电脑读取增量证据。

### 重连后最小取证

先取证，后重新打开/连接/暂停，以免覆盖失败状态。若测试还在运行或停在可继续操作的页面，优先在手机暂停，再记录暂停时间。

- 从 `files/poll-history.jsonl` 只取本轮新增事件。输出时间、state、elapsed_ms 和必要的脱敏原因；重点查看 `START_RECEIVED → ARMED → PROBING/TIER_SELECTED → CHECKOUT → 停止`。不输出观演人、联系方式、身份证、支付资料。
- 读取助手包的 `dumpsys activity exit-info cn.local.tickethelper`，仅保留本轮时间之后的退出记录；记录 reason、subreason、description 与 PID。无新退出也要明确记录。
- 读取本轮时间之后、限量的 `Service`、`UserServiceManager`、`UserServiceRecord` 和 `ActivityManager` 相关日志；在落盘/展示前过滤，仅保留目标包 `cn.local.tickethelper`、其 `.shizuku` provider、已知本轮服务记录或对应 PID 的条目。不保存全量 logcat。重点找 `provider is dead`、`kill ... and try again`、force-stop、服务删除/死亡。日志缺失不能反推没有发生。
- 核对助手和 Shizuku server 是否仍在、连接是否可用，记录本轮 armed 的最终值。页面证据仅保留公开的演出、场次、票档、数量及“确认购买”状态，避开任何实名区域。
- 对照用户确认的“先拔线，后点开始”顺序和本地启动时间。仅凭电脑 ADB 的稀疏离线采样不能恢复精确拔线时刻，也不能再次声称先后已知。

### 验收分级

| 结果 | 可得结论 |
| --- | --- |
| 有用户确认的物理拔线顺序；本轮本地 START/ARMED、自动选票与 CHECKOUT 完整；真实确认购买页可见；最后暂停且无提交 | **本次物理拔线 PREPARE 通过**；不外推为长期稳定、锁票成功或通知启动通过 |
| 有 START/ARMED，但输入阻塞/进程退出 | 单机启动入口已触发；按增量日志区分输入、页面识别、生命周期问题，完整 PREPARE 未通过 |
| 仍无 START/ARMED | 启动或主进程生命周期仍未通过；不能判定为库存或点击执行失败 |
| 只有通知、isReady、构建通过或方法返回成功 | 完整物理拔线运行仍未验收 |

测试结束后保持暂停；恢复本次联调临时修改的手机设置。若成功，另行验证手机重启后通过官方无线调试启动 Shizuku，以及较长时间离开电脑运行；本轮不把这两项提前标为通过。

## 6. 后续修复原则

当前最小改动仅是修正文案中的 USB/无线身份描述，并补齐上述证据。不因旧失败盲目改变解绑和进程清理逻辑。

若再次出现 force-stop：先取得同一时段明确的 server 分支证据；权限撤销与死 provider 重试必须分别处理。若日志仍不足，则保留“根因未定”，再添加范围受限的本地生命周期诊断。现有 `TouchDiagnosticActivity` 只覆盖本应用内无障碍节点/手势，不覆盖 Shizuku；不得把该自检通过写成 Shizuku 脱机链路已通过。
