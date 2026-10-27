# TicketPilot 私有诊断同步

这是手机与开发电脑之间的轻量诊断接口，**代码已实现、未部署**。不需要两端在同一 Wi-Fi。APK 可通过 TicketPilot 的 GitHub Releases 发布；诊断单独经本服务 HTTPS 上传并由授权电脑读取，不能公开写入 GitHub Issues、提交历史或 Release 附件。

该接口接收明确类型的版本、权限、运行状态及事件码。它没有远程 shell、ADB、触屏、文件读取、配置导入、观演人、订单或支付接口，不能替代这些动作的真机验证。手机后台是否及时运行仍受 vivo 电源策略和网络影响；一次 HTTP 接收成功也不代表抢票成功。

## 运行与部署

需要 Node.js 20.6+，无 npm 运行依赖。执行 `node --test cloud/test/*.test.mjs` 做本机协议、鉴权及持久化检查。

1. 在自有服务器部署 `cloud/`。以专用低权限用户运行，准备私有目录，例如 `/var/lib/ticketpilot-private-sync`，权限 0700。实际数据、授权凭据及配对结果不能放在 Git 仓库内。
2. 在仓库外建立 mode-0600 环境文件，参考 `.env.example`。`TICKETPILOT_OPERATOR_SECRET` 使用密码管理器或 Node `crypto.randomBytes(32).toString('base64url')` 生成的随机值。不要把真实值发进聊天或命令行参数，不要把它配置到手机。
3. 使用 `node --env-file=/etc/ticketpilot/private-sync.env /opt/ticketpilot/cloud/server.mjs` 启动。服务只监听 `127.0.0.1:8787`；不能开放该端口或绕过前置 HTTPS。systemd 示例见 `ticketpilot-private-sync.service.example`，其中路径与 Node 路径须按服务器调整。
4. 用已有域名配置 HTTPS 反向代理，见 `Caddyfile.example`。TLS 终止和服务必须在同一主机，代理覆盖 `X-Forwarded-Proto: https`。不要启用请求体、Authorization 或带密钥 URL 的日志；不要把任何密钥放 URL。
5. 检查 HTTPS `/health`，再按下一节配对、上传、从另一台电脑读取同一报告。只完成健康检查不能宣称手机上传链路通过。

服务要求 43–256 位 base64url 管理密钥；设备授权为独立的 32 字节随机 token，服务仅保存其 SHA-256。管理密钥能够签发配对码、读取报告和撤销设备；设备 token 只能给自己绑定的随机安装 ID 上传报告，不能读取其他手机或自己的云端历史。

配置文件损坏时不覆盖证据。写盘失败后本进程停止接受存储操作并返回通用错误，需排查磁盘后重启；不得用删库解决。可对私有目录做加密备份，备份须遵守相同访问与保留规则。

## 配对与电脑接手

手机只需输入服务器 HTTPS 根地址和一次性配对码，不输入 GitHub PAT 或管理员密钥。配对码十分钟有效、单次使用；每个设备 token 最长 90 天，可由管理端随时撤销。同一安装重新配对会撤销其旧 token。每分钟最多 12 次配对尝试、每设备最多 30 次上传，超过返回 429。

电脑工具 `operator.mjs` 只从私有环境读取 `TICKETPILOT_SYNC_URL` 和 `TICKETPILOT_OPERATOR_SECRET`。服务器 URL 必须是 HTTPS 地址，仅用默认 443 端口。可以配置 `/ticketpilot` 之类的固定前缀，但反向代理必须先去掉此前缀，再转给服务端的 `/v1/…` 路由；手机和电脑填写同一地址。结果直接写到指定绝对路径的新文件（0600），终端不会打印 token、配对码或报告。

```sh
node --env-file=/private/ticketpilot-owner.env cloud/operator.mjs pair --out /private/pairing.private.json
node --env-file=/private/ticketpilot-owner.env cloud/operator.mjs devices --out /private/devices.private.json
node --env-file=/private/ticketpilot-owner.env cloud/operator.mjs reports 0 --out /private/reports.private.json
node --env-file=/private/ticketpilot-owner.env cloud/operator.mjs reports 0 --follow --out /private/reports.private.ndjson
```

打开私有配对结果，在手机填写 `pairingCode`。下次读取以报告结果中的 `nextCursor` 替换 `0`，使用新输出文件路径。另一台电脑拿到同一私有管理员环境文件后即可读取同一服务的诊断；密钥需用户通过自己的安全方式传递，代码仓库不会同步它。

`reports --follow` 每十秒读取新报告，临时网络或服务器故障退避到最长六十秒；授权失效立即退出。它将每批结果以一行 JSON 追加到私有 NDJSON 文件，成功落盘后才推进游标，空结果不持续重复写入。按 Ctrl-C 停止。重开时使用最后一条完整 JSON 的 `nextCursor` 和新输出文件；不自动覆盖旧记录，也不自动向手机发送操作。

撤销时从私有设备列表读取 `deviceId`，运行 `operator.mjs revoke <device-id> --out /private/revoked.private.json`。撤销设备不会清除历史诊断或手机上的订单防重记录。

## HTTP 契约 v1

