# Operit Harness 平板自用版

Android 8.0+ / arm64。把完整的 DeepSeek Harness 运行时、Ubuntu 环境与本地 Node 引擎打包进一个 APK，
桌面浏览器里的同一套 DSH 界面直接在平板上跑；本分支另外内置 **白刃破甲 v4.0.1** 人格与技能包，
并支持 **应用内更新**。

- 应用包名：`com.ai.assistance.operit.clone`（可与原版 Operit 共存）
- 当前版本：`1.12.3`（versionCode 52）
- 运行时：Node 24.22.1 + `@deepseek-ai/dsh` 0.2.1-rc.2 + pnpm 11.7.0
- 插件：`dsh-claude-style` 0.18.2、`dsh-our-free-model` 2.5.9、`operit-android-bridge`、`operit-tablet-ui`、`bairen-persona`

## 下载与更新

发布页：<https://github.com/adgry/operit-harness-tablet/releases/latest>

首次安装需要手动下载 APK。装好之后，启动页底部与「设备与权限」面板都有「版本 x.y.z · 检查更新」，
应用启动时也会自动检查：发现新版本会显示版本说明，点「下载并安装」即走系统安装器覆盖安装，
本地运行环境、模型账号与设置全部保留。

更新清单是发布页上的 `update.json`：

```json
{
  "versionCode": 52,
  "versionName": "1.12.3",
  "apkUrl": "https://github.com/adgry/operit-harness-tablet/releases/download/v1.12.3/Operit-Harness-arm64-1.12.3.apk",
  "sha256": "…",
  "notes": "本次更新内容"
}
```

## 白刃破甲（内置）

`harness/plugins/bairen-persona` 与 `harness/bairen` 会在 Harness 首次安装或升级时落到 `DSH_HOME`：

| 落地位置 | 内容 |
|---|---|
| `AGENTS.md` | 白刃人格块（`<!-- BAIREN-DSH:START -->` 幂等标记） |
| `skills/bairen-pentest/` | 渗透技能包（SKILL.md + 13 篇 references + 6 个脚本） |
| `profiles/*/cordis.patch.yml` | 注册 `bairen-persona`，接管 system-prompt 与 AGENTS 预算 |

人格文案、24 类场景矩阵、绿框文案分别来自 `harness/plugins/bairen-persona/{index.js,scenarios.json,lib/client.js}`。
桌面版注入器源码保留在 `tools/bairen-dsh/`，改完 payload 后可用它重新生成插件实体，再跑 `sync-assets`。

## 构建

```bash
# 1. 锁定依赖与本地插件，生成 assets/harness
node harness/scripts/sync-assets.mjs

# 2. 打包（clone 变体 = 可与原版共存的自用版）
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleClone
# 产物：app/build/outputs/apk/clone/app-clone.apk
```

发布新版本：

```bash
tools/release.sh "本次更新说明"
```

脚本会读取 `app/build.gradle.kts` 的版本号、生成 `update.json` 与 SHA256、打 tag 并上传 Release。

## 已知限制

- 仅 arm64、Android 8.0+；模拟器需 API 26 以上且带 arm64 转译或真机。
- 首次启动要联网安装 Ubuntu 依赖与 Node/Harness 运行时，几分钟起步，占用空间较大。
- EAC / 部分上游免费渠道取决于对方可用性，与桌面宿主一致。
