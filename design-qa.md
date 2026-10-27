# Tablet UI design QA — 2026-10-22

final result: passed

No actionable P0/P1/P2 finding remains in the verified UI scope. This is a client extension of the actual full DSH product, not a recreation using sample data. Controller Android integration gates below also passed on the owned emulator. Physical hardware and account-protected states remain explicit limits.

## Source, implementation, normalization

- Visual truth: `design/reference/user-selected-dsh-home.png`, 1440×900 pixels.
- Actual implementation: official full DSH 0.2.1-rc.2, Claude Style 0.18.2, author's OFM 2.5.9, installed adapter 0.1.2; `http://127.0.0.1:3087/`.
- Browser: user's Tabbit, task `operit-ui-adapter`. PNG screenshots are CSS-pixel screenshots at density 1: source and home implementation both 1440×900; landscape 1280×800; portrait 800×1280. No density resampling was used for measurement or focused comparisons. Side-by-side canvases concatenate equal-size crops; a tool preview may scale the full 2880px comparison for display.
- State: light studio home, sidebar open, no message sent, actual local profile with zero usage; OFM free page, actual unavailable/untested models, no account login or star. Native-entry screenshot uses actual installed client with the full desktop UA plus `OperitHarness/0.1` to exercise the native host contract; it does not emulate native status.
- App content is compared separately from the browser extension's pink translate widget and orange tray, which are external to DSH. The theme's idle crab pose and window-focus dimming are dynamic upstream behavior.

## Evidence opened and compared

| Evidence | Purpose |
| --- | --- |
| `design/screenshots/home-first.png` | Initial real home; stats top 102px |
| `design/screenshots/home-comparison-first.png` | Initial full source/implementation comparison |
| `design/screenshots/home-top-comparison-first.png` | Readable initial greeting/stat typography and spacing |
| `design/screenshots/home-final.png` | Final 0.1.2 browser home, 1440×900 |
| `design/screenshots/home-comparison-final.png` | Full source and final home side by side |
| `design/screenshots/home-top-comparison-final.png` | Equal-size greeting/stat crops |
| `design/screenshots/home-composer-comparison-final.png` | Equal-size composer/control/asset crops |
| `design/screenshots/ofm-landscape-final.png` | Actual 0.1.2 free page, 1280×800 |
| `design/screenshots/ofm-portrait-final.png` | Actual 0.1.2 free page, 800×1280 |
| `design/screenshots/portrait-comparison.png` | Initial side navigation and final top navigation at equal 800×1280 |
| `design/screenshots/routing-editor-final.png` | Real unsaved route form and model member picker |
| `design/screenshots/models-settings-final.png` | Stock model settings preserved |
| `design/screenshots/provider-form-final.png` | Stock provider form open, no credential entered or saved |
| `design/screenshots/native-boot-before.png` | Actual native-UA failure caught during QA |
| `design/screenshots/native-settings-final.png` | Fixed actual client section with exact native URI |

The source image and implementation were combined in the comparison files and opened together. Focused crops were necessary to read compact labels and compare the composer controls; full-view comparison alone was not used to pass QA. OFM has no selected source screenshot: its additional pages are evaluated against the selected palette/density and actual product behavior, not an invented source image.

## Comparison history and fixes

1. **P2 — crowded OFM settings and small targets.** Original landscape settings dialog was 800px wide, leaving 553px usable content. Original tabs were 30.59px high. A separate scoped stylesheet widens OFM settings, sets 44px primary targets, flattens glass/card effects, and reduces the existing live level illustration without hiding its status or ARIA label. Final landscape root: 884px; all 11 tab targets: 44px. Evidence: `ofm-landscape-final.png` and actual browser measurements.
2. **P2 — portrait side navigation consumed content width.** First installed adapter still left only 532px usable content at 800×1280. Browser assertion requiring at least 680px failed. Responsive top navigation increased content to 720px; the same assertion passed. Evidence: `portrait-before.png`, `portrait-comparison.png`, `ofm-portrait-final.png`. All settings destinations and OFM tabs remain visible and scrollable.
3. **P2 — home statistics began 30px above source.** First same-size comparison measured top 102px versus reference 132px. A desktop-only 30px panel margin restored top 132px; final browser assertion passed after client activation settled. Evidence: first/final full and focused home comparisons.
4. **P1 — native-UA client failed to activate.** Real native-marker browser boot exposed undeclared `slots` access. The official client runner guards service access using the returned plugin's `inject` declaration. The initial unit double was too permissive. It now mirrors that guard and failed both native cases before the fix. Adapter 0.1.2 declares `inject: ['slots']`; four unit tests and actual native-UA boot pass. Evidence: native before/after screenshots. No web status API was introduced.

