---
status: verified-on-emulator
scope: unpublished-personal-apk
---

# 自用安装与验收记录

本版本使用完整官方 DeepSeek Harness 0.2.1-rc.2，Claude Style 0.18.2、作者 GitHub 原版 Our Free Model 2.5.9，以及独立平板适配插件 0.1.2。上游包原文件不变；版本、归档 SHA256 和作者提交见 [运行时锁](../../../harness/upstream-lock.json)。

## 功能范围

Harness 自身提供会话、工作区、模型提供商、上下文、工具编排、MCP、Skills、插件及自动化任务。启动加载完整 web/base profile，内核与用户数据分开，后续可按 [运行时更新流程](02-runtime.md) 同步上游；本版没有实现后台自动更新 APK。

Android 复用 Operit 的文件、设备操作、截图、终端、内存库、浏览器、应用列表和通知等工具。实际注册工具与静态允许清单取交集，通过两个 MCP 工具导出操作目录和执行入口。原生操作需要现有权限系统批准；辅助功能、Shizuku、Root、通知及存储能力还受设备授权限制。全部允许工具并不等于全部权限等级已在硬件上验证。

旧 Operit 工作流仅供查看，执行及定时调度由 Harness 承担。旧工作流执行器可调用旧 Agent 和模型，不作为新版执行入口，因此旧工作流不能原样运行。语音与离线模型入口已移除，外部分享和旧 Agent 深链入口关闭；附件通过 Harness 的系统文件选择器导入。

免费模型插件按 managed 方式安装，升级归宿主安装器管理。插件保留上游的账号、渠道、额度和宿主限制；EAC 桌面限定不能通过换皮解除。无可用账号/API Key 时，不能把页面加载成功当作模型对话成功。

## 安装方式

包名 `com.ai.assistance.operit.clone`，应用名 Operit Harness，自用 clone 变体使用开发签名，可与原版 Operit 共存。第一次启动需要联网安装 Ubuntu 必需工具、锁定的 Node 与 Harness 依赖；这些内容存储在应用私有目录，安装 APK 本身不包含已展开的整个运行环境。

进入界面后在 More → Settings → Operit 设备中打开原生设备与权限面板。按实际用途授权悬浮确认、辅助功能、存储或通知；设备权限提升不会自动完成。模型通过 Harness 原有提供商设置或 Our Free Model 配置。

## 当前证据

- 最终 `:app:assembleClone` 成功，42 秒、234 项任务；安装更新成功，APK v2 签名有效，包内脚本及六个锁定归档内容/摘要一致
- Android 16 / SDK36、arm64、2560×1600、density2 的独立平板模拟器，应用以普通 UID 运行；完整官方内核在实际应用 PRoot 中安装并打开网页
- 两个原版插件和独立适配插件已加载；工作区、空会话和预览确认在更新 APK 及随机端口重启后保留
- 本地 MCP 实际目录57项，未认证401、恶意 Origin403、合法请求200；拒绝请求关闭连接，后续连接仍可使用
- 实际 `device_info`：一个米白色授权浮层，允许后返回设备信息；再次请求拒绝后返回 `isError: true / DENIED`
- 浏览器完成11个 OFM 页签的横竖屏共22项检查，以及模型设置、提供商表单、路由草稿和选择器；截图与参考比较见 [设计验收](../../../design-qa.md)

## 退出修复与最终设备检查

实际 PRoot 的 `/proc/<node-pid>/exe` 显示 APK 加载器，原检查因此拒绝正确的自有 Node。现统一核对内核可执行映射的设备号与 inode，并保留 pidfd、PID 启动时间、boot ID、命令和 generation 校验。新的真实 Linux 测试先失败后通过：8项测试中加载器正例从失败变为通过，只读映射和不同 inode 的反例仍拒绝。

连续三轮实际应用 PRoot 启停通过：每轮运行10个进程，停止后均为4个基础进程，仅剩应用及公共终端；没有 Harness PRoot、Bash、Python 或僵尸残留。停止后的文件句柄数252、252、253。服务被销毁后立即重建，也恢复到单个新运行时，进程数10、句柄数273前后相同。

Android 文件选择器成功导入52字节 TXT 并完成 Harness 上传预览，未发送模型消息。原生设备入口展示实际运行状态及未授予的通知、共享存储权限。状态栏深色图标在米白背景上可读。最终 APK 上再次验证 MCP 401/403关闭连接、合法200、旧 Agent/工作流执行/模型工具均拒绝，操作目录57项；OFM元数据200，实际页面1280×743且无横向溢出。

本轮完整 Harness 定向测试：纯 JVM16项、Node安装器及UI8项、主机恢复5项、Linux真实内核生命周期8项全部通过。此前 Linux 恢复4项及浏览器22项检查也已通过。没有把旧 Operit 全仓测试声称为本次结果。

最终 APK 为 `Operit-Harness-arm64.apk`，288,541,306字节，约275MiB。构建源码提交 `dcc0ecd`；当前版本名沿用 Operit1.12.2/versionCode51，发布渠道为本地自用开发签名。

SHA256：`62df4ceed4696b3212b5f7151d310d381584e8543e348dd95c4de0659196b376`。

交付目录为本地工作区的 `交付/`，内有APK、SHA256及安装说明。源码留在 `feat/deepseek-harness-tablet`，未推送或发布。结构化证据见 [Android验收](../../../harness/validation/android-acceptance-2026-10-22.json)。

## 实测范围与限制

未连接用户实体平板，不能把模拟器结果写成实体平板通过。未使用用户账号、API Key 或付费服务，真实模型回复、登录态页面与 EAC 未验证。上游包缺少三份 Anthropic 字体，当前实际加载 Inter、JetBrains Mono 与系统中文字体。

[DONE]

## 2026-10-27修正版

原包及以上记录保留。最新 `Operit-Harness-arm64-20261027.apk` 修复中文启动、默认诊断展示及终端控制符；签名和干净首次安装检查通过。见 [本次修复记录](04-startup-cn.md)。
