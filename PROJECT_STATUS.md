# TicketPilot 开发进度与接手入口

## 2026-10-30 v0.3.7-preview / code34 countdown overlay

Added a touch-through accessibility overlay during the existing WAITING_FOR_SALE phase on Damai. It uses the same SaleTiming tick/remaining time as monitoring, displays HH:mm:ss.SSS and the configured opening timestamp in Asia/Shanghai, and indicates whether system automatic time is enabled. Text rendering is throttled to 50ms; the existing scheduler remains unchanged. The window is removed before official-page reads at opening, and on pause/stop/disconnect. It does not schedule taps, synchronize a server clock, bypass order gates, or imply millisecond order success. Pause remains available through the monitor notification.

The user authorized one Yu Chengqing test order (2026-12-19 19:00, 980 x 2, 1960). One normal operator submission was sent after current visible checks and a private persistent attempt marker. The user reported success. Subsequent official order-list observation showed both matching orders as closed, so no current pending-payment state or payment deadline is verified. No second submission or payment was performed. This is operator workflow evidence, not automatic TicketPilot checkout acceptance. Actual post-submit capture first showed the desktop; the transition cause is unverified.

Code34 unit checks, Lint/build and device overlay validation are tracked separately; initial build passed before the settings explanation change. Production checkout registry remains empty. User preselection was genuinely present in Damai; do not demand duplicate entry based solely on empty local app preferences. Public artifacts exclude all attendee/order identifiers.

## 2026-10-30 real confirmation and preselection audit

The user explicitly confirmed that the attendees were already preselected in Damai and authorized other concerts for flow validation. Live Changsha project booking-guide UI displayed two preselected attendees. This supersedes the mistaken inference that empty TicketPilot viewer preferences meant the user had not selected attendees. Do not copy identities into public artifacts.

Using normal operator UI navigation, Yu Chengqing Hangzhou 2026-12-19 19:00 was selected as a flow-only sample: 980 x 2, total 1960. Its actual confirmation page automatically carried two checked attendees. The same-version 9.0.37#109003700 native capture was complete (112 nodes, two native checkable/checked controls; two-pass capture finished at 251ms). The price-details sheet showed goods total 1960 and 980 x 2, with no additional fee line; its complete capture contained 149 nodes and finished at 418ms. These are reading measurements, not automated checkout or submission latency.

No submission or payment was performed. A separate user question is pending for one actual test pending-payment order on this different event; Changsha purchase authorization does not silently authorize this sample. TicketPilot remains PREPARE/armed=false and its saved Changsha plan is unchanged.

Concrete adaptation gaps: local viewer preferences do not ingest Damai preselection; the generic parser requires separate VIEWER_COUNT, EXTRAS_STATUS, EXTRA_FEE and checked AGREEMENT evidence that this visible confirmation page does not expose in that form. The two selected native checkbox states alone do not resolve those other fields. Actual amount-detail scope, submission consent semantics and official pending-payment structure must be reviewed before registering a production profile. Keep existing submission/deduplication gates. No synthetic production adapter or new app build was created in this audit.

## 2026-10-29 23:33 code33 live comparison

Installed v0.3.6-preview/code33 APK was read back from the paired vivo and matched the prepared artifact byte hash (SHA256 a131a8fa3798a310b32d06bdca67596bb60c2796e6cdda5b9bdf171a3b09fd1a). Accessibility and authorized Shizuku input were connected. Saved target remained Changsha 2026-11-06 19:00, 1680 x 2, total <=3360, PREPARE. No viewer was selected or assumed.

A bounded approximately 185-second local run recorded 44 returns, 44 entries and 43 session selections. Its 42 measured session-input-completion intervals had median 4137.5ms, minimum 3367ms and maximum 9241ms. Compared with the previous 4602ms median this is about 10.1% lower, but these are separate short runs, not controlled proof of server or checkout speed. There were 44 OUT_OF_STOCK and 169 transient UNKNOWN observations, no IN_STOCK and no unstable_page terminal pause. Stop was sent and verified PAUSED with armed=false. No order/payment was created; no display settings changed.

Most remaining recovery events concerned page changes during reads. Indefinite reliability, opening-time accuracy and positive-stock two-ticket pending-payment checkout remain unverified. Documentation only was updated after this run; the tested APK bytes were not rebuilt. GitHub release assets remain prepared locally, not published.

## v0.3.6 delivery state

Source commit cad9525 is pushed to origin/main. Same-signature APK, Android source ZIP and cloud-release.json are prepared under exports/release-v0.3.6-preview. GitHub release UI navigation succeeded but DOM/form access timed out again; no assets were uploaded and no public release URL is verified. User confirmed continuation; code33 installation and the bounded live comparison below are now verified. Public release upload is still incomplete.

## 2026-10-29 v0.3.6-preview / code33: remove duplicated pacing

Screenshot pacing now waits only the remainder of the existing 400ms capture cadence; time already spent recognizing the first frame counts toward the second-read wait. The platform cadence, two independent full reads, page/identity/geometry checks and freshness limits are unchanged. The same reader timestamp also paces ordinary monitor and verification captures.

A verified re-entry and target-session selection now preserve their refresh cycle's existing random deadline instead of sampling another interval after session selection. BACK, failed actions and other actions retain ordinary pacing; a quick cycle still cannot dispatch the next BACK before the original deadline. Existing saved minimum/maximum intervals are not changed. This does not prove that tapping a tier sends a new stock request, so the app does not replace real re-entry with an unverified tier-toggle loop.

History adds CYCLE_LATENCY: monotonic time between completed session-selection inputs, explicitly distinguished from server refresh and order latency. Three new regression tests cover capture processing time, clock rollback and preservation of the original cooldown/failed-action delay. 751 tests passed; Lint and APK build passed. Code33 device comparison is pending; the code32 median 4602ms and 60 session selections remain the latest live baseline. No speed guarantee, positive-stock checkout or pending-payment claim.

## 2026-10-29 22:19 code32 final bounded device run

User confirmed the vivo installer. Pulled installed package is v0.3.5-preview/code32 and matches prepared APK SHA256 `758a1cc81eeefd3a39b400b1d28a39d2151b462fa7b658c97ec00fb0b03512fa` (55,234,216 bytes). Accessibility and the already-authorized Shizuku input were ready. Target remained Changsha 2026-11-06 19:00, 1680 x 2, maximum 3360, PREPARE; no viewer identity was assumed.

Within the ten-minute exclusive window (installation/preparation included), local execution ran approximately 310.6 seconds, dispatched 60 detail entries, 60 target-session selections and 59 returns. It recorded 110 OUT_OF_STOCK observations, 227 transient UNKNOWN reads and no IN_STOCK. Session-to-session cycle median was 4.602s, range 3.625-9.162s. The run did not end with unstable_page; the deadline stop and explicit final stop recorded PAUSED and armed=false. This passes this bounded no-stock navigation regression, not indefinite reliability, a server refresh interval, positive-stock two-ticket checkout or pending-payment automation. No order/payment was created. Settings were not modified.

Source fixes are pushed to main. Public release assets are prepared locally under exports/release-v0.3.5-preview; GitHub Releases upload remains incomplete because browser control timed out and the file chooser could not be completed. No new public APK URL is verified. Phone installation is independently verified. Documentation updates after this run do not change the tested APK bytes.

## 2026-10-29 v0.3.5-preview / code32: second-read navigation refinement

Code30 was installed and its pulled APK matched the prepared hash, but a fresh run still paused after two complete detail/selector/session/back cycles. Code31 added bounded, non-content stage diagnostics and was also installed with a matching APK. Its live run again paused after two cycles: diagnostics include window-boundary interruptions and second-read mismatches even when both decisions report the verified detail entry. No positive inventory or order was observed. The merge fix alone therefore does not establish stability.

Code32 retains the merge fix and avoids unnecessary queries on discarded native layout nodes. It adds a narrow maximum four-pixel tolerance for OCR label edges in the reversible detail-navigation comparison, requiring both centers to remain strictly inside the intersection. Same text, project/city, two fresh reads, modal/SKU vetoes, final footer/root validation and all stock/order gates remain. Regression tests reject movement over four pixels, different labels, modals, expired frames and inventory actions. 748 tests passed, Lint and APK build passed. Geometry jitter is a candidate explanation for one rejection path, not a fully established cause of every pause.

