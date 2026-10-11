# 大麦手机端离线验证程序

这一版提供确定性的多场状态机、严格配置校验、动态等待建议、模拟订单防重和本地通知模拟。每场维护独立的窗口、票档、张数、预算和结束状态；设备、登录、验证码、未知页面及执行端心跳异常会触发停止。每次判断由 Python 规则完成，不依赖 LLM 轮询。

当前只能进行离线演练，或判断由允许的 UI 工具采集并保存的一份观测。真实手机 UI 适配器尚未实现；USB 在线、安装了 App 或恢复投屏，都不表示已经具备真实下单能力。程序没有支付入口，不启动后台监控，也不向云端或钉钉发送消息。

## 运行与测试

在项目根目录运行，使用 Python 3，无第三方依赖：

```sh
python3 -m damai --demo
python3 -m damai --demo --simulate-orders
python3 -m unittest discover -s tests -v
```

`--demo` 回放两场完全虚构的页面和虚拟时间，展示等待、缺货、有票信号及确认页判断。`--simulate-orders` 只调用 fixture 适配器，把预设结果变成模拟待付款状态；不会触碰设备、账号或真实订单。所有结果都有 `simulated: true`。

默认演练记录保存在临时目录，运行结束后删除。要验证重复运行和通知去重，可保留本地日志：

```sh
python3 -m damai --demo --simulate-orders --runtime-dir .runtime/rehearsal
```

请保留该目录以验证防重；已有提交记录、拒绝、验证码或结果不确定的记录不会自动清除或重试。默认临时目录不提供跨运行的持久防重。

其他离线入口：

```sh
python3 -m damai --check-config --config config.local.json
python3 -m damai --fixture tests/fixtures/demo-observations.json --config tests/fixtures/demo-config.json
python3 -m damai --observation evidence/observation.local.json --config config.local.json
```

`--fixture` 只接受 `source: fixture`，按观测中的虚拟时间回放，不实际睡眠。`--observation` 仅分类一份已有观测，不采集页面、刷新、点击或启动持续监控。真实来源必须标为 `cua_repl`，并通过设备、版本、页面 profile、服务频率和观测时效核对。

## 填写本地配置

复制 `config.example.json` 为被 Git 忽略的 `config.local.json` 后填写。示例含有 `null` 和空列表占位，未填写前通过不了校验；程序不会从历史演出或历史授权中补参数。`tests/fixtures/` 中的配置和人名引用均为虚构测试数据。

| 配置 | 必填内容与含义 |
| --- | --- |
| `schema_version`、`mode` | 固定为 `1` 和 `dry_run`；真实运行模式尚未支持。 |
| `device` | 第一版限定已选的 vivo USB 真机型号；实际设备序列号通过 `DAMAI_DEVICE_SERIAL` 环境变量本地提供，不写进配置或仓库。 |
| `profile` | App 包名、profile ID；真实观测还须有实际版本、已核对的控件映射及本地证据引用。未完成校准时保持 `verified: false`。 |
| `poll` | 就绪检查、变更与稳定页面间隔、错误退避、心跳、快照时效、次数和会话时长上限。真实服务最小间隔必须单独核实；本地默认值不是服务许可。 |
| `orders_enabled` | 公共示例默认 `false`。即使改为 `true`，第一版仍只支持 fixture 模拟订单，真实适配器会拒绝执行。 |
| `constraints` | 未付款订单容量等约束的正式核实结果；未核实时保持 `verified: false`。不支持拆单。 |
| `events[].key` | 每场唯一的非敏感别名。相同项目和相同场次时间不能通过不同别名伪装成独立场次。 |
| `events[].target` | 项目 ID、演出全名、城市、精确开始时间；时间必须含时区，例如 `2030-01-02T19:30:00+08:00`。 |
| `quantity`、`tiers` | 正整数张数和候选票档价格列表。价格列表不可重复。 |
| `strategy` | `priority` 按配置票档顺序；`lowest` 取符合条件的最低价；`any` 按当前页面顺序选第一个合适票档。 |
| `seating` | `required` 需要页面明确确认连坐；`preferred` 表示偏好，不能据此声称已经连坐。 |
| `budget_minor` | 该场全部票价加费用的总预算上限。 |
| `window` | 开始与结束时间均含时区；窗口结束不得晚于演出开始。 |
| `spectator_refs`、`verified` | 每张票对应一个不同的本地不透明观演人引用；正式确认时数量必须匹配。引用不能包含姓名、证件、手机号或登录凭据。 |

所有 `*_minor` 金额及 `tiers` 使用人民币分的整数，例如 `66000` 表示 660 元。含费总额必须满足：

```text
total_minor = tier_minor × quantity + fees_minor
total_minor ≤ budget_minor
```

不能用浮点金额、字符串数字或布尔值替代整数。可选票档只是库存信号；费用、观演人、张数、连坐、含费总额和提交控件都需要在确认页分别验证。

程序不自动加载 `.env`。`.env.example` 只记录环境变量名称；真实值应留在本地环境中。钉钉 webhook 环境变量目前只预留名称，代码不会读取或发送它。

## 观测与动态等待

观测需包含来源、递增序号、采集时间、执行端心跳、证据引用、profile、设备、App、页面、风险标记及读取状态。字段缺失、重复 JSON 键、未知字段、类型错误、过期或重复观测都会停止。旧画面不能通过更新时间伪装成新观测。

