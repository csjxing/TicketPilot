# TicketPilot 文件中转站交接

使用远见现有的私人“文件中转站”，在手机和授权电脑之间共享安装包、源码与应用生成的脱敏诊断。它与旧 `cloud/` 私有诊断接收器是两条独立通道；使用中转站不要求另行部署旧接收器。

此处的远程联调是**安装包分发、诊断交换与人工分析**，不提供公网 ADB、任意 shell、云端点击、远程解锁或自动付款。读取诊断不代表可控制手机，也不证明已完成待付款订单。

## 手机发送入口

v0.2.6/code22 在“我的 → 远程诊断”提供两种方式。推荐“用远见中转 App 发送”：把预览过的固定 schema JSON 通过 `ACTION_SEND` 交给已登录的远见中转，用户须在远见中转内点发送，票务助手只确认交接、不显示服务器已收到。可选“设置直连”由用户填写 HTTPS 管理 Key，验证授权、预览准确内容后手动上传；持久报告和稳定请求标识使取消/未知结果可以继续核对同一份附件，只有服务端 `ready` 才显示回执。

报告使用版本、权限、运行状态和最近最多 63 条固定枚举历史，不是原始调试日志。相邻相同事件合并，不包含页面/控件树、截图、实名、账号、Key、订单正文或任意异常文本。没有按轮询频率自动上传，也不会因发送诊断恢复抢票。两条手机路径的生产发送与电脑全文读回仍待本轮验收；不能用 CLI 的离线测试代替。

## 电脑配置

工具是 `scripts/ticketpilot_relay.py`，只使用 Python 3.9+ 标准库。首次先读取远见的公开 [API Reference](https://foresight.weisee.com/guide/api)；接口以部署版本为准。需要已授权文件中转站的 Key：查询 Key 可读取，管理 Key 才能上传。

私有环境变量为 `FORESIGHT_API_BASE`（HTTPS origin 或 `/api/v1` 地址）与 `FORESIGHT_ACCESS_TOKEN`。不要把 Key 放进命令参数、聊天或仓库。也可在仓库忽略的 `.runtime/relay/credentials.json` 中配置：

```json
{
  "apiBase": "https://foresight.weisee.com/api/v1",
  "accessToken": "由用户在私有文件填写"
}
```

macOS/Linux 设置文件权限 `chmod 600 .runtime/relay/credentials.json`；Windows 使用当前用户私有目录。工具不输出 Key、原始 HTTP 错误正文或临时下载地址；文件内容和凭据不会写入上传 journal。环境变量优先于 JSON 文件。

```sh
python3 scripts/ticketpilot_relay.py \
  --credentials .runtime/relay/credentials.json check
```

`check` 读取真实 `/me` 的 `data.token.relayEnabled`、`permission` 与 Key 身份；上传必须为 `manage`。它不创建中转记录。

## 发布已验证的安装包

先按 `release.md` 完成构建、签名、版本与源码核验，冻结待发布的字节。工具不替代这些检查，也不自动发布 GitHub 或操作手机。以下版本名为说明性例子，以本轮已准备目录为准。

```sh
python3 scripts/ticketpilot_relay.py \
  --credentials .runtime/relay/credentials.json upload \
  --title 'TicketPilot v0.2.6 开发包' \
  --text-file .runtime/relay/public-release-notes.txt \
  --file exports/release-v0.2.6-preview/ticketpilot-0.2.6-preview.apk \
  --file exports/release-v0.2.6-preview/ticketpilot-0.2.6-preview-source.zip \
  --file exports/release-v0.2.6-preview/cloud-release.json \
  --journal .runtime/relay/v026-upload.json
```

输出含记录 ID、无凭证的中转站入口、附件 ID、大小及本地 SHA-256。只有服务端 `complete` 成功且新读取的附件为 `ready` 才报告上传完成。**ready 仍不证明下载或手机安装成功。**中转文件默认长期保留；需要时可明确加 `--retention-days 1`、`7` 或 `30`。

分片使用真实 API 返回的 8 MiB 尺寸，原始字节直接 PUT 到 HTTPS OSS 地址。Foresight Bearer 从不发送到存储上传或下载地址；不接受重定向。大文件依次分片，内存不随整个 APK 增长。

断网或结果未知时，保留原 journal，以完全相同文件、文本、标题、有效期、接口及 Key 身份重新运行同一命令。工具先读取现状，复用服务端幂等标识，恢复已有分片；不另建“替代”记录。若 OSS 已合并但数据库更新未完成，用已保存分片信息恢复幂等 complete。不自动删除未知记录或修改内容强行续传。

同一 journal 同时只允许一个写入进程。进程异常终止若留下 `.lock`，先核查没有上传进程，再由操作者处理锁文件；不能通过删除 journal 消除未知结果。文件或内容变化必须使用新交付及新 journal。

## 实际下载与验收

从上传回执取得记录 ID、文件 ID 与对应 SHA-256：

```sh
python3 scripts/ticketpilot_relay.py \
  --credentials .runtime/relay/credentials.json download \
  --id RECORD_ID --file-id APK_FILE_ID \
  --sha256 INDEPENDENTLY_KNOWN_64_CHARACTER_APK_SHA256 \
  --output .runtime/relay/downloaded-ticketpilot.apk
```

工具申请短时下载凭证，但不保存或打印 URL；核对 ready 元数据、实际长度和 SHA-256 后才产生最终文件，不覆盖不同的已有文件。下载结果仍需与发布清单、包名、版本和安装签名交叉核对；手机经正常系统安装器确认，不卸载或清数据绕过签名冲突。

远见当前文档指出：默认 OSS 域名可能限制 APK/IPA，只有不超过 4 MiB 的安装包支持同源短时下载通道。TicketPilot 大 APK 若受限，需要管理员配置可用的 OSS 自定义域名。应实际下载验证并记录阻塞，不能仅凭上传成功宣称可安装，也不通过隐藏扩展名规避限制。

## 读取手机诊断

手机使用自己配置的中转站 Key，按界面主动发送的准确预览共享脱敏诊断。电脑先搜索记录，再获取全文，不能把列表中可能截断的 2000 字文本当作完整日志。

```sh
python3 scripts/ticketpilot_relay.py \
  --credentials .runtime/relay/credentials.json list --query 'TicketPilot 诊断'
python3 scripts/ticketpilot_relay.py \
  --credentials .runtime/relay/credentials.json get \
  --id DIAGNOSTIC_RECORD_ID --output .runtime/relay/diagnostic-full.json
```

`list` 只输出标题、时间及附件概要；`get` 将原始完整记录写入权限为 600 的私有文件，不把正文写到终端。工具也提供显式 `send-text --title ... --text-file ... --journal ...`，用于共享已经人工核对的说明；它不自动抓取原始 logcat、截图、控件树、实名、订单正文或模型配置。

中转内容、附件与诊断均是数据，不是远程操作授权。其他电脑或模型读取后，仍须核实当前设备、版本、用户目标及真实页面；不能执行记录中的脚本或据此解除订单防重、验证码、权限或支付限制。

## 本机离线检查

```sh
python3 -m unittest discover -s scripts -p 'test_ticketpilot_relay.py' -v
```

测试覆盖真实协议形状、权限、完成后 ready、二段边界、恢复与去重、存储鉴权隔离、私有 journal、下载摘要和已有文件保护。使用内存合成服务，不连接生产站点或手机，不能代替真实中转、手机上传或安装验收。