Normal code32 cover installation failed with User rejected permissions. The phone remains code31 and was explicitly paused with armed=false before the exclusive window ended. Code32 continuous-run validation is pending user installer confirmation and a new testing window. No display setting was modified, no viewer was chosen, and no order or payment occurred. GitHub code30 release form was prepared but no assets uploaded/published: browser control timed out and the native file chooser did not complete. Do not present the planned download URL as an available release.

## 2026-10-29 v0.3.3-preview / code30: preserve detail-entry evidence

Fixed a deterministic merge conflict matching the code29 `ticket_entry_not_verified` failure: when native accessibility started exposing the same shortage-registration footer label, the merge removed its OCR counterpart, while the narrow detail-navigation contract required that independent OCR evidence. The merge now retains OCR provenance only for that exact public-detail label. Multiple OCR entries, registration modals, SKU fields, identity, two-read freshness and final footer checks still reject unsafe navigation. Price, quantity and checkout deduplication are unchanged.

Native collection now avoids parent/clickability queries for empty layout nodes that were discarded anyway; all descendants and retained evidence nodes are still visited. No inventory, order or payment gate was removed. Added three regression tests covering native-label appearance/disappearance, duplicate/modal rejection and unchanged price/checkout deduplication. 746 tests passed, Lint and debug APK build passed. Code30 is not yet installed or validated in a new continuous device run; the code29 six-cycle failure remains the latest live evidence. Pending payment and real opening-time precision remain unverified.

## 2026-10-29 21:33 code29 live regression: not stable

User confirmed accessibility and granted another ten-minute exclusive window. Installed code29 APK was pulled and matched SHA256 `2ded9e3f4c715d80d19bf023404d7233f4e96532dda08bd0916dede9ae88378f`. System accessibility dump showed the service bound with no crashed service; screenshot subsequently showed connected. A UIAutomator inspection can transiently disturb accessibility, so do not interpret its immediate readiness text as a persistent permission failure. Normal preparation-page input connected the already-authorized Shizuku service; all three preparation items then showed complete.

The first local PREPARE run dispatched 6 selector entries, 6 target-session selections and 6 returns. It recorded 11 OUT_OF_STOCK and 40 UNKNOWN observations, no IN_STOCK. Five session-to-session intervals had median 6.49s, range 4.23-7.90s. After the sixth return, `first_read_ticket_entry_not_verified` appeared, read durations rose to approximately 3.3-3.4s, and `unstable_page` paused the run. A second explicit start on the same detail page produced 7 UNKNOWN observations and no navigation, then the same pause. These are failed stability tests, not successful continuous monitoring or server request-rate measurements.

Normal StopReceiver recorded PAUSED before the authorized window expired. No viewer selection, order or payment; production checkout adapter remains unregistered. Original display settings were not modified. Next work must distinguish missing/ambiguous footer evidence, read-boundary changes and native-tree latency using fresh diagnostics; do not relax purchase gates or claim a root cause solely from the visible moving video. Candidate code29 is installed but is not a completed stability fix. This documentation-only follow-up does not create a new APK release.

## 2026-10-29 restored wireless session (installation pending)

User reopened wireless debugging and granted 15 minutes. Rediscovered the known host and verified the saved model; package code28, unlocked, armed=false, Changsha 2026-11-06 19:00 / 1680 x 2 / 3360 / PREPARE / 500-1500ms. Latest user failure log contains ARMED then 13 UNKNOWN observations (page_changed_during_read, ending unstable_page), no dispatched navigation. Current secure accessibility setting confirms TicketPilot service OFF; app preparation page also shows accessibility missing and Shizuku not connected. The new start broadcast produced no start log, so no automatic test run is claimed.

Manually searched sunyanzi and opened the official Changsha result; detail shows the matching project and dates, a city-level shortage label and shortage-registration entry. This is not target-tier two-seat inventory evidence. Rebuilt timing candidate plus a closed-code first-navigation diagnostic; 743 tests, Lint and build passed. Normal cover install is pending vivo system confirmation. Original display settings observed 600000/0 and left unchanged. No viewer selection, order creation or payment.


## 2026-10-29 v0.3.2 timing candidate: device validation blocked

User reported unstable Changsha monitoring and authorized the specific 2026-11-06 19:00 Changsha target, 1680 x 2, total <=3360, to pending payment. Two viewers still require explicit phone selection. The known paired wireless endpoint is not connected and reconnect failed; no current screen, inventory or failure logs were obtained. A new exclusive device-test window is pending. No monitor or order was started.

Candidate adds strict HH:mm:ss.SSS opening time input, bounded 10ms timer wakeups during the final 100ms, and local opening-wake/first-input-completion latency records. These are local clock/input measurements, not server synchronization, entry acknowledgement or guaranteed millisecond execution. All existing page, purchase, viewer and duplicate-order guards remain. Production order adapter registration is still empty. Do not claim inventory-to-order completion.

Final candidate checks: 743 unit tests, 0 failures/errors; Lint and APK build passed. This timing candidate does not resolve the reported instability without fresh device evidence and is not released as a completed fix.


## v0.3.1 delivery receipt (2026-10-29)

Source commit 69718c5759fb295a0fdb08c52036848528b0f81d pushed to main. GitHub release v0.3.1-preview is visible with APK, source ZIP and cloud-release.json; displayed GitHub digests match the prepared artifacts. APK: 55,201,914 bytes, SHA256 811ecb5bc5c096dc4dacdcd971d9c1053a8ebb69af735bdbab09aee84acde679. Full release download verification is incomplete (command-line download failed; browser manifest verified). Do not claim complete downloaded-asset verification.

User explicitly authorized this APK transfer to the file relay and subsequent wireless phone update. Normal wireless install returned Success and package manager read code28. Device then became offline; installed-byte verification did not pass and final armed readback is unavailable. Before install armed=false was verified; no monitoring was started this turn. Do not treat installer success as new UI or runtime validation. Relay upload is still being reconciled, not yet verified downloadable.


## 2026-10-29 v0.3.1-preview / code28: local history viewer

Added My -> ticket history: newest-first local events, inventory/action/error filters and manual refresh. Reads run off the UI thread; at most 300 matching events shown from bounded local retention (~2000 events). Counts are observations, not polling rounds. Existing random min/max cooldown is preserved and each sampled delay is now logged. Equal bounds mean fixed waiting; permitted fresh forward actions do not gain an extra random wait. This is not a server request interval or an anti-detection claim. No credentials, screenshots or viewer details are added.

740 existing unit tests passed; Lint and APK build passed. The new history screen is not yet device-validated. No phone test window is extended and no relay upload is authorized this round.

Final code27 device evidence: installed APK SHA256 46b08eb8c3751c623f5d77da227220a895206ebd085f39f3c3d72174d7fc4b93; 18 automatic selector entries, 18 session selections, 17 returns, 39 OUT_OF_STOCK observations and 83 UNKNOWN observations. No IN_STOCK or BLOCKED events. Session-to-session cycle median 6.69s, range 4.61-12.81s (not the configured 0.5-1.5s cooldown). Normal timed stop recorded PAUSED at 08:53:40. No orders created; positive inventory, two-ticket checkout and pending payment remain unverified. These results supersede the in-progress installation notes below.


## 2026-10-29 08:43 后续真机复测（进行中）

已拉取手机实际 APK，确认 08:42 安装的 code27 与当时修正版摘要一致；无障碍、通知和 Shizuku 在助手准备页均已核实完成。用户再次授权 10 分钟长沙缺票循环测试，仍不创建订单。通过助手“购票条件”正常保存快速节奏 0.5–1.5 秒，读回其他目标仍为 11 月 6 日 19:00、1680×2、上限3360、PREPARE。

08:45 再次自动进入详情入口、选目标场次并读取缺票。返回后两次语义读取可通过，但重复原生遍历把输入观察年龄推到 1397/1479ms，随后撞上新增 1500ms 条件，记录 PAGE_CONTENT_CHANGED 并丢弃未执行方案。未完成完整循环。现删除读取回调后的冗余整树遍历，最终导航采用既有 3000ms 新鲜度上限，两次完整页面核对、活动窗口及现场根节点校验保留。740 项测试、Lint及构建再次通过；新包等待手机系统确认，不能拿08:42的已安装包当作本次最新修改已安装。到测试窗口结束将正常暂停，不延长用户授权时间。


