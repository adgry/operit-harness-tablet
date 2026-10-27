package com.ai.assistance.operit.harness

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.R
import com.ai.assistance.operit.harness.HarnessStartup.Stage
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.provider.filesystem.PRootMountMapping
import com.ai.assistance.operit.terminal.provider.type.LocalTerminalProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import fi.iki.elonen.NanoHTTPD

/** One foreground shell and one MCP server, both owned by HarnessService. */
class HarnessRuntime(private val context: Context, private val publish: (HarnessState) -> Unit) {
    companion object {
        // A destroyed Service may be replaced before its asynchronous cleanup finishes.
        // Keep the shared runtime directory owned until the old host has fully stopped.
        private val ownership = Mutex()
    }

    private val provider = LocalTerminalProvider(context)
    private var pty: Pty? = null
    private val lifecycle = Mutex()
    private val closeLock = Any()
    @Volatile private var closing = false
    private var cleanupJob: Job? = null
    private var server: NativeMcpServer? = null
    private var token = ""
    private var stopCommand: String? = null
    private var sessionEnvironment: File? = null
    private val lines = ArrayDeque<String>()

    suspend fun run() = ownership.withLock {
        withContext(Dispatchers.IO) {
            token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            var readerJob: Job? = null
            var stage = Stage.NATIVE
            var failureState: HarnessState? = null
            try {
                checkOpen()
                publish(HarnessState(stage))
                HarnessNativeInitialization.initialize(context)
                stage = Stage.TERMINAL
                publish(HarnessState(stage))
                check(TerminalManager.getInstance(context).initializeEnvironment()) { "Terminal environment initialization failed" }
                val runtime = File(context.filesDir, "harness")
                copyAssets("harness", runtime)
                val mount = PRootMountMapping.buildRuntimeBindMounts(context.filesDir.absolutePath,
                    context.applicationInfo.dataDir, context.packageName, false)
                    .single { it.sourcePath == context.filesDir.absolutePath && it.targetPath == context.filesDir.absolutePath }
                val linuxRuntime = mount.targetPath + "/harness"
                // Register the provider's returned PTY before cancellation can resume the caller.
                // close() sets closing immediately; no resources may be allocated after teardown.
                val (shell, bridge) = withContext(NonCancellable) {
                    lifecycle.withLock {
                        checkOpen()
                        val bridgeServer = NativeMcpServer(context, token)
                        server = bridgeServer
                        bridgeServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                        val (terminal, ownedPty) = provider.startSession("operit-harness").getOrThrow()
                        pty = ownedPty
                        terminal to "http://127.0.0.1:${bridgeServer.listeningPort}/mcp"
                    }
                }
                val output = Channel<String>(64)
                readerJob = launch(Dispatchers.IO) {
                    try {
                        shell.stdout.bufferedReader().use { reader ->
                            while (true) output.send(reader.readLine() ?: break)
                        }
                        output.close()
                    } catch (e: Exception) { output.close(e) }
                }
                withTimeout(600_000) {
                    while (true) {
                        val line = output.receiveCatching().getOrNull() ?: error("Ubuntu shell exited during setup")
                        log(line)
                        publish(HarnessState(stage, log = lines.joinToString("\n")))
                        if (line.contains("TERMINAL_READY")) break
                    }
                }
                lifecycle.withLock {
                    checkOpen()
                    val environment = File(runtime, "session-${UUID.randomUUID()}.env")
                    sessionEnvironment = environment
                    HarnessSessionEnvironment.write(environment, linuxRuntime, bridge, token)
                    val linuxEnvironment = "$linuxRuntime/${environment.name}"
                    val script = "$linuxRuntime/bootstrap.sh"
                    stopCommand = HarnessSessionEnvironment.command(linuxEnvironment, script, "stop")
                    val install = HarnessSessionEnvironment.command(linuxEnvironment, script, "install")
                    val start = HarnessSessionEnvironment.command(linuxEnvironment, script, "start")
                    val command = "($install) && ($start); exit\n"
                    shell.stdin.write(command.toByteArray())
                    shell.stdin.flush()
                }
                var launch: String? = null
                while (currentCoroutineContext().isActive) {
                    val raw = output.receiveCatching().getOrNull() ?: error("Harness runtime exited")
                    val clean = HarnessStartup.clean(raw)
                    val candidate = HarnessSecurity.launchUrl(clean)
                    if (candidate != null && launch == null) launch = candidate
                    stage = if (launch != null) Stage.RUNNING else HarnessStartup.nextStage(stage, clean)
                    log(raw)
                    publish(HarnessState(stage, launch, lines.joinToString("\n")))
                }
            } catch (e: TimeoutCancellationException) {
                log("Ubuntu setup timed out: ${e.message.orEmpty()}")
                failureState = HarnessState(Stage.TIMED_OUT, error = R.string.harness_timeout_message, log = lines.joinToString("\n"))
                publish(failureState.copy(stage = Stage.STOPPING))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                val reason = when (stage) {
                    Stage.NATIVE, Stage.TERMINAL -> R.string.harness_environment_failed
                    Stage.PREREQUISITES, Stage.NODE, Stage.ENGINE, Stage.PLUGINS -> R.string.harness_install_failed
                    else -> R.string.harness_runtime_failed
                }
                failureState = HarnessState(Stage.FAILED, error = reason, log = lines.joinToString("\n"))
                publish(failureState.copy(stage = Stage.STOPPING))
            } finally {
                withContext(NonCancellable) {
                    close().join()
                    readerJob?.cancel()
                    // Retry becomes visible only once the previous host has been torn down.
                    failureState?.let(publish)
                }
            }
        }
    }