## Required fidelity surfaces

- **Fonts and typography:** compact 20px home/page hierarchy, 12–13px supporting text, regular body weights, readable line height and wrapping retained. Browser `document.fonts` confirms **Claude Style Inter** and **JetBrains Mono** loaded. Upstream package omits the three Anthropic font files its host advertises, resulting in three 404s and fallback typography; this is a documented P3 limitation. Chinese falls back through the theme's existing system font stack. No proprietary font was downloaded.
- **Spacing and layout rhythm:** 280px home sidebar and 480px statistics panel retained; greeting and bottom composer remain in their upstream studio positions. The panel top now matches 132px. OFM uses restrained 10px cards, consistent padding, wrapped tabs and 44px main controls. Landscape content 884px and portrait 720px; all 22 route/viewport checks passed with no root horizontal overflow. Long content scrolls within the settings panel.
- **Colors/tokens:** main `#FCFCFB`, secondary `#F9F9F6`, sidebar/surface `#FBFBF9`, border `#E8E6DC`, text `#141413`, muted `#6E6A60`, accent `#D97757`. Semantic unavailable/authorization/disabled states remain upstream. Sponsor copy, links and logos remain, with the banner surface adapted to ivory.
- **Image quality and assets:** actual supplied Claude/OFM/sponsor logos and the upstream mascot are retained. No replacement artwork, custom SVG or placeholder image was introduced. The existing live level illustration is smaller; adjacent real percentages and counters remain unchanged.
- **Copy/content:** actual profile identity, workspaces, statistics, models, account state and model eligibility are preserved. Reference Ada/demo-project/history/blue activity and selected model are not copied into this new profile. Current DSH permission dropdown and actual model picker remain functional rather than replacing them with the reference's older Read/Edit/Yolo presentation. Native copy reads “查看运行状态，管理设备权限与本地工具。” with “打开设备与权限”.

## Interactions and runtime checks

- All 11 OFM tabs selected with `aria-selected=true` at both 1280×800 and 800×1280: free, EAC, channels, aggregate, saver, routing, ledger, logs, gateway, upgrade, picks. Root scroll width never exceeded its actual width.
- Aggregate search input accepts a filter and clears; route draft name and member search accept text; draft cancelled without saving. Existing chain remained.
- Stock Models settings opened; Add Provider form opened and cancelled. No key, account, login or star action was performed.
- Actual model picker opened; sidebar collapsed and reopened using the stock controls.
- Ordinary desktop has no “Operit 设备” settings entry. Actual installed client with native UA boots and registers the entry; link is exactly `operit-harness://device`. The URI was not followed in desktop Tabbit, because the actual native panel belongs to Android.
- Unit tests verify stylesheet ownership, idempotent disposal/reapply, guarded service dependency, exact UA gating and link target. Four passed after observed RED failures.
- Console/network inspected: no remaining adapter JavaScript exception. Known upstream font 404s described above. Stalled API requests during early QA recovered after old preview tabs/streams were closed; authenticated backend independently returned 200. Only the final useful preview remains.

## Residual limits / P3 follow-up

- Upstream Anthropic fonts absent; Inter/system fallback is verified and readable. Revisit only with a properly licensed upstream font distribution.
- Small upstream differences from reference (identity/history, permission presentation, zero activity/helper text, focus dimming and idle mascot pose) are expected product state, not fabricated to match the image.
- No authorized account was available for login-only overlays, account success, or EAC entitlement. Their scoped styles were implemented but those protected states were not visually exercised.
- Empty usage/log tables were checked; populated table row and long real-data scrolling remain a test gap. No fake requests or log rows were inserted.
- No hardware touch, native URI handling, screenshots from physical Android WebView, or APK installation was performed by this UI worker. Controller owns Android compilation/package/device gates.

