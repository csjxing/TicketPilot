# TicketPilot · 票务助手

在 Android 手机上操作真实大麦 App 的本地票务助手。用户在手机上选择演出、场次和票档，助手读取可见页面、判断状态、执行已授权的选票操作，并记录结果。普通轮询使用本地规则、无障碍服务和内置 OCR，不调用云端模型；可选 Shizuku 作为本机输入方式。

当前为 **v0.1.10-preview**。工程重点是同一台 vivo 的日常使用，以及换一台电脑后继续开发。**自动提交订单、锁票和付款尚未开放；USB 物理拔线后的完整流程仍待验收。**

## 当前能力

| 功能 | 当前状态 |
| --- | --- |
| 从真实大麦搜索、读取演出、场次和票档，由用户选择 | 已在测试 vivo 和指定演出验证；其他页面需重新适配 |
| 本地读取、OCR、动态检查节奏和状态日志 | 已实现；正常轮询不需要模型 |
| `ALERT` 仅提醒 | 发现符合条件的页面信号后暂停 |
| `PREPARE` 准备购买 | 已在 USB 连接时自动选票并进入已加载的确认购买页，然后暂停 |
| 自动提交、待付款订单核验和锁票 | 未开放，生产订单页适配名单为空 |
| 实际拔掉 USB 后完整运行 | 待验收，不能用接线测试代替 |
| 内置知识库、源码导出、另一台电脑接手 | 已实现；源码包不含账号、签名密钥和设备授权 |

当前仅支持 **1 张**。有票信号、可选票档和确认购买页都不等于已经锁票。最近一次 PREPARE 验证使用 vivo X300 Ultra / Android 16、大麦 9.0.37；大麦升级、不同手机或演出页面需要重新验证。详细结果见 [Android 验收记录](android-app/VALIDATION.md)。

## 使用与开发入口

- [Android 使用说明](android-app/README.md)：设置权限、选择演出、开始与暂停。
- [另一台电脑接手](android-app/handoff/START_HERE.md)：环境检查、构建、签名和调试。
- [开发协作说明](android-app/AGENTS.md)：先读此文件，再修改 Android 工程或操作手机。
- [手机独立运行审计](android-app/handoff/STANDALONE_AUDIT.md)：本机运行边界和待完成测试。
- [内置知识库](android-app/app/src/main/assets/knowledge/)：随安装包提供的离线说明。

```sh
git clone https://github.com/csjxing/TicketPilot.git
cd TicketPilot/android-app
python3 handoff/doctor.py --offline
```

`doctor.py` 只检查环境，不下载、安装、点击或启动监控。构建需要 **JDK 17、Android SDK Platform 35**；设置 `ANDROID_HOME`，或在 `android-app/local.properties` 指定本机 `sdk.dir`。项目包含 Gradle Wrapper，首次构建需要下载依赖。

```sh
# 在 android-app 目录执行
sh ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s handoff -p "test_doctor.py"
```

Windows 使用 `gradlew.bat` 和 `py -3`。APK 输出为 `android-app/app/build/outputs/apk/debug/app-debug.apk`。安装与系统授权由用户正常完成；新电脑生成的调试签名可能不同，不能通过卸载或清数据来绕过覆盖安装冲突。

## 仓库结构

| 路径 | 用途 |
| --- | --- |
| `android-app/` | 当前 Android 应用、测试、离线知识和电脑交接工具 |
| `damai/`、`tests/` | Python 离线状态机、模拟订单防重与测试 |
| `scripts/` | 早期 macOS + ADB 现场脚本，保留用于研究和回归 |
| `docs/OFFLINE_ENGINE.md` | Python 离线引擎的完整说明 |

早期电脑脚本含历史演出日期、固定屏幕布局和本机工具路径，**不能直接当作通用抢票入口运行**，也不是 Android App 的运行依赖。它的真实提交与待付款流程尚未端到端验收，见 [历史脚本说明](scripts/PHONE_WATCH.md)。

离线演练不连接手机、不创建真实订单：

```sh
# 在仓库根目录执行
python3 -m damai --demo
python3 -m unittest discover -s tests
```

## 数据与提交边界

源码默认不包含实际演出配置、观演人资料、账号、设备序列号或登录凭据。真实配置、截图、设备日志、订单记录、构建缓存和导出包仅留在本地忽略路径；签名材料另行安全保存。App 自身不声明网络权限，官方大麦 App 仍需要联网。

验证码、登录、权限请求、未知页面及平台阻塞由用户处理。程序不提供验证绕过、私有库存接口或支付操作。恢复、导入和升级不能清除订单防重复记录，也不应自动沿用历史购买授权。
