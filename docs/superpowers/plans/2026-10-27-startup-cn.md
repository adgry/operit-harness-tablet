# Chinese Startup Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Replace the raw English terminal-facing launch screen with readable Chinese startup states and opt-in sanitized diagnostics.

**Architecture:** Retain the existing runtime/service/WebView ownership. A pure JVM helper sanitizes/redacts terminal diagnostics and classifies explicit bootstrap stage markers; Android strings render user-visible states. The Activity keeps detailed output collapsed by default.

**Tech Stack:** Kotlin Android Views, JVM Java/JUnit, Bash, official DSH unchanged.

**Spec:** docs/TODO/deepseek-harness-tablet/04-startup-cn.md

## Global Constraints

- Preserve official engine, upstream plugins, private data, native authorization, PID ownership and sandbox policy.
- Default native copy is Chinese for this Chinese personal build; no raw exception/path on collapsed startup screen.
- No fabricated progress percentage or success. Keep genuine diagnostics accessible, bounded and redacted.
- Terminal submodule unchanged. D-drive dependencies/build outputs only. Existing tests/build/APK authorization persists.

### Task 1: Chinese launch states and safe diagnostic presentation

Files: app/src/main/java/com/ai/assistance/operit/harness/{HarnessActivity.kt,HarnessRuntime.kt,HarnessService.kt,HarnessStartup.java}; app/src/main/res/values/harness_strings.xml; app/src/test/java/com/ai/assistance/operit/harness/HarnessStartupTest.java; harness/bootstrap.sh.

- [x] Write JVM behavior tests and observe RED for preserving Chinese while removing CSI, OSC/BEL, CR/backspace controls; token/query redaction; explicit stage transitions; ordinary log text cannot imply readiness.

```java
assertEquals("准备环境", HarnessStartup.clean("\u001b[32m准备环境\u001b[0m"));
assertEquals("Node", HarnessStartup.clean("\u001b]0;title\u0007Node"));
```

- [x] Implement the minimal diagnostic helper and integrate it on every log path before storing diagnostics. Readiness still uses the strict existing launch URL parser.
- [x] Add explicit bootstrap phase messages before prerequisites, Node, engine, plugin install and final start. Map actual phases to Chinese native status resources. Keep stopped/error/timeout states clear; retain redacted technical failures in collapsed diagnostic details.
- [x] Update the existing ivory launch surface: clear title, real activity indicator, concise Chinese current step and first-install network explanation, relevant retry/start/stop actions, “查看诊断详情” / “收起诊断详情”. Log view starts GONE and toggles on explicit tap; scrolling/details do not displace the main startup status. Preserve current permission panel and shell lifecycle.
- [x] Translate all native Harness strings, including notification/setup/device labels; replace boolean/executor-code display with readable Chinese where the existing panel owns it. No changes to upstream permission policy.
- [x] Run focused JUnit tests plus existing16 contracts; inspect diff, commit only owned source, report exact RED/GREEN commands/results.

### Task 2: Device acceptance and APK handoff

Controller owns build, emulator and docs; no simultaneous implementation agents.

- [x] Review Task1 against the spec; resolve source findings through its implementer.
- [x] Build `:app:assembleClone --no-daemon --max-workers=2`; install on owned emulator. Capture Chinese startup with diagnostics collapsed, expanded sanitized details, and successful full Harness boot. Also exercise clean initial install and a retained-data restart; do not claim physical tablet testing.
- [x] Verify final APK signature and bundled helper/source equality. Preserve the2026-10-22 APK at its original path, publish the corrected local APK plus checksum in the existing delivery folder.
- [x] Save screenshots and actual validation, update this checklist, keep local branch and source commits. No push or external publish.

Completed: source+scoped review clean; actual retained-data boot, failure/retry, clean first install and page-error preservation passed. Local artifact/source evidence is recorded in docs/TODO/deepseek-harness-tablet/04-startup-cn.md. Physical tablet and real model authentication remain untested.