## Implementation checklist

- [x] Preserve upstream full app, model controls/data and brand assets.
- [x] Scoped no-build plugin, official lifecycle and guarded service declaration.
- [x] Responsive OFM settings, portals, cards, inputs, tables, focus and reduced motion.
- [x] Actual desktop/native capability gating and exact native link.
- [x] Same-size source comparison, focused crops, fixes and recapture.
- [x] Real browser controls and all tab/viewport checks.
- [x] No unresolved actionable P0/P1/P2 in the verified UI scope.

## Android integration iteration

- **P1/P2 closed — duplicate purple permission UI.** Actual Android showed both an Activity dialog and the original Operit overlay. Removed the Activity presenter, supplied the existing overlay with the full ivory/terracotta palette. One approval now returns actual device_info successfully; a subsequent Deny returns isError/DENIED. Original Always allow policy is retained. Opened and verified `design/screenshots/android-permission-final.png`.
- **P2 closed — white system icons on ivory.** Actual attachment screenshot exposed unreadable status icons. Existing AndroidX insets controller now requests dark status/navigation icons. Opened final installed APK screenshot `design/screenshots/android-home-final.png`: time/battery/network and gesture bar are readable.
- Actual native URI followed through Settings → Operit 设备 → 打开设备与权限; panel reports real Harness state, effective executor, overlay grant and missing notification/storage grants. Opened `design/screenshots/android-native-panel-final.png`.
- Android system document picker imported the harmless52-byte `harness-attachment-check.txt`; Harness completed upload and displayed TXT52B. Opened `design/screenshots/android-attachment-final.png`; Android keyboard is visible and the composer remains reachable. No model message sent.
- Final installed APK opens actual Our Free Model settings, all tabs remain reachable and the content scrolls. Opened `design/screenshots/android-ofm-final.png`; real anonymous availability0% is preserved. Metadata endpoint200; root has no horizontal overflow.
- Native screenshots are raw2560×1600 device pixels at density2. WebView content is1280×743 CSS pixels after status/navigation bars; no image resampling used for measurement. Comparison to1440×900 reference remains the equal-size desktop crops above; Android captures establish integration/readability, not a same-viewport pixel comparison.
- Runtime integration: three Stop/start cycles clean, service recreation clean; details and verification limits are in `docs/TODO/deepseek-harness-tablet/03-validation.md`.


## 2026-10-27: startup localization correction

User reported an English terminal dump with odd symbols. Actual Android RED capture `startup-before-20261027.png` reproduced the unfiltered initial `[1A` / `[K` / `[31m` output. This startup experience was missing from the earlier gate; it was not a font failure.

The corrected native launch screen uses the existing ivory/terracotta palette, concise Chinese phases, genuine activity indication and opt-in diagnostics. Removed the giant startup permission-button stack; the existing device panel remains reachable. There is no fabricated percentage. First-install and retained-data screenshots are raw2560×1600 at density2, matching the old native capture; each was opened for visual inspection.

Opened evidence: `startup-cn-running-20261027.png`, `startup-cn-first-install-20261027.png`, `startup-cn-engine-install-20261027.png`, `startup-cn-stopped-20261027.png`, `startup-cn-failure-20261027.png`, `startup-cn-diagnostics-20261027.png`. Default startup/failure pages show Chinese, with no shell paths or control codes. Expanded diagnostics retain real errors and remain scrollable; technical installer prose stays in its original language. The real lock-conflict failure and one-tap retry passed; clean first installation completed and entered full Harness.

A source review found that permission refresh discarded a page-error diagnostic. Separate bounded fields fixed it; scoped rereview clean. Actual loopback navigation failure→device panel→permission refresh→expanded diagnostic retained both `ERR_CONNECTION_REFUSED` and permission reason. Screenshot `startup-cn-page-diagnostics-20261027.png`. Test network rule removed and reload recovered. System WebView's own error document and system-dialog styling are OS surfaces; the Chinese host alert is present, and no complete translation of those OS surfaces is claimed.

Result: passed for this startup correction on the owned Android16 emulator. No physical tablet/real-account/model-reply claim. Official web locale preference and upstream plugin files were preserved.
