# 独立运行时与插件版本

`harness/upstream-lock.json` 记录官方 Node、官方完整 DSH、pnpm、Claude Style、作者 GitHub 原版 Our Free Model，以及本项目两个适配插件。原版包归档保存在 `harness/packages/`，安装前校验 SHA256。

## Android 安装

原生宿主将应用内的 `assets/harness/` 复制到私有目录，使用 Operit 的本地 Ubuntu/PRoot 会话执行 `bootstrap.sh install`。不会使用用户当前选择的 SSH 终端。

第一次安装需要联网下载锁定版本的 Linux Node 和 DSH 的依赖。所有下载、npm/pnpm 缓存、Node、内核、插件和会话数据均在应用私有运行时目录。应用要保留足够的存储空间，Android 也必须允许本地 Ubuntu 和 Node 进程持续运行。

以后启动会检查锁与实际已安装包版本，一致时直接启动，不重复联网安装。升级内核或插件后重新执行安装步骤。`data/` 与引擎版本目录分开，更新不删除会话、账号、工作区和用户 patch。

安装会补齐 Ubuntu 缺失的 `python3`、`curl`/CA、`xz-utils`、`tar` 和 `coreutils`；当前 Ubuntu 环境使用 Python 3.12（归档校验要求 Python 3.11+ 的 `hashlib.file_digest`）。安装锁由内核 `flock` 持有，进程被杀后可重试。Node 先在独立 staging 目录解压，检查 Node/npm 并写入归档摘要标记，最后才发布为版本目录；中断残留 staging 和旧的不完整目录会在重试时回收。

宿主记录包含 PID、Linux boot ID、进程 start time、随机 generation 及 supervisor 身份。启动会回收死亡 supervisor 的宿主与旧记录；停止前还核对可执行映射的设备号/inode、DSH 命令和 generation。PRoot通过APK加载器执行Node，`/proc/<pid>/exe`可显示加载器，不能据此把自有Node判断成其他进程。发信号使用先打开再验证的 pidfd，避免 PID 重用误杀。supervisor 意外死亡时，Linux parent-death signal 也会结束尚未发布记录的子进程。需要 Python 的 `os.pidfd_open` / `signal.pidfd_send_signal`、内核及实际 PRoot 环境允许相应调用；不支持时明确失败，不退回有竞态的 `kill(pid)`。Linux chroot 测试通过不等于平板的 PRoot 或官方沙箱已通过。

`start` 使用官方 `dsh web --no-open --host 127.0.0.1 --port 0`。地址和临时登录 token 来自当前进程标准输出，不假定固定端口，不接管其他进程。原生桥同样只监听 loopback，并使用进程级 Bearer token。

## 上游更新

更新每个上游包时，先核对原仓库的新版本、兼容要求和变更说明，然后将对应完整归档及 SHA256 更新到锁。Our Free Model 必须从 `Ebony-Vinyl/dsh-our-free-model` 的固定提交 `npm pack --ignore-scripts`，保留 `vendor/channel-pack/` 和 WASM，不使用 npm 同名第三方包。

运行 `node harness/scripts/sync-assets.mjs` 会打包本项目两个适配插件，验证全部上游归档，并生成 Android assets。它不会修改上游插件源码。修改本地适配插件后也必须重新同步。

Android 的 `syncHarnessAssets` 构建任务自动执行同一同步步骤，再交给 `syncMainAssets`，避免新检出遗漏运行时资源。构建机需要 Node 24 或满足官方 DSH 要求的 Node 版本、npm、JDK 21 和 Android SDK。自用安装包使用 `./gradlew :app:assembleClone`，应用名为 Operit Harness，包名后缀为 `.clone`，可以与原版共存。

Our Free Model 使用 `distribution: managed`，避免插件自己的升级器与 APK 安装器同时写文件。模型渠道保留上游行为；桌面宿主限定的 EAC、账号登录、地区及额度限制不会被绕过。匿名渠道是否可用取决于用户实际网络和上游服务。

完整 Harness 的沙箱策略保持官方默认。Android 的 Linux 内核、PRoot 和隔离机制与桌面 Linux 不同，相关能力失败会显示错误。这里没有自动改用无沙箱，也没有回到旧 Operit Agent。

## 主机检查

独立开发 profile：`/Volumes/D盘/软件数据/DeepSeekHarness/operit-tablet-dev`。该目录与现有个人 Harness 配置隔离，未读取或迁移既有账号凭据。

2026-10-22 已在完整官方 `0.2.1-rc.2` web/base profile 加载 `dsh-claude-style@0.18.2` 与 GitHub 原版 `dsh-our-free-model@2.5.9`。安装器的归档校验、完整 profile 检查、MCP 地址验证和配置保留测试均通过。真实平板运行需要安装 APK 验证，主机浏览器结果不代表 Android 内核已通过验收。

## Android 验收补记

2026-10-22 完整官方内核已在应用普通UID的实际PRoot中运行；连续三轮停止均清理自有Node、Python、Bash及PRoot，没有关闭公共终端。Linux新增加载器回归先RED后GREEN，身份检查没有放宽为仅凭PID或命令字符串。详见 [设备验收记录](03-validation.md)。

[DONE]