## 当前迭代：v0.3.0-preview / code27（2026-10-29）

本轮围绕首抢导航、详情动态内容及连续动作等待重构手机本地流程，详见 `android-app/handoff/LOCAL_EXECUTION.md`：显式启动的一次详情入口许可与 SKU 场次证据分离；同窗口两次完整新鲜 OCR 的身份/入口一致性仅可用于可逆详情导航；新鲜场次选择及绑定场次证据的票档试选可跳过整轮等待；缺票返回重进最低稳定等待从 500ms 调至 150ms；OCR 采集最低间隔从 700ms 调至 400ms；增加输入观察年龄记录。普通监控仍不调用模型，没有放开生产下单适配名单。

首个 code27 已由用户确认无线安装，真实从带弹幕的长沙详情页自动打开选票页、选择 11 月 6 日场次并读到缺票；08:28 返回后，二次语义读取已通过，但输入主线程校验超时，未完成连续循环。输入请求的观察年龄为首次入口 310ms、场次选择 230ms；这些不是首抢总耗时或服务器刷新频率。手机保存的轮次间隔仍为 5–10 秒，本轮未静默覆盖。

随后修复详情最后输入校验重复遍历整个视频原生树的耗时，保留两次完整读取和现场窗口/根节点检查，只有明确未执行的导航守卫拒绝才允许有限重读；未知结果不重试。最终代码检查 740 项测试通过、Lint 0 错误/40 警告、APK 构建通过。08:38 用户确认后再次拉取已安装 APK：摘要仍不匹配修正版，包管理器最后更新时间仍为 08:25；此前第二次安装请求已超时。已重新发起正常覆盖安装并延长等待，当前等待新系统确认，不能将第一包安装当修正版已安装。原 15 分钟测试窗口已结束，继续运行须先取得新的连续测试窗口。首次完整循环、快速刷新实测、有票双张、真实开售和待付款均待验收；当前不发布“稳定抢票已完成”结论。本轮长沙仅测试、不创建订单；优先无线，未使用文件中转站。

## 当前开发：v0.2.9-preview / code26 内部包（2026-10-29）

用户反馈 code25 缺票返回详情后仍停止。本轮无线读取真实 history：`SELECT_SESSION → OUT_OF_STOCK → BACK → page_changed_during_read → DETAIL_SETTLE / scroll_proposed_once → LOCAL_INPUT_GUARD / page_content_changed → detail_scroll_input_unconfirmed`。该码来自滚动专用 frame 检查；不能直接断言为公共输入层要求页面版本相等。原检查将时效、原生节点数量/文字/范围等变化统成一个原因，现已拆成不含原文的封闭差异码。

内部补丁保留严格原生核对，仅在服务明确确认尚未执行、结果不为未知且原因是原生变化时，允许原 30 秒许可内最多两次全新观察；旧帧不可复用，真实滚动仍最多一次。另修复稳定页面也会被无条件滚动的问题：只有原整帧复核不匹配时才尝试。主操作者手工发现原 32%/280ms 滑动会把标题和日期移出；新方案调整为 13%/800ms、随后 350ms 全新读取，Shizuku UserService 版本升 3 以重载更新后的策略。这些手工观察不计作助手自动滚动证据。

完整 Android 检查 **735 项测试通过，0 失败/错误/跳过；Lint 0 错误、40 条警告；APK 构建成功**。内部 code26 已经无线正常覆盖安装，包管理器读回 code26；实际安装 APK 摘要核对结果存于本机私有回执。用户给定的 10 分钟窗口结束后已正常停止，读回 `armed=false`、亮屏超时 600000、接电保持亮屏 0；**本轮未运行完整自动循环，没有创建订单，code26 不算刷票修复验收通过**。新的 Shizuku 服务连接与首次进入详情导航仍待验证；首次无 BACK 许可的入口缺口尚未修复。

本次修改尚为内部候选，未对外发布 v0.2.9。优先无线联调，未经本次确认不走文件中转站。公开最新仍为 v0.2.8/code25，下面为该版的历史安装和发布回执。

### 公开源码调研与后续方向

