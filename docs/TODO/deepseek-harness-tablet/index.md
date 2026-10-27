---
status: verified-on-emulator
fork: local-only
upstream: https://github.com/AAswordman/Operit
---

# DeepSeek Harness 平板版

用户已确认这是未发布的自用新版本，并于 2026-10-22 授权自动化测试、Android 编译及生成 APK。

## 已确认的方案

完整使用官方 `@deepseek-ai/dsh` 和官方 web/base profile。Android 使用 Operit 的 Ubuntu/PRoot 终端承载 Linux Node，不修改 Harness 的 Agent 循环、存储、调度、模型和权限实现。模型、会话、工具编排、MCP、Skills、插件管理跟随 Harness。

界面直接使用原版 `dsh-claude-style`，以用户选择的桌面首页截图为准。横屏平板常驻侧栏、紧凑标题、居中内容、底部输入框。独立的 `operit-tablet-ui` 插件覆盖额外页面和触摸交互，保持两个上游插件原文件不变。

`dsh-our-free-model` 从 Ebony-Vinyl 的 GitHub 源码固定提交打包，包含完整渠道模块和 WASM；禁止安装 npm 同名非官方包。采用 managed 分发，更新归宿主安装器统一管理。桌面限定 EAC 不伪造宿主，不自动登录账号。网络、账号和额度条件由上游决定。

Android 暴露经过认证的本地 MCP 设备工具桥接。仅导出明确允许的工具，经过现有 Operit 工具权限系统；截图作为 MCP 图片返回。禁用旧 Agent、语音、离线模型工具入口。不能自动放宽 Harness 的沙箱策略。

原 Operit 工作流通过只读工具查看，执行及定时调度采用 Harness 自身实现。原工作流执行器可以调用旧 Agent，不作为新版的执行入口。附件通过 Harness 文件选择器导入，旧版 MainActivity 的外部分享/深链入口关闭。

内核、主题、免费模型插件和 Android 适配插件分别锁版本，更新保留用户数据。依赖和运行缓存留在 D 盘。Android 端资料放应用私有目录。

## 实现接口

- Android 启动 Activity：`com.ai.assistance.operit.harness.HarnessActivity`
- 运行时资源目录：`app/src/main/assets/harness/`
- 源脚本和锁：`harness/`，由打包脚本同步到 assets
- 本地桥：随机可用端口，绑定 `127.0.0.1`，Bearer token；MCP 路径 `/mcp`
- 启动输出只接受当前进程的 `dsh web: http://127.0.0.1:<port>/?token=...` 地址；禁止加载远端网页到有本地能力的 WebView
- WebView 按平板实际逻辑宽度渲染，支持文件选择、外部链接、返回键和软键盘；启动、安装、错误和权限界面使用相同米白/陶土色系

## 验收

- 两个上游插件在真实完整 Harness profile 加载，新增页面得到统一主题，主交互可用
- Android 从原生入口安装/启动本地 Harness；重启后会话、插件及设置保留
- MCP 未授权、未知工具和旧 Agent 工具请求均拒绝；执行遵守工具权限
- Node/沙箱失败可见，禁止悄悄切换为无沙箱或旧 Operit Agent
- 生成 APK 前完成相关测试和编译；无设备时明确区分主机验证与真机验证

参考文件：[用户选定首页](../../../design/reference/user-selected-dsh-home.png)。实现已生成自用 APK 并通过模拟器验收；功能清单、安装步骤与实测限制见 [交付验收](03-validation.md)。

[DONE]

2026-10-27：[中文启动及终端符号修复](04-startup-cn.md) 已完成模拟器验证，交付日期版修正APK。