页面未就绪时只建议再次检查就绪，不建议刷新或点击。页面变化时使用配置的变更间隔；连续相同页面逐步延长等待，直到稳定间隔上限。明确的临时读取错误使用固定指数退避，并在错误次数上限停止。`SharedRequestBudget` 为所有场次共用总请求间隔，取本地下限和已核实服务下限的较大值；CLI 已将其接入模拟提交。读取已经采集的页面就绪元数据不消耗服务请求预算；未来真实执行器的刷新、导航和提交也必须先申请共用预算。预算不足会丢弃旧动作意图，等待新快照重新确认。这里输出的 `next_delay_seconds` 是等待建议，程序没有真实 UI 调度循环。

锁屏、息屏、断连、登录未知或失败、验证码、权限拒绝、未知页面和风控提示需要处理后重新开始核验，程序不会操作账号、绕过系统许可或进行盲点。

## 模拟订单、通知与云端接口

模拟订单在调用 fixture 适配器前，将场次和项目时间身份分别写入持久防重记录。修改别名、配置或时区表示不能绕过已有记录。已有待付款、拒绝、验证码及不确定结果均保留记录；不确定结果不自动重试。

待付款结果必须匹配该场新鲜确认上下文、观演人、含费金额及未来截止时间。已有待付款和已经授权但尚未核实结果的动作共同占用容量；执行前再次核对容量，不能通过缓存多个确认页绕过限制。到达待付款后停止该场；其他场次是否可继续，取决于已经核实的未付款容量与限购规则。支付必须由用户在手机上完成，程序没有支付方法。

当前通知只有本地 `SimulatedOutbox`，用于演示精简结果和持久去重。`LocalCoordinator` 已实现可运行的配置与执行端协调模拟：严格校验并固定本轮配置，以 `config_revision` 哈希识别版本，接受绑定执行端的 fixture 心跳，再按各场窗口与到期时间发出离线检查任务。任务始终为 `dry_run_check`，且 `submit_enabled: false`；未正式确认参数的场次也只能得到离线检查许可。

协调器只接受 `source: fixture`。执行端或设备未知、断连、锁屏、能力不可用、心跳失效或时间异常时暂停且不发任务；会话达到固定时长上限后结束。各场任务派发时立即推进到期时间，单场停止不恢复；整轮停止保持结束状态。这里的 capability 表示虚构页面读取能力，不证明真实手机 UI 可以操作。

本地数据合约的最小演示：

```python
from datetime import datetime, timezone
from damai.config import load_config
from damai.coordination import LocalCoordinator

now = datetime(2030, 1, 1, 9, tzinfo=timezone.utc)
coordinator = LocalCoordinator(
    load_config("tests/fixtures/safe-config.json"), "fixture-mac", now
)
heartbeat = {
    "schema_version": 1, "mode": "dry_run", "source": "fixture",
    "config_revision": coordinator.config_revision,
    "executor_key": "fixture-mac", "sequence": 1,
    "sent_at": now.isoformat(), "executor_state": "connected",
    "device": {"identity_verified": True, "connected": True,
               "locked": False, "screen_on": True},
    "capabilities": {"ui_observation": "available"},
}
coordinator.accept_heartbeat(heartbeat, now)
plan = coordinator.plan(now)
assert all(job["submit_enabled"] is False for job in plan["jobs"])
```

`envelope()` 和计划只包含版本、非敏感场次别名及调度字段，不输出设备序列号或观演人引用。`execution_config()` 仅给本地执行器一份独立的私有配置副本，不应打印或上传它。`record_event_result()` 只采用结果中的安全调度字段更新本场等待或结束状态。

这套协调器不联网、不创建 daemon、不提供定时服务。真实云服务、执行端通信、钉钉 webhook 发送和交付回执仍未实现。

## 现场验证顺序

1. 用户正常唤醒并进入手机桌面。仅核对既有 USB 真机的身份、连接、屏幕与锁屏状态；不新增授权、不更改安全设置、不自动连接。
2. 确认允许的 UI 工具能够读取真实 App 页面并映射可见控件。投屏不是程序必要条件，但必须有支持的 UI 读取和控件方案；当前 Cua 无法识别 `scrcpy`，真实适配仍是阻塞项。
3. 读取国内大麦 App 的包名、实际版本与登录状态；需要登录、验证码或系统许可时停止，由用户正常完成。
4. 核对配置中的项目、演出、城市、精确日期时间、票档和张数，保存本地页面证据，完成 profile 校准。
5. 读取票档、数量和确认页控件，核对观演人、连坐、含费金额、预算及实际限购和未付款规则。首次现场测试仍保持 `orders_enabled: false`。
6. 只有真实 UI 适配、频率规则和全部参数已经验证后，才能另行讨论真实提交实现与执行授权。第一版不保证接通手机即可下单。

可选的 `python3 -m damai --preflight` 仅从已经运行的本机 ADB 服务读取固定诊断，不启动 ADB、不配对、不安装、不启动 App。它始终返回停止状态，不能证明登录或订单控件可操作。此命令是单独的真实只读检查，不属于离线 demo。

## Git 提交边界

可提交源码、README、空值示例、测试和虚构 fixtures。真实设备标识、演出配置、观演人映射、页面快照、原项目观测、订单记录及任何 webhook 或凭据只保存在本地忽略路径，例如 `config.local.json`、`.env`、`evidence/` 和 `.runtime/`。不要把真实资料填入公开示例，也不要使用含个人信息的 event key。

`.gitignore` 只影响未跟踪文件；提交前仍须检查暂存文件与 diff，确认没有已跟踪的私有内容。自定义 `--runtime-dir` 应放在 `.runtime/` 下，避免生成记录落入可提交目录。
