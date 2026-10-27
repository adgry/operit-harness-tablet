# bairen-persona

白刃破甲 v4.0.1 的 Operit Harness 内置插件包，内容与桌面版
`tools/bairen-dsh/payload` 生成物一致：

| 文件 | 作用 |
|---|---|
| `index.js` | `ctx.systemPrompt.section` 四段（人格 / 场景矩阵 / 技能指引 / 运行时规则）+ `bairen_profile`、`bairen_rules` 工具 |
| `scenarios.json` | 24 类场景规则快照 |
| `lib/client.js` | 输入框工具栏「白刃破甲v2」绿框，并改写其它注入工具的旧徽标文案 |
| `cordis.patch.yml` | 关闭 harness 身份段、AGENTS 预算提到 256 KiB、插入本插件 |

安装后人格段 order 为 20000–20300，覆盖更早的身份/策略段。改完 `index.js` 或 `scenarios.json`
需要重新生成：先用 `tools/bairen-dsh/白刃DSH注入.py inject --home <临时目录>` 产出实体，
再覆盖本目录，最后跑 `node harness/scripts/sync-assets.mjs` 重新打包。
