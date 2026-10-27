# Operit Tablet UI

No-build client extension for official DSH `0.2.1-rc.2`, Claude Style `0.18.2` and Our Free Model `2.5.9`. It preserves their components, routes, accounts and real model state.

The bundle registers `operit-tablet-ui` using official `dsh.client` web metadata. Its client owns a scoped stylesheet through `ctx.effect`; disposal removes it and the settings contribution. It widens the OFM settings surface, provides 44px primary touch targets, native horizontal table scroll, solid ivory cards and portaled dialogs, and respects reduced motion. Upstream source packages stay unchanged.

A settings section appears only when the UA contains the exact `OperitHarness/0.1` marker. Its link `operit-harness://device` opens the actual native status/permission panel. There is no JavaScript bridge or invented status endpoint.

Run `npm test` for lifecycle and capability-gating tests. Real Tabbit layout, navigation and source-image comparison are recorded in project-root `design-qa.md` and the Task 3 report.

Pack this directory with `npm pack`; install the resulting local tarball with the full official engine's `dsh plugin --profile web add /absolute/path/operit-tablet-ui-0.1.2.tgz` under the intended `DSH_HOME`. A host restart or normal plugin reload may be needed to discover the newly added row. Do not modify installed theme or OFM files.