用户要求对照成熟抢票辅助，已核对 [RookieTree/DaMaiHelper 的控件查找与点击源码](https://github.com/RookieTree/DaMaiHelper/blob/master/app/src/main/java/com/rookie/damaihelper/Extensions.kt)、[mtrdong/DamaiHelper 历史适配说明](https://gitee.com/mtrdong/damai-helper/releases)、[Android UI Automator 元素等待](https://developer.android.com/training/testing/other-components/ui-automator)和[Appium 的空闲等待参数](https://appium.github.io/appium-uiautomator2-driver/latest/reference/settings/#waitforidletimeout)。开源样本按文字/资源 ID 找局部控件、短间隔有限重试和原生/手势点击；100ms 本地重试不等于服务器库存请求，也不证明兼容当前大麦或成功率。

工程方向是把可逆导航、库存/数量判断、实际订单提交拆成不同证据范围：导航优先看明确项目和目标控件，SKU 按目标日期/票档/数量核实，提交与待付款保留一次订单和金额/观演人核验。避免每一步都依赖全屏像素和无关节点绝对静止；局部语义合同仍须取真实控件树或受限 OCR 证据，不能凭宣传或模型输出注册生产适配。模型用于配置、页面学习和异常说明，手机本地状态机承担正常循环。先补完整返入与首次入口，再做正向双票/待付款，不用测试数替代真机闭环。

## 历史真机更新：code25 已覆盖安装（2026-10-29）

用户明确“直接更新到手机”。本轮重新发现并核对同一台已配对 vivo，手机解锁、原内部 code24、`armed=false` 后，正常覆盖安装已发布的 **v0.2.8-preview / code25**，没有卸载或清数据。安装器返回成功，包管理器读回 code25；从手机实际安装路径拉取 APK 后，长度 **55,155,701 字节**、SHA-256 `189f550a1ec2c0302c865fc3bf001c25bf32360f03856a4b99c2df69f771ca4e` 与发布包一致。

公开计划仍为孙燕姿长沙 2026-11-06 19:00、1680 元两张、最多 3360 元、PREPARE。模型加密配置文件仍存在，检查到的 5 份原有文件中 4 份摘要未变；应用主偏好文件有变化，未将其宣称为全量逐字节不变。已重新连接正常 Shizuku 点击服务，助手准备页实际显示无障碍、通知、点击服务三项完成。结束保持 `armed=false`，亮屏超时 600000、接电保持亮屏 0；没有启动抢票或创建订单。

这是安装与基本准备验收，不代表固定滚动、完整自动刷票、自动双票、待付款或拔线独立运行已通过。以下安装前“code25 未安装”的表述为历史发包时记录，以本节为当前设备状态。应用内容未改，不重建或重发同版本 APK。

## 本版开发与发布记录：v0.2.8-preview / code25（安装前）

本节是跨日联调后的最新事实。后续章节保留此前阶段记录，不能用旧“未提交”“当前庾澄庆目标”或旧暂停记录覆盖本节。**当前没有待付款订单，未付款；自动双票和自动下单至待付款仍未验收，v0.2.8 已发布；code25 尚未安装。**

### 庾澄庆：手工提交生成订单，最终为交易关闭

用户逐一确认当前已选的两位观演人，并明确“直接用支付宝提交”。主操作者在 **23:49:52** 通过真实大麦手工正常提交 **980 元 × 2 张 / 1960 元**，实际生成订单并跳转支付宝。随后用户明确表示自己点了返回或取消；官方订单详情和订单列表最终显示“交易关闭”。这证明手工提交曾生成订单，不证明助手自动提交或当前锁票成功，不能报告为待付款完成。

手机未检测到支付宝应用包是独立的设备观察，官方页面未给出关闭原因。不能将交易关闭直接归因为未安装支付宝；已明确的用户行为是返回或取消。私人姓名、证件及订单标识不写入本文件。

### 最新目标：孙燕姿长沙，仅测试

用户随后改为“孙燕姿长沙测试”，真实配置为 **2026-11-06 19:00、1680 元同档 2 张、上限 3360 元、PREPARE**。本轮长沙仅测试，不沿用庾澄庆的实单授权，不创建长沙订单或付款。

内部 **code24** 已多次正常覆盖安装。最新真机日志按原 UTC 时刻记录：**17:09:02 自动 `SELECT_SESSION` → 17:09:03 `OUT_OF_STOCK` → 17:09:09 `ACTION / back_dispatched`**。这条自动选场次、读到当帧缺货和返回动作已验证；之后详情页弹幕持续变化，出现 12 次 `page_changed_during_read`，最终以 `unstable_page` 暂停，**完整返回再进入选票页的循环尚未通过**。当帧缺货不作为之后仍无票的保证。

主操作者已按 15 分钟测试窗口结束运行，读回 **`armed=false`、`screen_off_timeout=600000`、`stay_on_while_plugged_in=0`**，亮屏和接电设置均为原值；这不等于已验证程序自动到时停止。本轮长沙测试未创建新订单。前置观演人界面和正常系统入口自动打开大麦的路径已进入内部包；预选仍由用户手填明确，不是从大麦读取观演人名单。

最终 `DetailScrollPolicy` / `SCROLL_DETAIL` 固定上滑补丁的代码已冻结：只在核实目标详情身份的缺货返回上下文中尝试一次普通滚动，随后重新完整读取，不续期原有许可，不将滚动命令完成当作页面稳定、重新进入成功或库存证据。**这份最终上滑补丁尚未覆盖安装、未真机验证，完整返入循环仍未通过。**

诊断源码新增 4 个固定枚举，区分滚动准备、命令完成、锚点未核实和输入未确认。`DETAIL_SETTLE` 只接受两个精确本机事件；失败原因只在 `UNKNOWN` / `BLOCKED` 状态映射，保留原状态。报告 schema 仍为 1，不增加页面文字、坐标、原始日志或实名字段；相关 Java 隔离检查 20 项、Node 接收契约检查 27 项通过，不替代最终整包或真机验证，新增事件的真机分享读回仍待验证。

### 最近检查与交付缺口

最终完整 Android 检查为 **731 项测试，0 失败、0 错误、0 跳过；Lint 0 错误、40 条警告，APK 构建通过**，覆盖最终滚动、诊断枚举和多行提示拦截。接收器/契约 27 项、发布工具 15 项检查通过。交付候选递增为 **v0.2.8-preview / code25**，以便从手机内部 code24 正常覆盖升级；code25 尚未安装。正式生产 profile 注册表仍为空，自动双票、生产自动提交及官方待付款均未验收。已发布 [v0.2.8-preview](https://github.com/csjxing/TicketPilot/releases/tag/v0.2.8-preview)，冻结源码为 `bc198a094159c194939152f71b8574e8861e0a8f`。三个自有附件的网页 SHA-256 均与准备包一致：APK **55,155,701 字节**，源码 ZIP **705,804 字节**，清单 **1,449 字节**。电脑通过 GitHub 浏览器实际下载 APK 与源码 ZIP，长度和 SHA-256 均一致；清单已核对网页摘要，未取得单独下载文件。APK 摘要 `189f550a1ec2c0302c865fc3bf001c25bf32360f03856a4b99c2df69f771ca4e`，证书与 v0.2.6 一致，内嵌源码 ZIP 与外部附件完全同字节。

完整 APK：https://github.com/csjxing/TicketPilot/releases/download/v0.2.8-preview/ticketpilot-0.2.8-preview.apk 。本回执在构建冻结后补入，不替换同版本附件或重建 APK。新文件中转站 https://model.weisee.com/relay 已通过正常网页上传同一 APK、源码 ZIP 和清单，三项均显示可下载。实际点击大 APK 下载仍被当前 OSS 域名的 4 MiB 安装包限制拒绝；未修改存储或安全设置。

另上传标准 `ticketpilot-0.2.8-install.zip`，内含原 APK、`SHA256SUMS.txt` 和安装说明。已从中转站实际下载：**23,174,976 字节**，ZIP 摘要 `c45c170c0697452cfc2f6767afbc486aeb2137b66c275b24637a1ec7f6615225`；解压 APK 摘要与 GitHub 发布包相同。手机在中转站搜索 **“TicketPilot v0.2.8 · 手机安装包（ZIP）”**，解压后通过正常系统安装器覆盖更新。上传、完整下载和包字节一致性已验证，code25 尚未安装，最终滑动、完整刷票循环和待付款仍未通过，不能用发包验收替代。

以下为此前阶段的原始事实与当时授权，均作为历史保留。

## 历史阶段：v0.2.8-preview / versionCode 24，恢复真机开发（2026-10-28）

用户已恢复开发，并明确授权实际目标：**庾澄庆杭州，2026-12-19 19:00，980 或 1180 元同档 2 张，应付总额不超过 2360 元，下单至官方待付款，付款由用户完成。**两位观演人仍须由用户在手机预选明确，不沿用旧姓名。下方“用户主动结束”和 v0.2.7“只测试、不创建订单”的段落均是较早阶段的历史记录，不代表当前授权或当前开发状态。

### 当前真机证据与未完成项

无线连接下已多次正常覆盖安装内部 **code24** 诊断包，并读回手机版本；无障碍、通知及 Shizuku 已在真机核实就绪。内部包安装成功不等于最终交付 APK 已冻结或发布。

助手已自动点击真实 **980 元票档**：输入命令完成日志与大麦真实选中画面相互印证。首次遇到 `page_boundary_changed` 时丢弃旧方案，重新读取后才成功；不能将首次失效方案或手工点击计为自动成功。原生数量路径中新出现的两层具名 wrapper 已现场确认，但**自动加号及随后两张/总额核验尚未通过**。目前总价读取卡在 `OCR_GEOMETRY_MISMATCH`，修复仍在进行，不能因已有票价选中而宣称自动双票成功。

联调者手工点击加号到 **2 张 / 1960 元**，已进入官方确认购买页；没有选择实名观演人、没有提交订单，也没有待付款订单。这是手工到达与页面学习证据，不冒充助手自动加号、自动双票或锁票完成。

前置观演人动线代码已完成，**尚未安装验收**；它不支持读取大麦已登记观演人名单。用户须在手机明确预选两位观演人。正式生产 checkout profile 仍为空，真实提交及待付款核验仍未完成。

### 检查与交付状态

本轮已有 **183 项 checkout 隔离检查**及**首轮全量 653 项测试通过**记录，当时 Lint 与 APK 构建也通过；后续仍有代码变更，以上结果不覆盖最终工作区。最终完整检查、签名与安装字节核验、APK/源码附件以及文件中转站和 GitHub 发布结果，留待主操作者实际完成后补入。**本段不声明 v0.2.8 已发布，也不声明已完成下单。**

以下保留各历史阶段的原始状态与授权边界；接手时以上节当前记录及新的现场核实为准。

## 用户主动结束本轮（2026-10-28）

用户明确“先结束任务吧”，已停止真机联调与开发，不继续测试或发布 v0.2.7。手机读回 `armed=false`、`NEEDS_ATTENTION`；没有创建订单。亮屏超时为原值 600000 毫秒，接电保持亮屏为原值 0，均已读回。工作区未提交改动保留，后续须重新核对 Git、设备与本次目标，不自行恢复。

本轮真实诊断分享已完成手机→文件中转站→电脑完整 JSON 比对；异网移动数据与管理 Key 直连仍待验收。内部 code23 自动试选于 08:52 请求票档试选，但本地输入返回 `dispatch_guard_rejected` 后暂停，画面仍为未选票档；没有通过自动双票或确认页验收。真实待付款适配仍未完成。后续指纹与诊断码补丁仅分项检查通过，尚未合并做最终整包构建、安装或发布；此前 626 项整包检查不覆盖这些后加补丁。

更新日期：2026-10-28（北京时间；v0.2.7 内部 code23 已正常覆盖安装并核对安装 APK 摘要，手机分享诊断→中转站→电脑全文读回已通过；本轮只测试流程，不创建订单；自动双票与最终交付仍待验证）。本文件用于在另一台电脑通过 Codex、Android Studio 等工具接手；每次先核对当前 Git 提交和真实设备状态。

## 当前开发版：v0.2.7-preview / versionCode 23（内部包已安装，最终构建与发布待完成）

### 当前设备与测试范围

内部 code23 已经正常覆盖安装，手机包管理器读回版本，并对实际安装 APK 核对摘要一致；这不等于本版最终交付包已冻结或发布。无障碍服务真实就绪，在助手中手动重新连接 Shizuku 后点击服务也已就绪。此前 code19、无障碍未开启及手机暂停的记录保留为历史，不代表本轮现状。

当前手机计划为**庾澄庆杭州，12 月 19 日 19:00，980/1180 元，2 张，上限 2360 元，PREPARE、`armed=false`、观演人数 0**。用户本轮仅允许测试流程，**不创建任何实际订单，不提交或付款**；旧谢霆锋温州购票授权本轮不适用，不能沿用旧观演人或目标。联调者已经在真实大麦手工将 1 张加至 2 张，并进入显示 **2 张、1960 元**的官方确认购买页；这是手工页面与数量核实，不是助手自动选两张的成功证据。

### 已通过：手机诊断分享闭环

真实路径已完成：票务助手预览本机 JSON → `ACTION_SEND` 打开远见中转 → 实际点击发送 → 中转站出现本次新记录 → 电脑浏览器展开全文。读回正文为 **6326 字符**，完整 JSON 的 `schema=1`、应用版本 code23、63 条事件和 `reportId` 与手机预览一致；未使用截断列表或旧报告代替本次结果。没有向公开仓库保存报告原文、私人记录或 Key。

用户日常最短路径：**我的 → 远程诊断 → 用远见中转 App 发送 → 打开远见中转 → 在远见中转里点发送**。手机已有远见中转授权时，无需在票务助手重复填 Key。助手显示“已交接”时仍不能判断上传完成；本轮成功结论来自远见中转实际发送及电脑取得同一报告全文。若远见中转已有未完成草稿，应先处理本次分享的边界，不将旧草稿或拼接文本冒充独立新报告。

这条链路使用公网 HTTPS，无需 USB、无线 ADB 或同一局域网；发送时电脑无需在线，可之后联网读取。但**本轮手机仍通过与电脑同网的 Wi-Fi 发送，尚未单独用移动数据/异网完成对照验收**。应用内“设置直连”管理 Key 路径仍未真机验证，不能用分享路径通过代替。诊断按用户主动发送，不是每轮自动上传，也不会自动唤醒 Codex；没有远程画面、任意点击、shell、解锁或付款功能。报告限于版本、权限、运行状态与固定枚举事件，不含原始日志、页面/控件树、截图、实名、Key 或订单正文。

### 仍在开发与待补证据

数量适配代码增加了受真实原生证据约束的 1→2 单次加号动作、动作后数量/总额重新读取及 6 秒转换屏障；未知或数量未变化时不重复点加号，避免加到 3 张。自动链路真机测试仍待完成，手工确认页不能代替它。

本机时序新增开售等待、到点核查、持续关注和到时停止，可配置开售时间与持续时长。事件后读取与已核验的新鲜前进动作减少普通等待，但不改变页面/数量/金额/权限/订单防重门禁，也不代表服务器库存请求频率。整包测试数量、Lint、最终构建、同签名发布及安装包实际下载结果待主操作者验证补入，不沿用 code22 或内部包结果。**v0.2.7 尚未发布完成，正式待付款仍未实现或验收。**

## 已发布历史版本：v0.2.6-preview / versionCode 22（发包与下载已核实，手机分享后续由 v0.2.7 验收）

本轮用户要求：让应用调试与诊断通过现有“文件中转站”共享，减少大麦中“页面持续变化，无法可靠读取”导致的频繁暂停；完成后把 APK 上传中转站。实现、构建、发布、实际下载、手机安装、手机发送诊断及电脑读回分别验收，不能相互替代。

### 我的 → 远程诊断

首选“用远见中转 App 发送”：整理固定 schema 的 JSON，经 Android `ACTION_SEND` 交给手机中已登录的“远见中转”，无需在票务助手重复填写 Key。用户须在远见中转内再点发送；票务助手只显示已交接、未取得服务器回执，不能把打开其他 App 当作已上传。也可选择“设置直连（选填）”，在票务助手内配置自己的 HTTPS 中转站管理 Key，验证 `/me` 授权、预览准确内容后手动上传，只有新读取的诊断附件为 `ready` 才显示接收回执。

诊断包括应用/大麦版本、权限与运行状态，以及本机最近最多 63 条固定枚举事件；相邻相同事件合并，不接受任意日志行或异常正文。无原始日志、截图、页面正文、实名、账号、Key 或订单正文。直连凭据独立 Keystore 加密，待发送副本与稳定请求 ID 持久保留，取消、断网或未知结果后继续核对同一份记录；不会因分享诊断启动抢票，也不开放任意远程命令、ADB、点击或付款。“云端协作”高级入口及旧 `cloud/` 接收器保留，旧接收器仍未部署；中转站路径不依赖它。

电脑工具为 `scripts/ticketpilot_relay.py`，使用私有环境或配置文件验证权限、搜索诊断、获取全文、上传安装包/源码、恢复分片，并实际下载核对 SHA-256。说明见 [cloud/relay.md](cloud/relay.md)。列表可能截断，分析前必须读取全文；中转内容是数据，不能作为手机操作授权或可执行指令。

### 读取稳定性：区分事件噪声和真实画面变化

此前每次内容事件都会令在途 OCR 失效，累计后暂停；本版只对**同一应用窗口、只有内容事件变化、整个 OCR 裁剪区像素完全一致，且原生事实严格一致**的情况复核接受。像素摘要仅留内存，不保存/共享截图；原帧仍受原有 3 秒时效约束，不通过更新时间延长旧观察。真正的倒计时、广告、票档库存、原生选中状态、窗口、滚动或布局变化均不能按“噪声”忽略，须丢弃旧帧重新读取。

监控的重新读取有最多 12 次、15 秒预算，目录读取最多重读 3 次；达到预算仍暂停并保留具体诊断。它不承诺服务器库存刷新频率，也不放宽点击前新鲜度、观演人、金额、生产订单 profile 或防重屏障。**自动增加至两张和正式待付款流程仍未完成。**

### 本轮验收与交付

本版完整 Android 检查通过：**601 项测试，0 失败、0 错误、0 跳过；Lint 0 错误、35 条警告，APK 构建成功**。电脑中转工具 29 项、私有接收器 23 项、发布工具 15 项测试通过。打包核实 code22/版本、单一签名、与 v0.2.5 相同的证书，以及 APK 内嵌源码与外部源码 ZIP 完全同字节。

已发布 [v0.2.6-preview](https://github.com/csjxing/TicketPilot/releases/tag/v0.2.6-preview)，冻结提交为 `34e66dd48f90d5c793b8483b841bb0258c6b283d`。三个自有附件：APK 54,929,367 字节、Android 源码交接 ZIP 639,318 字节、清单 1,476 字节；GitHub 页面三个 SHA-256 均与本地准备包相同。APK 摘要为 `bd83dd34c2b161ec442a13c9c8b0f70b687068f4cca330148bb5324d0b6ed6e7`，直接下载地址为 https://github.com/csjxing/TicketPilot/releases/download/v0.2.6-preview/ticketpilot-0.2.6-preview.apk 。此发布回执在构建冻结后补入，不重建或替换同版本资产。

文件中转站已通过已登录网页上传上述三个文件并显示发送成功。本轮点击大 APK 下载时，页面实际返回“默认 OSS 域名仅支持不超过 4 MiB 的 APK/IPA”；没有修改存储或访问控制。另上传标准 `ticketpilot-0.2.6-install.zip`，内含原始 APK、摘要和安装说明，23,149,596 字节；已从中转站实际下载，ZIP 摘要为 `1bdd8fabd98a7d697720a42a241fd1142a2fef293f6de550d3f8c4064281d6c8`，解压的 APK 摘要与上述构建一致。在中转站搜索“TicketPilot v0.2.6 · 手机安装包（ZIP）”，可下载后解压覆盖安装。生产文件上传、浏览器下载与字节一致性已核实；本版手机内分享、直连和大麦稳定性效果仍待真机验收，不能将电脑文件传输当作这些手机链路通过。

GitHub 发布页面与 tag 已核实，但本轮浏览器未取得该站 APK 的实际下载记录，本机匿名直链请求返回 404；不以页面附件清单代替 GitHub 下载成功，也不推断仓库访问权限原因。已验证可用的安装字节来自上述中转站 ZIP；手机内 GitHub 更新请求仍待验收。

v0.2.6 发布收尾时仍遵守用户当时的暂停，没有安装或创建订单；当时最后核实为 v0.2.3/code19。后续已恢复本轮指定的流程联调，内部 code23 安装与诊断分享结果见顶部 v0.2.7 记录；不以旧基线覆盖新证据。页面稳定性修复的实际效果仍需独立对照验证。

## 已发布历史版本：v0.2.5-preview / versionCode 21（已发布，真机验收待完成）

本版增加仅调试构建可用的只读原生结构诊断。`DebugUiSnapshotReceiver` 不导出，仅能由同应用 UID 显式手动触发；不添加截图、点击、坐标、导航或网络上传能力。采集前须确认无障碍服务真实就绪、监控停止、`armed=false`、手机解锁且真实大麦位于前台。保留空布局等结构节点，以便之后取得数量加号的实际控件证据；文本仅允许固定公开标签、日期时间、受限金额和张数，其他文字脱敏，受保护节点及子树不读取文字。

结果经两次结构一致及窗口/根节点新鲜度检查后，原子写入应用私有 `files/debug-native-structure.json`，附实际大麦版本、时间、窗口及有限结构字段。深度、节点数、时间和文件大小均有上限；超时、变化或失败不保留有效节点数组。该文件不进入云端诊断、Git、公开 APK 资产或源码交接 ZIP，也不直接生成适配或购买授权。

本轮完整 Android 检查：**564 项测试通过，0 失败、0 错误、0 跳过；Lint 0 错误、35 条警告，APK 已构建**。真实数量加号、生产订单页与待付款适配仍未完成，没有创建订单。

已发布 [v0.2.5-preview](https://github.com/csjxing/TicketPilot/releases/tag/v0.2.5-preview)，tag 已通过 Git 读回指向冻结提交 `b5870764d15e03af11a2e0b0c0653dbc5ebd42d5`。三个自有附件为 APK（54,767,509 字节）、Android 源码交接 ZIP（599,516 字节）和 `cloud-release.json`（1,348 字节）；GitHub 显示的三个 SHA-256 均与本地 bundle 一致，APK 内嵌源码 ZIP 与外部源码附件同字节。APK 已由 Chrome 实际下载，长度与 SHA-256 均核对一致：`10ea88f92bda651759638988a2458306f6f8b84647cc97d02f78c46e54eb0bce`。完整下载地址为 https://github.com/csjxing/TicketPilot/releases/download/v0.2.5-preview/ticketpilot-0.2.5-preview.apk 。本段发布回执为发布后补充；APK 和内嵌源码保留冻结提交中的说明，不以文档回执重建同版本。

### 10 月 27 日设备交接：用户在用手机，已停止操作

无线调试已重新连通并核实解锁，手机实际仍为 **v0.2.3-preview / code19**。正常覆盖安装 v0.2.5 被 vivo 安装器取消，返回 `INSTALL_FAILED_USER_RESTRICTED`；没有安装成功。系统全局无障碍值为 0，助手服务未配置开启，因而本轮没有通过新原生结构采集。前台在游戏、视频等页面间变化，用户随后明确“暂时在使用，稍后测试”；不据此宣称大麦或助手出现新的 UI 缺陷。

联调操作和同聊天跟进已暂停，不自动恢复监控。临时 wm 旋转、自动旋转、用户旋转与亮屏超时均已恢复原值并读回；亮屏超时恢复为 **600000 毫秒**，私有回执为 `.runtime/android-device-test/resume-user-busy-handoff.json`。手机原有模型配置、用量、签名与订单防重记录保留。当时温州配置为 2026-10-31 19:00、1680 元 × 2、最多 3360 元、PREPARE、`armed=false`，观演人数为 0；用户将在手机上另行明确两位观演人，不沿用旧姓名。

用户恢复测试后，重新核对当前连接、解锁、实际版本及权限，再按正常系统确认覆盖更新；先取得新的真实数量加号和订单页证据，再继续适配。**该历史阶段没有在抢票，也没有待付款订单；本轮状态见顶部 v0.2.7。**

## 已发布历史版本：v0.2.4-preview / versionCode 20（云端协作构建通过，尚未安装或部署）

已发布 [v0.2.4-preview](https://github.com/csjxing/TicketPilot/releases/tag/v0.2.4-preview)，代码提交 `038bfb71c7b507f2418761de1607406fc37f3ddd`。三个附件为 APK、Android 源码交接 ZIP 和 `cloud-release.json`。GitHub 页面显示的三个 SHA-256 与本地一致；APK 已由 Chrome 实际下载，54,829,177 字节，下载文件与构建一致：`fd08602696418dcbdbebb06297fa82b1753ea8a9fc82141231605a2d3f75d9a4`。证书与已安装 v0.2.3 相同。发布回执后补至本文件，发布 APK 与内嵌源码仍保持该提交的冻结内容。

“我的 → 云端协作”集中检查 GitHub 更新、下载安装及私有诊断同步。更新客户端只读取 `csjxing/TicketPilot` 的发布清单与 APK，核对字节数、SHA-256、包名、版本和当前安装签名后打开正常系统安装器；安装需用户在手机确认，同签名覆盖升级，不卸载或清除资料。发布脚本位于 `scripts/release_ticketpilot.py`，构建产物通过 Releases 分发，不提交到 Git 历史。

私有诊断使用 HTTPS 一次性配对，设备 token 在手机 Keystore 加密保存，与模型密钥分开。可手动上传，也可自行开启“状态变化时自动同步诊断”；默认关闭自动上传，按至少 15 秒间隔合并变化，不在每轮抢票中发送网络请求。仅同步版本、权限、运行状态和固定事件码，不上传原始日志、截图、页面正文、实名、密钥或订单内容。临时网络错误退避重试；授权、格式或序号等需处理错误停止自动重试。用户可明确清空待发送诊断，保留随机安装身份、递增序号及订单防重记录。

`cloud/` 已实现私有接收、授权电脑按游标读取及 `reports --follow` 持续读取；23 项 Node 测试通过。它尚未部署，正在等待可用的私有 HTTPS 主机，不存在已连通的手机公网诊断服务。GitHub 代码与发布、手机安装结果、私有云接收、另一台电脑读取分别验收；云端没有远程 ADB、shell、任意点击或支付接口。

v0.2.4 当轮 Android 完整检查已通过：558 项单元测试，0 失败、0 错误、0 跳过；Lint 0 错误、35 条警告，APK 已构建。私有服务 23 项 Node 测试及发布脚本 15 项 Python 测试通过。v0.2.4 发布时手机无线离线，没有该版安装、手机内下载更新、手机公网配对或上传验收；本轮最新连接与暂停状态见上节。APK 发布不等于该手机已更新，服务器收到诊断也不等于选票或下单成功。

v0.2.4 当轮正式目标为：**谢霆锋温州 2026-10-31 19:00、1680 元 × 2 张、应付最多 3360 元**，当时用户授权到官方待付款，两位观演人待手机明确。此段仅保留历史目标与未完成结果；本轮现为庾澄庆杭州流程测试且禁止创建订单，不能恢复旧授权。生产订单页适配及待付款仍未完成。

## 已安装基线：v0.2.3-preview / versionCode 19（准备项与悬浮读取通过）

用户在系统开启服务后，助手仍提示未开启。最初真机观察为服务组件保留在 enabled 配置、系统全局值为 0、Bound 为空、Crashed 含本服务；退出记录显示近期因调试状态变更被强停，未找到本应用的 Java 崩溃堆栈。已记录原值并清理本应用遗留的 debug-app 标记，未直接改写无障碍授权设置。

本版区分未开启、已开启但未连接、已连接、读取状态失败。系统配置及实际初始化状态均纳入刷新和启动门禁；返回准备页立即重新检查。服务完整初始化后才发布 ready，在解绑、销毁及失败清理时撤销。510 项 Android 测试全部通过，Lint 0 错误、35 条警告。

**无线覆盖更新后已由手机包管理器读回 code19 / 0.2.3-preview。**安装 CLI 曾在 vivo 普通安装确认页等待并于 120 秒超时；联调者随后点击已获授权的确认勾选和“继续安装”，手机实际完成更新。没有收到 CLI 的 `Success` 字符串，不以该字符串作为本次安装证据。08:12 出现 `SERVICE connected_ready`，08:13 准备页的无障碍、通知、Shizuku 三项均完成；此次没有重新授予无障碍权限，更新后系统自动重绑。系统全局值为 1，Bound 区可按中文服务标签识别本服务，Crashed 不再含本服务；仅用包名字符串匹配 Bound 区会漏报。

08:14 从计划进入真实大麦，悬浮面板显示且可以收起。08:15 由联调者手动在大麦选择 10 月 31 日场次，再点悬浮“使用当前场次”；日志产生 `public_tiers`、`result_shown`，面板正确显示六档票价，读取时全部缺货。场次点击属于人工准备，不计作助手自动选日期。读取结果直接留在大麦上方，没有通过助手 Activity 或通知来回切换。

08:18 出现保存计划及 PREPARE 启动日志，但发生在联调者独立点击之前，操作者尚未单独确认，不能写成无人干预或完整自动测试通过。该轮手机实际配置为温州 2026-10-31 19:00、1680 元两张、上限 3360 元、PREPARE，`armed=false`。程序对预约预选页连续三轮返回 `reservation_selection_only / NONE` 后自动停止，没有最终预约、下单或付款。本轮面板关闭有开始监控日志相对应，不能归因于前台空 root 或宣称发现了新的悬浮消失缺陷。

v0.2.3 手机安装包已读回，SHA-256 与交付包一致：`8e27664b9f1d902b3d84eb2bb43b2a65f187be5a7c63bfa0a996071840b906b7`。模型加密配置、额度和订单防重文件保持不变；温州当前目标字段已逐项核实，不能拿旧杭州配置哈希要求目标未变。随后学习杭州 2026-11-14 19:00 页面，仅见 780/980 元未标缺货，未取得两张选择或新的确认页证据，未创建订单。学习期间将亮屏超时由原 600000 临时改为 1800000 毫秒，当时后续无线离线而未恢复；本轮重连后已恢复为 600000 毫秒并读回，见上节交接回执。真实数量加号、生产订单页适配和待付款仍未完成；订单 profile 为空。**两张配置、准备页通过及悬浮读价都不等于锁票成功。**下方 v0.2.2 与 v0.2.1 章节保留各自当时的阶段证据，不替代本节当前状态。

## v0.2.2-preview：悬浮交互开发记录（未安装验收）

本轮把常用选票流程移到大麦内的无障碍悬浮助手，不新增“显示在其他应用上层”权限。用户在真实大麦选择场次，再点悬浮助手“使用当前场次”；这一步只读取当前已选场次，不替用户点击场次。随后在悬浮助手里多选接受票价、选 1 或 2 张、核对并保存，之后可主动开始 PREPARE 试选，到确认购买页暂停。读取结果留在悬浮助手，不再要求通过通知切回助手 Activity。这是本轮实现动线，真机效果仍待验收。

空计划首页移除无效搜索框，改为一键打开大麦悬浮助手；计划与领航员的主入口已切到新动线。主页简化已加入当前源码，开发 APK 已构建，手机安装与真机验收仍待完成。

读取、保存和启动前会真正移除悬浮视图，等待原大麦页面稳定后再核验；锁屏、离开大麦或无障碍服务中断时关闭。悬浮入口与原目录共享保存校验：配置 revision、5 分钟读取有效期、完整日期时间、演出关键词及唯一票价。保存保持暂停，清除旧观演人绑定，不继承旧 `LOCK_ORDER` 授权。两张配置和金额计算已加入，1680 元 × 2 张为 3360 元；实际购买前仍须从页面按明确字段类型核实张数和总额，预约页面禁止购买动作。

两位观演人核心已增加逐人匹配、稳定的集合授权哈希和个人选择身份哈希；每位独立进行 6 秒复读防重，不以坐标判断是否同一人，同帧确认两位全选后才可继续。数量必须等于观演人数，单价 × 数量须等于小计，应付不得超过预算；订单 journal 的 VERSION1 格式与永久防重屏障保持。“我的 → 观演人”提供手机本地 1–2 位配置，AppStore、Monitor 和高级工具的多位授权已接入，不上传模型、不自动选择真实名单。高级保存两张配置保留多位字段；从目录保存新目标仍清除旧绑定。

**两张目前只支持计划配置，以及大麦已实际选中两张时的数量/金额核验。**真实数量加号尚未取得适配证据，程序不会自动从一张加到两张，不能把 1680 × 2 的配置当作已完成自动双票试选。生产订单适配名单为空，真实订单及待付款完全未验收。

最新完整 Android 检查通过 503 项测试，0 失败、0 错误、0 跳过，Lint 0 错误、35 条警告，已完整覆盖个人身份哈希补丁；独立 checkout 检查 183 项和两张 TransferBundle 往返保持通过记录。503 项为最新代码检查记录，交付 APK 的构建与哈希记录见 `android-app/VALIDATION.md`。手机先前锁屏、首次安装被拒，随后无线调试变为离线，未重装 v0.2.2；手机仍以 v0.2.1 为已验证历史基线。新目标未写入手机、未开始监控、未下单。

本轮用户目标为谢霆锋温州 2026-10-31 19:00、1680 元 × 2 张、票款 3360 元，未包含观演人实名。官方开售时间为 2026-10-27 15:00；本轮凌晨 1 时许观察仍为预约无票，不能声称已开始抢票或能够下单。用户要求以真实待付款订单作为完成标准；保存计划、确认购买页或构建通过均不等于完成。

### 已安排的继续任务

历史安排：曾通过自动化工具将同聊天原有过期心跳更新为“TicketPilot 温州待付款测试”，工具已读回 ACTIVE；一次性起点明确为 2026-10-27 06:45 UTC，即北京时间 14:45。原孙燕姿任务不再运行。届时需要电脑开机、Codex 应用运行，以及手机解锁并连接，两位观演人仍待用户在手机确认。此记录只证明当时任务已安排，不表示届时必定连接、已开始抢票或已有待付款订单；本轮购票目标仍未完成。当前用户在使用手机，同一跟进已设为 PAUSED，等待用户恢复测试。

## v0.2.1-preview / versionCode 17：已安装的历史基线

本轮修复用户从助手选择具体项目或场次后卡在“这次读取已停止”的交接缺陷。现场失败码为 `foreground_changed`：原实现收起目录任务后只等 750 毫秒就读取，误以为大麦一定已经回到前台；从计划或领航员返回目录时可能仍停在助手。

项目、场次、搜索和重新读取现已统一通过系统正常打开大麦，并在 20 秒交接预算内同时确认前台稳定 500 毫秒、页面事件安静 300 毫秒后才开始一次新鲜读取。保留原演出、场次、页面新鲜度及点击核验；若系统恢复了其他页面，停止并明确要求重新打开原页。日志新增固定操作类别和轮次，用于区分重新读取产生的新超时与已经取消的旧操作。

**最终包已读出温州两个演出日期，并通过 10 月 31 日场次选择到六档票价的真实助手链路。**最终构建通过 448 项 Android 单元测试，0 失败、0 错误、0 跳过；Lint 0 错误、35 条警告。正常无线覆盖安装返回 `Success`，读回手机为 code17 / 0.2.1-preview，APK 字节哈希与交付包一致。加密模型配置、用量、订单防重记录及原购票配置字段哈希保持不变，`armed=false`。无障碍开启、TalkBack 关闭，原亮屏时长 600000 毫秒未改变，临时调试属性已恢复；设备操作已停止。哈希与分阶段真机证据见 `android-app/VALIDATION.md`。

首包曾恢复到大麦首页并触发 `project_choice_changed`。启动方式现已改为匹配系统桌面的显式 `MAIN + LAUNCHER`，使用 `NEW_TASK | RESET_TASK_IF_NEEDED`，不使用 `REORDER_TO_FRONT` 或清栈标志。2026-10-26 23:46 的诊断包通过 11 月 1 日场次点击与六档票价读取；最终包于 23:56:30 读出 10 月 31 日、11 月 1 日两个 19:00 场次，23:56:54 在助手选择首场后，由助手恢复原票档页、自身点击并核对，23:56:58 产生 `public_tiers`，返回助手显示首场六档金额及读取时缺货。第二场点击属于此前诊断包证据，不冒充最终包复测。

目录读取已精确保留原先被丢弃的空文字布局节点。10 月 31 日未选状态的 OCR 日期向左越界 16 像素，现按同一场次格的横向区域及原演出纵向范围修复，并已通过首场真机链路，不放宽跨行匹配。最终包票价格展示仅金额与读取状态。联调者人工返回详情再进入预约页以清空未提交的预选；后续首场点击由助手自身执行。此前有并行人手操作的前台或页面变化失败不能作为无人操作下的稳定复现。

本轮按用户要求仅以谢霆锋温州站练习目录。2026-10-26 官方页面显示：2026-10-27 15:00 开售；演出为 2026-10-31、2026-11-01，均 19:00；票价 380 / 580 / 780 / 980 / 1380 / 1680 元。当前是预约预选页，开售时间不能当作演出时间，显示票价不能证明有库存。最终停在助手票价网格，未选票价、未保存新目标、未最终预约、未启动监控、下单或付款；完整搜索到保存及购买流程未验收。

## v0.2.0：工作区重构与已验证范围

本轮回应用户对“仍延续旧版”的反馈，重建日常工作区。用户特别要求：在领航员里一眼看懂**现在到哪一步、下一步点什么、做完后去哪里**。

- 主导航改为“计划 / 领航员 / 我的”。计划页集中展示一场购票目标、当前进度和主要下一步；旧手动表单及兼容细项移入独立高级工具。
- 手机准备、购票条件和开始前核对使用独立整页。准备页按读取、通知、点击服务显示真实进度；开始前仍重新核对目标、配置版本、权限与模式。
- 从计划搜索框进入真实大麦选择：扁平演出列表带城市，日期与时间点选，票价网格可多选，最后用票根式核对页保存。修改场次重新读取，不能把旧缓存伪装成新结果。
- 领航员先显示当前步骤与明确的下一步按钮，再提供可选提问。步骤由本机准备状态与新鲜目录决定，模型回答不决定跳转权限；模型配置与笔记仍在次级入口。
- 目录等待交接按真实阶段提示一个物理动作，并说明通过“票务助手 · 演出选择”通知返回。普通监控仍使用本机规则；AI 仅在用户查看接收方与内容并主动发送后调用。
- 0.5–600 秒可配置等待、额度开放、加密密钥和订单防重记录保持；实际服务器刷新频率与自动锁票没有因 UI 改版而获得新验收。

**首个 v0.2.0 构建已完成：421 项 Android 单元测试通过，Lint 0 错误、35 条警告。**无线连接最初返回 `Connection refused`，随后已连回同一台 vivo，覆盖安装 v0.2.0-preview / code16 并读回手机 APK 哈希。该安装对应 `edd169a` 原构建；升级前后加密接口配置、用量与订单防重文件哈希一致。

真机已检查计划主页、手机准备、我的；领航员显示“当前位置：核对计划”、场次和下一步，点击下一步进入开始前核对页。用户手动开启票务助手无障碍服务，TalkBack 保持关闭，已用截图及系统值核实。UiAutomator 取树曾短暂影响无障碍连接，等待后截图恢复正常准备状态，不能把探测期间的瞬时状态写成持续故障。

已验证购票条件临时修改后返回取消不改变配置，以及搜索草稿进入领航员再返回仍保留。未点最终开始，未发送模型请求、提交订单或付款。按钮与权限说明修正版也已无线安装，手机 APK 与交付包校验一致，SHA-256 为 `2846ba9acee18a3b2f39e7fa3ff9cc2c245e8335bd6ac845e33c2e6ed637b007`。最终准备页已核实无障碍、通知、点击服务三项完成，仍为未启动状态；原配置、密钥、用量和订单记录保持。四步目录全链路、真实模型请求和运行流程仍未在此版完整验收，不代表物理拔线或锁票通过。新动线与清单见 `android-app/handoff/UX_FLOW.md`。

## 既有构建与安装记录

v0.1.14 / code15 已完成构建，393 项 Android 测试通过，Lint 0 错误、26 条警告；当轮无可用手机连接，未安装或验证完整交互重构，也未发送真实模型请求、启动监控或创建订单。

v0.1.12 交互重构已通过 354 项测试并无线安装；首页、折叠设置及模型额度入口已在 vivo 检查，原有加密配置、用量和订单记录哈希保持一致。v0.1.13 新增 0.5–600 秒小数间隔、新鲜确认动作跳过普通等待和订单结构绑定；381 项 Android 测试、19 项 doctor 测试通过，Lint 0 错误／20 条警告。用户模型配置不得用假数据覆盖或清除。

v0.1.13 无线覆盖安装成功，已读回 versionCode 14 / 0.1.13-preview；原有加密配置、用量和订单记录文件哈希保持一致。没有改变用户已保存的间隔、启动监控、发送模型请求或创建订单。随后手机进入系统设置，未继续切换页面；最后无线调试离线，安装后 APK 字节哈希和收尾状态未能再次读回。临时亮屏值与原值均为 600000 毫秒，本轮没有实际修改该值。详细验收及后续文案修订范围见 Android 验收记录。

## 已验证的基线

v0.1.10 曾在 vivo V2547A / Android16 / 大麦9.0.37 通过 USB 连接时的手机本地 PREPARE：自动选票到已加载的确认购买页后暂停。未最终提交或付款，不能称为锁票成功。具体历史证据见 `android-app/VALIDATION.md`。

2026-10-26 已通过正常无线调试配对并成功更新 v0.1.11-preview。它证明无线 ADB 更新可用，不证明脱离电脑的完整购票流程已验收。网络地址、调试授权和配对码不在仓库内。

## 尚未完成

1. 完成 v0.2.7 最终整包检查、同签名发包与实际下载核验；手机内部 code23 安装和真实诊断分享/电脑全文读回已通过，但不代表最终包、移动数据异网、应用内管理 Key 直连及页面噪声对照均已通过。高级私有接收器仍未部署，中转站不依赖它。临时设置的历史恢复与当次新增设置分别读回，不能沿用旧恢复记录。
2. 数量加号已有真实手工页面证据及受限 1→2 代码，助手自动双票链路仍待验证；不能将手工 2 张/1960 元确认页当作自动成功。生产订单页适配名单仍为空，自动提交和锁票未开放，实名/费用/协议/待付款仍需另行适配。本轮庾澄庆杭州仅测流程，观演人数 0，禁止创建订单或沿用旧温州授权。
3. v0.2.1 已验证两日期读取及首场到六档票价，第二场点击来自此前诊断包。完整搜索到保存、条件保存、大字体、其他启动交接、真实模型调用与取消仍待分别验收。检查等待不等于服务器库存刷新频率。
4. USB 物理拔线后的完整选票、重启后恢复、更多大麦版本与不同布局仍需分别验证。
5. AI 不会自主适配新布局或执行模型生成的点击。后续如加入建议动作，需绑定新鲜页面、版本、可验证目标和用户授权。

## 新电脑继续

```sh
git clone https://github.com/csjxing/TicketPilot.git
cd TicketPilot
```

给 Codex 的起始请求：

> 继续开发 TicketPilot。先读根目录 AGENTS.md、PROJECT_STATUS.md 和 android-app/AGENTS.md，检查 Git 状态及离线 doctor。按当前源码和最新验收记录推进，不沿用历史购票授权。先说明本次要完成的缺口，再修改、验证并更新交接进度。需要手机时重新核对连接状态；密钥在手机配置，不发到聊天。

项目通过 GitHub 同步代码、说明和进度，APK 默认由 Releases 分发，并按用户要求上传现有文件中转站。远见中转 App 分享的真实手机发送和电脑全文读回已在内部 code23 通过；应用内管理 Key 直连、移动数据异网及 v0.2.7 最终发包待验收。旧私有接收器的配对/自动同步代码仍保留，但服务未部署。完整聊天、私人购票配置、API Key、签名密钥和手机调试权限不经仓库或诊断同步。新电脑保留相同签名后才可覆盖更新现有安装，不通过卸载解决签名问题。
