# Harness Tablet Implementation Plan

> Spec: [已确认方案](../../TODO/deepseek-harness-tablet/index.md). Execute inline with subagent-driven-development. User authorizes tests, Android compilation and APK generation. New unpublished personal fork.

## Global Constraints

- Use full official DSH web/base profile; do not fork or reimplement the agent loop.
- Keep upstream theme and free-model plugin unmodified; extra styling is a separate plugin.
- Install free-model from the author's GitHub source, never the npm namesake.
- All native tool calls require authenticated loopback MCP, an explicit allowlist and Operit's existing permission check.
- Exclude voice, offline LLM and legacy Agent tools. Do not silently weaken sandbox policy.
- Keep runtimes, plugins and app integration versioned independently. Preserve user DSH data across updates.
- Work on `feat/deepseek-harness-tablet`; do not touch original workspace deleted files.
- Growing caches and downloads use `/Volumes/D盘`; no secret or generated runtime committed.

### Task 1: Android Harness host and native MCP

Ownership: new `app/src/main/java/com/ai/assistance/operit/harness/`, matching `app/src/test/.../harness/`, `app/src/main/res/values/harness_strings.xml`, manifest launcher changes only. No edits to runtime scripts or third-party sources.

- [x] Read actual terminal submodule APIs, original permission UI and native tool result APIs.
- [x] Write focused tests first for exact loopback launch URL validation, MCP auth/method/allowlist rejection, and command quoting. Run RED before implementation.
- [x] Implement lifecycle-owned service/runtime controller using Operit's Terminal/Ubuntu environment. Read `harness/bootstrap.sh` from assets copied into private runtime dir. Invoke script with environment variables `OPERIT_BRIDGE_URL`, `OPERIT_BRIDGE_TOKEN`, `OPERIT_RUNTIME_DIR` and action `install`, `start` or `stop`. Terminal path mapping must use actual submodule API. Retain terminal session ownership; no killing arbitrary port users.
- [x] Runtime reports install/start/log/error state, captures current process token URL, does not expose it externally, and keeps DSH data private. Only allow exact loopback origin in WebView; external links go to system browser. No JS interface for arbitrary remote pages. File selection works via Android document picker.
- [x] Implement stateless streamable HTTP MCP JSON-RPC initialize, notifications/initialized, tools/list and tools/call on `/mcp` with bearer auth, version/origin validation and bounded body; unknown methods/tools fail. Export allowlisted native device/file/memory/workflow/terminal/browser operations through a catalog + execute tool with parameter documentation. Exclude `run_ui_subagent`, model calls, voice, local models and arbitrary package activation. Check `ToolPermissionSystem.checkToolPermission` before `AIToolHandler.executeTool`; serialize requests so the existing single permission request callback cannot race. Render actual permission requests in the host.
- [x] Return screenshot bytes as MCP image, errors as tool errors, do not return a host-only screenshot path as a successful image.
- [x] Add tablet host setup/error/status UI in ivory `#FCFCFB`, sidebar `#FBFBF9`, border `#E8E6DC`, text `#141413`, muted `#6E6A60`, accent `#D97757`. WebView fills content after runtime readiness; desktop UA but physical viewport remains touchable. Hide removed voice/offline/old Agent entrypoints by making Harness the launcher. Keep reusable native permissions/services code.
- [x] Append UA marker `OperitHarness/0.1` and handle exact `operit-harness://device` as a native current-status/permission panel; no generic Intent or JS bridge. UI adapter adds Android-only settings entry using this contract.
- [x] Run covering tests/compile when inputs ready, self-review, commit only owned files and write report.

### Task 2: Official runtime and reproducible plugin installation

Ownership: `harness/scripts/`, `harness/bootstrap.sh`, `harness/upstream-lock.json`, vendor package staging, `docs/TODO/deepseek-harness-tablet/02-runtime.md`, assets sync and app Gradle/CMake removal of voice model build inputs. Controller owns this task alongside one Android implementer.

- [x] Pin official runtime, trusted published theme and author's free-model source commit/integrities.
- [x] Write installer contract tests first: profile isolation, reject bad artifacts, never npm free-model, preserve data, exact full profile bundles, managed distribution. Run RED then implement.
- [x] Install official Node LTS Linux arm64 in Operit Ubuntu private runtime, SHA256 verified; full DSH package in isolated prefix. Create profile via official CLI and install two plugins plus own adapter. Use vendored free-model tarball (full contents) with sha256 verification. Emit ready URL from actual `dsh web` output, bind loopback with no-open, fail on missing runtime/sandbox support.
- [x] Configure native MCP via a dedicated DSH bundle plugin with bridge env values; respect installed official MCP schema. No native bridge plugin on desktop preview without Android credentials.
- [x] Sync reproducible runtime assets into Android assets; document first-install network/storage requirements and independent updates.
- [x] Run relevant installer tests and real profile load. Commit owned files.

### Task 3: Tablet UI coverage and real browser verification

Ownership: `harness/plugins/operit-tablet-ui/`, screenshot artifacts and `design-qa.md`; separate task after native implementer returns, no simultaneous implementation subagents.

- [x] Read product-design and image-to-code as required. Inspect actual loaded theme and all free-model plugin route roots; preserve upstream implementation.
- [x] Create lightweight DSH client plugin, no build step, proper apply/dispose and official context services. Uniform ivory, restrained borders, compact typography, touch targets/scroll and responsive tablet widths. Theme free-model management subtrees including route dialogs, sidebar, tables and overlays. Preserve functional controls, data and capabilities. No fake model data or fabricated account success.
- [x] Add Android capabilities/status page only if actual bridge endpoints are present; use actual state and explicit unsupported text. Native host-only controls must not appear on ordinary desktop profile.
- [x] Install adapter into real profile; browse 1440×900 and tablet 1280×800/portrait layouts, exercise navigation, model page/settings/dialogs. Capture screenshot and compare chosen reference. Fix P0/P1/P2, record design QA.
- [x] Run relevant tests, full Android compilation/package when dependencies permit, broad code review, final honest validation record and APK location.

验收完成：实际 Android PRoot 三轮启停、服务重建、权限允许/拒绝、附件预览、两个插件页面及最终签名 APK 均已验证。实体平板与需账号的模型对话未验证，详见 `docs/TODO/deepseek-harness-tablet/03-validation.md`。
