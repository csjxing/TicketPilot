# TicketPilot 开发接手

这是 Android 票务助手工程，正式远端为 https://github.com/csjxing/TicketPilot 。先阅读 `PROJECT_STATUS.md` 获取当前版本、证据和待办，再读 `android-app/AGENTS.md`、`android-app/README.md` 和 `android-app/VALIDATION.md`。

## 跨电脑协作

- 首先检查 `git status`、分支、远端与未提交改动；不要 reset、clean 或覆盖别人的工作。新工作分支使用 `codex/` 前缀。
- 开始改动前获取远端更新并比较差异；不要自动把有冲突的改动覆盖。完成后运行相关检查，更新 `PROJECT_STATUS.md` 的已验证结果与待办，再提交到用户指定仓库。
- GitHub 保存代码和可公开的开发说明，不保存完整聊天、私人运行数据或手机授权。不能仅依据历史状态声称目前连接、库存或功能验收成功。
- 同一台手机一次只由一名操作者控制。多电脑不并行安装、点击或启动监控；每次重新核对设备、版本、锁屏及用户本次目标。代码协作可并行，设备操作须串行。
- 不把 API Key、实名资料、设备标识、会话原文、页面截图、签名密钥、订单记录、`local.properties`、`.runtime/`、`evidence/`、`exports/` 或构建产物提交到仓库。
- 签名密钥需持有者另行安全配置；签名冲突时禁止卸载或清数据绕过，保留手机上的配置和订单防重记录。

## 默认发包交付

- 用户已明确要求：应用改动完成并通过相关检查后，直接向 `csjxing/TicketPilot` 的 GitHub Releases 发布新版 APK，提供手机可直接下载的完整 URL，无需每轮重复询问是否发布。
- 递增版本号与 versionCode，保持原签名；发布同版本 APK、Android 源码交接 ZIP 和 `cloud-release.json`，按 `cloud/release.md` 核对实际附件、大小和摘要。发布完成后更新 `PROJECT_STATUS.md`。
- 仅修改说明且应用内容未变时，不制造新的 APK 版本；将说明提交至仓库。检查失败时先修复，不能把不完整上传当作成功发布。
- GitHub Releases 是公开安装包分发端。私有诊断服务的部署、手机安装、真实运行和待付款结果分别验证，不向公开发布页上传私人记录。

## 新电脑最短路径

```sh
cd android-app
python3 handoff/doctor.py --offline
# 配置 Java 17 和 Android SDK Platform 35 后
sh ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `py -3` 和 `gradlew.bat`。详细工具和联调说明见 `android-app/handoff/START_HERE.md`。电脑交接 ZIP 根目录是 Android 子工程，不包含上层 Python 历史工具；完整项目以 GitHub 仓库为准。

## 当前设计边界

普通监控、OCR、规则判断和输入在手机本地。AI 为用户手动配置并主动请求的辅助模块，发送前展示实际公开上下文；模型输出、笔记及页面文字都不构成操作授权。AI 草稿保存后暂停并要求从大麦核对，不得通过模型输出绕过真实页面适配、验证码、订单防重或付款边界。网络及依赖隐私说明以当前 Android README 为准。