    private fun log(raw: String) {
        // Neither bearer credentials nor the web launch token are displayable diagnostics.
        val safe = HarnessStartup.diagnostic(raw, token)
        lines.addLast(safe.take(2000))
        while (lines.size > 100) lines.removeFirst()
    }

    private fun copyAssets(path: String, destination: File) {
        val children = context.assets.list(path) ?: error("Missing bundled asset: $path")
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(path).use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
        } else {
            check(destination.isDirectory || destination.mkdirs()) { "Cannot create private runtime directory" }
            children.forEach { copyAssets("$path/$it", File(destination, it)) }
        }
    }

    suspend fun stop() = withContext(NonCancellable) { close().join() }

    private fun checkOpen() {
        if (closing) throw CancellationException("Harness runtime is closing")
    }

    private suspend fun awaitVisibleZombie(ownedPty: Pty, timeoutMillis: Long): Boolean {
        if (ownedPty.pid <= 0) return false
        return withTimeoutOrNull(timeoutMillis) {
            var zombie = false
            while (!zombie) {
                // comm may contain parentheses/spaces; the state follows its final ') '.
                val stat = runCatching { File("/proc/${ownedPty.pid}/stat").readText() }.getOrNull()
                zombie = stat?.substringAfterLast(") ")?.firstOrNull() == 'Z'
                if (!zombie) delay(50L)
            }
            true
        } ?: false
    }

    /** Service.onDestroy may call this on Main: schedule once, never block that thread. */
    fun close(): Job = synchronized(closeLock) {
        cleanupJob ?: run {
            closing = true
            // This job owns teardown independently of the cancelled service/run scopes.
            CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                lifecycle.withLock {
                    try {
                        stopCommand?.let { command ->
                            // Provider bounds shell readiness (30s) and command execution (10s).
                            // Do not cancel creation before the provider can register its shell.
                            val result = provider.executeHiddenCommand(command, "operit-harness-stop", 10_000L)
                            if (!result.isOk || result.exitCode != 0) {
                                // Output/error text can contain runtime credentials; report only
                                // the transport enum and numeric exit status, never shell data.
                                Log.e("HarnessRuntime", "Owned host stop failed: state=${result.state}, exit=${result.exitCode}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("HarnessRuntime", "Owned host stop failed before a command result was available")
                    } finally {
                        val ownedPty = pty
                        // Let the supervisor reap its host and the login shells unwind while
                        // their PRoot tracer is still alive. Killing the tracer first orphans them.
                        if (ownedPty != null) {
                            runCatching {
                                ownedPty.stdin.write("\nexit\n".toByteArray())
                                ownedPty.stdin.flush()
                            }
                            if (!awaitVisibleZombie(ownedPty, 3_000L)) {
                                Log.w("HarnessRuntime", "Visible terminal graceful exit timed out")
                            }
                        }
                        // disconnect owns process termination, including the cached hidden shell
                        // and its separate reader scope. Keep our direct child unreaped until
                        // this sole signal owner has removed it, so its PID cannot be reused.
                        try {
                            provider.disconnect()
                        } catch (e: Exception) {
                            Log.e("HarnessRuntime", "Terminal provider disconnect failed", e)
                        } finally {
                            pty = null
                            if (ownedPty != null) {
                                if (awaitVisibleZombie(ownedPty, 2_000L)) {
                                    // This provider has no waitpid monitor; only this once-only
                                    // cleanup reaps the confirmed-zombie direct PTY child.
                                    runCatching { ownedPty.waitFor() }
                                } else {
                                    Log.w("HarnessRuntime", "Visible terminal not confirmed exited; skipping blocking reap")
                                }
                            }
                            runCatching { ownedPty?.stdout?.close() }
                            runCatching { ownedPty?.stdin?.close() }
                            val ownedServer = server
                            server = null
                            runCatching { ownedServer?.stop() }
                            stopCommand = null
                            runCatching { sessionEnvironment?.delete() }
                            sessionEnvironment = null
                        }
                    }
                }
            }.also { cleanupJob = it }
        }
    }
}

data class HarnessState(val stage: Stage = Stage.STOPPED, val url: String? = null, val log: String = "", val error: Int? = null) {
    val statusRes: Int get() = when (stage) {
        Stage.STOPPED -> R.string.harness_stopped
        Stage.NATIVE -> R.string.harness_preparing_native
        Stage.TERMINAL -> R.string.harness_preparing_terminal
        Stage.PREREQUISITES -> R.string.harness_preparing_prerequisites
        Stage.NODE -> R.string.harness_preparing_node
        Stage.ENGINE -> R.string.harness_installing_engine
        Stage.PLUGINS -> R.string.harness_installing_plugins
        Stage.STARTING -> R.string.harness_starting
        Stage.RUNNING -> R.string.harness_running
        Stage.STOPPING -> R.string.harness_stopping
        Stage.FAILED -> R.string.harness_failed
        Stage.TIMED_OUT -> R.string.harness_timed_out
    }
    val busy: Boolean get() = stage !in setOf(Stage.STOPPED, Stage.RUNNING, Stage.FAILED, Stage.TIMED_OUT)
}
