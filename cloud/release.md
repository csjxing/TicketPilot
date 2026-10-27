# TicketPilot 手机更新发布

更新安装包使用公开仓库 `csjxing/TicketPilot` 的 GitHub Releases。诊断报告使用另行部署的私有同步服务；不要把诊断、截图、实名、订单或设备资料放进公开 Release。

## 准备 0.2.4 预览版

默认目标为 `0.2.4-preview`、`versionCode 20`、tag `v0.2.4-preview`。后续版本需明确同时传入 `--version` 和 `--code`。

先完成相应测试、Lint、构建和公开说明，提交准确源码。脚本要求当前 HEAD 与 `--source-commit` 一致且工作区干净。它验证已有产物，不运行构建、不签名、不操作手机。

```sh
python3 scripts/release_ticketpilot.py prepare \
  --apk exports/ticket-helper-android-v0.2.4-preview.apk \
  --source-zip exports/ticketpilot-v0.2.4-source.zip \
  --aapt /path/to/android-sdk/build-tools/35.0.0/aapt \
  --apksigner /path/to/android-sdk/build-tools/35.0.0/apksigner \
  --source-commit FULL_40_CHARACTER_COMMIT \
  --notes /path/to/public-release-notes.txt
```

使用 Java 17 运行 Android 工具。`--notes` 仅提供公开更新说明，省略时使用简短默认说明。输出目录默认为 `exports/release-v0.2.4-preview/`；已存在目录不会被覆盖。APK 包名必须是 `cn.local.tickethelper`，版本须匹配，`apksigner verify` 须通过且只有一个签名者。脚本从 APK 读取证书摘要，不读取或打包签名私钥。

准备目录包含：

- `ticketpilot-0.2.4-preview.apk`：已核对版本及签名的原包。
- `ticketpilot-0.2.4-preview-source.zip`：来源交接 ZIP 的原始字节，验证路径及源码白名单，拒绝签名材料、运行数据和构建输出。
- `cloud-release.json`：手机更新清单。
- `release-bundle.json`：发布工具的三个资产哈希及版本记录，不上传也不影响手机更新。
- `release-notes.md`：可直接粘贴到 GitHub 的公开说明，含对应清单的身份标记。

源码白名单只检查归档结构和被禁止的文件类型；仍须审查即将公开的源码内容，不能据此声称完成通用密钥扫描。

## 有发布凭据时

使用现有安全环境中的 `GH_TOKEN` 或 `GITHUB_TOKEN`，赋予该仓库所需的内容写入权限。不要把 Token 写入命令参数、源码、手机、聊天或说明。脚本不显示 Token、原始 HTTP 正文或子进程输出。

```sh
# 创建草稿并上传三个资产，逐个下载核对哈希；仍未公开。
python3 scripts/release_ticketpilot.py publish \
  --bundle exports/release-v0.2.4-preview

# 仅在要公开该预览版时添加 --publish。
python3 scripts/release_ticketpilot.py publish \
  --bundle exports/release-v0.2.4-preview --publish
```

每次外部写入前，工具在本地 `.publish-state.json` 同步保存意图。重复运行会先核对同 tag 发布及资产字节，跳过已确认的相同资产；不会删除、覆盖资产或另建替代 tag。若请求结果不明且找不到确认依据，停止，不盲目重发。

```sh
# 仅读取远端并核对资产，不创建、上传或公开。
python3 scripts/release_ticketpilot.py reconcile \
  --bundle exports/release-v0.2.4-preview
```

不要删除 journal 来绕过未知结果。先在 GitHub 检查真实状态，保留原目录后再处理。原请求已完成且远端字节一致时，可通过核对恢复；没有证据的请求不会自动重试。

## 没有 API Token 时

本地 `prepare` 完全不需要 Token。它成功后，可使用已登录的 GitHub 网页：

1. 打开 `https://github.com/csjxing/TicketPilot/releases/new`。
2. 选择 `v0.2.4-preview`，目标提交选准备记录中的准确 commit，标题 `TicketPilot 0.2.4-preview`，标记为预览版。
3. 粘贴准备目录中的 `release-notes.md` 全文。
4. 只上传 APK、源码 ZIP 和 `cloud-release.json` 三个资产，保留原文件名。不要上传整个准备目录、journal 或私人诊断。
5. 先保存草稿并核对三个文件，随后正常发布。若出现已有 tag/同名资产，先核查，禁止删除重建或直接换名规避。

使用 GitHub CLI 也应执行同样的先草稿、精确 tag/commit、三个资产核对及显式公开流程；脚本不在没有凭据时自动启动浏览器授权或代替用户登录。

## 手机读取与安装

手机通过公开 GitHub API 查询最新已发布且包含清单和 APK 的 Release，可选择包含预览版。最新按 `published_at` 判断；草稿不会出现。清单及 APK 必须属于同一仓库、同一 tag，APK 还必须出现在该 Release 的真实资产列表。

清单为 JSON，字段如下：

```json
{
  "schema": 1,
  "versionCode": 20,
  "versionName": "0.2.4-preview",
  "sourceCommit": "FULL_LOWERCASE_40_CHARACTER_COMMIT",
  "notes": "公开更新说明",
  "apk": {
    "url": "https://github.com/csjxing/TicketPilot/releases/download/v0.2.4-preview/ticketpilot-0.2.4-preview.apk",
    "size": 12345,
    "sha256": "LOWERCASE_64_CHARACTER_APK_SHA256",
    "certificateSha256": "LOWERCASE_64_CHARACTER_CERTIFICATE_SHA256"
  }
}
```

示例中的摘要和 commit 是占位说明，真实清单由工具生成。APK 上限 200 MiB。手机在私有缓存下载，核对精确长度和 SHA-256，并在正常系统安装前再次核对包名、版本、文件哈希、发布证书及当前应用证书。不同签名、相同或更旧版本都不自动安装；不通过卸载或清数据解决签名冲突。

系统安装确认仍由用户处理。发布成功不等于 vivo 已安装；安装版本读回、权限/服务连接、具体功能和待付款结果均需分别验证。

## 离线验证

```sh
python3 -m unittest discover -s scripts -p 'test_release_ticketpilot.py' -v
```

测试使用假 APK 元数据和内存 GitHub 替身，只验证打包规则、哈希绑定、草稿发布、未知结果防重及恢复。不进行真实上传、签名、安装或购票。