所有非健康接口返回 `schema: 1`。JSON 请求最大 32KiB，未知字段、任意页面正文、非枚举字符串一律拒绝，报错不回显提交内容。所有鉴权使用 `Authorization: Bearer <token>`，无跨源浏览器访问或重定向授权。

| 请求 | 授权 | 输入 / 返回 |
| --- | --- | --- |
| `GET /health` | 无 | 服务与 schema 的通用健康状态 |
| `POST /v1/pairing` | 管理员 | `{}`；返回 `pairingCode`、`expiresAt` |
| `POST /v1/pair` | 一次性码 | `{pairingCode, installId}`；返回 `deviceId`、`deviceToken`、`expiresAt` |
| `POST /v1/diagnostics` | 设备 | 下方报告；返回 `accepted`、`duplicate`、`cursor`、`reportId` |
| `GET /v1/diagnostics?after=0&limit=100` | 管理员 | 返回 `reports`、`nextCursor`、`oldestAvailableCursor`、`gap`；limit 1–200 |
| `GET /v1/devices` | 管理员 | 不含 token/hash 的设备状态列表 |
| `DELETE /v1/device` | 管理员 | `{deviceId}`；返回 `revoked: true` |

`installId`、`reportId` 和 `deviceId` 是随机 UUIDv4，不是 Android ID、序列号、账号或手机号。设备 ID 仅用于私有诊断关联，不上传到公开 Git。

报告字段必须全部存在；大麦版本无法读取时 `damaiVersion: null`。

```json
{
  "schema": 1,
  "reportId": "00000000-0000-4000-8000-000000000001",
  "installId": "00000000-0000-4000-8000-000000000002",
  "sequence": 1,
  "createdAt": "2026-10-27T01:00:00.000Z",
  "appVersion": { "name": "0.2.4-preview", "code": 20 },
  "damaiVersion": { "name": "9.0.37", "code": 9000037 },
  "androidApi": 36,
  "readiness": { "a11y": "READY", "notifications": true, "shizuku": true },
  "runtime": { "armed": false, "mode": "PREPARE", "state": "OUT_OF_STOCK" },
  "events": [{ "at": 1793062800000, "tag": "USER", "code": "MANUAL_REPORT" }]
}
```

- `sequence` 为该安装单调递增的正安全整数，最大 `9007199254740991`。同一序号、相同报告重传返回原 cursor 和 `duplicate:true`；相同序号改内容、旧序号或重复 reportId 返回 409。手机按队列顺序上传、同一时刻仅一个请求，收到确认后再发下一份。
- `createdAt` 为 UTC ISO 时间，允许离线积压七天，不能超过服务器未来五分钟；超出以 HTTP 422 返回 `clock_out_of_range`，便于手机提示校正时间或处理过期的本地队列；服务不会把错误当作已接收。事件 `at` 为毫秒时间戳；最多 64 条，每个事件只能有 `at/tag/code`。
- `appVersion`/`damaiVersion` 的 name 只能为 1–64 位版本字符：数字开头，其余字母、数字、`.`、`_`、`+`、`-`；code 为 0–2147483647。Android API 30–99。
- readiness.a11y：`OFF / ENABLED_DISCONNECTED / READY / UNKNOWN`。
- runtime.mode：`OFF / ALERT / PREPARE / LOCK_ORDER`。state：`IDLE / STOPPED / CONFIG_INVALID / EXPIRED / BLOCKED / UNRELATED / UNKNOWN / READY / PROBING / OUT_OF_STOCK / IN_STOCK / TIER_SELECTED / CHECKOUT / PENDING_PAYMENT_VERIFIED / NEEDS_REVIEW`。
- events.tag：`SERVICE / MONITOR / CATALOG / INPUT / CHECKOUT / UPDATE / SYNC / USER`。
- events.code：`MANUAL_REPORT / SESSION_STARTED / SESSION_STOPPED / CONNECTED / DISCONNECTED / PERMISSION_REQUIRED / FOREGROUND_CHANGED / RESERVATION_ONLY / IN_STOCK / OUT_OF_STOCK / TIER_SELECTED / CHECKOUT_LOADED / ORDER_PENDING_VERIFIED / ORDER_OUTCOME_UNKNOWN / READ_FAILED / INPUT_FAILED / TIMEOUT / CANCELLED / UPDATE_AVAILABLE / UPDATE_INSTALLED / CONFIG_INVALID / EXPIRED / BLOCKED / UNKNOWN`。

报告只保留最近七天、最多 2000 份，在下一次存储操作时清理到期数据。`gap:true` 表示请求游标早于仍保留的历史，不能把缺失报告当作手机没运行。服务不会长期保留全量轮询日志，也不会把未知状态推断为有票或锁单。

## 本机验证范围

23 项 Node 测试覆盖鉴权角色隔离、一次性配对及到期、撤销与重新配对、安装绑定、报告重传与冲突、并发游标、原子落盘与权限、七天保留、嵌套隐私白名单、时钟和体积限制、速率限制、HTTPS 代理要求、损坏存储与写入失败保护，以及电脑持续读取的游标持久化、故障退避、授权失效停止和不覆盖私有输出。测试使用临时随机目录与合成报告，没有连接手机、GitHub 或生产服务。真实部署、手机公网配对与上传、跨电脑读取仍须由主联调者验收。
