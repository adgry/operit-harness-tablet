package com.ai.assistance.operit.harness

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.core.tools.system.action.ActionListenerFactory
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/** Native setup/permissions around the full official DSH web client. No JavaScript bridge. */
class HarnessActivity : ComponentActivity() {
    private val ivory = Color.rgb(252, 252, 251)
    private val ink = Color.rgb(20, 20, 19)
    private val accent = Color.rgb(217, 119, 87)
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var activityIndicator: ProgressBar
    private lateinit var failureMessage: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var setup: LinearLayout
    private lateinit var runtimeActions: LinearLayout
    private var launch: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var nativePanel: AlertDialog? = null
    private var nativeStatus: TextView? = null
    private var nativeDiagnostics: TextView? = null
    private var executorStatus = ""
    private var permissionDiagnostics = ""
    private var pageDiagnostics = ""
    private var permissionRefresh: Job? = null
    private var permissionSelection: Job? = null
    private var updateCheck: Job? = null
    private var updateDownload: Job? = null
    private var pendingUpdate: java.io.File? = null
    private var updatePermissionAsked = false
    private val storagePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissionStatus()
    }
    private val files = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initAndroidPermissionPreferences(applicationContext)
        // ToolPermissionSystem owns the single permission renderer, including Always allow.
        // Give its overlay the same palette as the native host and official web client.
        ToolPermissionSystem.getInstance(this).setColorScheme(lightColorScheme(
            primary = ComposeColor(accent),
            onPrimary = ComposeColor(ink),
            primaryContainer = ComposeColor(0xFFF5E2D8),
            onPrimaryContainer = ComposeColor(ink),
            inversePrimary = ComposeColor(0xFFE9B5A1),
            secondary = ComposeColor(0xFF6E6A60),
            onSecondary = ComposeColor(ivory),
            secondaryContainer = ComposeColor(0xFFE8E6DC),
            onSecondaryContainer = ComposeColor(ink),
            tertiary = ComposeColor(0xFF8C5A45),
            onTertiary = ComposeColor(ivory),
            tertiaryContainer = ComposeColor(0xFFF1DED3),
            onTertiaryContainer = ComposeColor(ink),
            background = ComposeColor(ivory),
            onBackground = ComposeColor(ink),
            surface = ComposeColor(ivory),
            onSurface = ComposeColor(ink),
            surfaceVariant = ComposeColor(0xFFF2F0E9),
            onSurfaceVariant = ComposeColor(0xFF6E6A60),
            surfaceTint = ComposeColor(accent),
            inverseSurface = ComposeColor(ink),
            inverseOnSurface = ComposeColor(ivory),
            error = ComposeColor(0xFFB3261E),
            onError = ComposeColor(ivory),
            errorContainer = ComposeColor(0xFFF9DEDC),
            onErrorContainer = ComposeColor(0xFF410E0B),
            outline = ComposeColor(0xFF6E6A60),
            outlineVariant = ComposeColor(0xFFE8E6DC),
            scrim = ComposeColor(0xFF141413),
            surfaceBright = ComposeColor(ivory),
            surfaceDim = ComposeColor(0xFFE8E6DC),
            surfaceContainerLowest = ComposeColor(ivory),
            surfaceContainerLow = ComposeColor(0xFFFBFBF9),
            surfaceContainer = ComposeColor(0xFFF7F6F2),
            surfaceContainerHigh = ComposeColor(0xFFF2F0E9),
            surfaceContainerHighest = ComposeColor(0xFFE8E6DC),
            primaryFixed = ComposeColor(0xFFF5E2D8),
            primaryFixedDim = ComposeColor(0xFFE9B5A1),
            onPrimaryFixed = ComposeColor(ink),
            onPrimaryFixedVariant = ComposeColor(0xFF8C5A45),
            secondaryFixed = ComposeColor(0xFFE8E6DC),
            secondaryFixedDim = ComposeColor(0xFFD5D1C6),
            onSecondaryFixed = ComposeColor(ink),
            onSecondaryFixedVariant = ComposeColor(0xFF6E6A60),
            tertiaryFixed = ComposeColor(0xFFF1DED3),
            tertiaryFixedDim = ComposeColor(0xFFE0BDA8),
            onTertiaryFixed = ComposeColor(ink),
            onTertiaryFixedVariant = ComposeColor(0xFF8C5A45)
        ))
        window.statusBarColor = ivory
        window.navigationBarColor = ivory
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ivory) }
        runtimeActions = LinearLayout(this).apply { setPadding(dp(16), dp(4), dp(16), dp(4)); setBackgroundColor(Color.rgb(251,251,249)) }
        val title = TextView(this).apply { text = getString(R.string.harness_name); textSize = 18f; setTextColor(ink); setPadding(0,dp(12),0,dp(12)) }
        runtimeActions.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        stopButton = button(R.string.harness_stop) { startService(Intent(this, HarnessService::class.java).setAction(HarnessService.STOP)) }
        runtimeActions.addView(stopButton)
        root.addView(runtimeActions)
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(232,230,220)) }, LinearLayout.LayoutParams(-1,dp(1)))
        setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32),dp(28),dp(32),dp(16)) }
        status = TextView(this).apply { textSize = 24f; setTextColor(ink) }
        activityIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
            contentDescription = getString(R.string.harness_startup_activity)
            visibility = View.GONE
        }
        setup.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(activityIndicator, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        })
        failureMessage = TextView(this).apply {
            textSize = 15f; setTextColor(Color.rgb(140, 70, 49)); setPadding(0, dp(12), 0, 0); visibility = View.GONE
        }
        setup.addView(failureMessage)
        setup.addView(TextView(this).apply { text = getString(R.string.harness_setup_description); textSize = 15f; setTextColor(Color.rgb(110,106,96)); setPadding(0,dp(14),0,dp(18)) })
        startButton = button(R.string.harness_start) { startRuntime() }
        setup.addView(LinearLayout(this).apply {
            addView(startButton)
            addView(button(R.string.harness_device_title) { showNativePanel() })
        })
        setup.addView(TextView(this).apply {
            text = getString(R.string.harness_update_current, HarnessUpdater.currentVersionName)
            textSize = 13f
            setTextColor(Color.rgb(110,106,96))
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { checkForUpdates(true) }
        })
        logs = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(110,106,96)); setTextIsSelectable(true) }
        val diagnosticScroll = ScrollView(this).apply { addView(logs); visibility = View.GONE; setPadding(0, dp(12), 0, 0) }
        setup.addView(Button(this).apply {
            text = getString(R.string.harness_show_diagnostics)
            setTextColor(accent)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val show = diagnosticScroll.visibility != View.VISIBLE
                diagnosticScroll.visibility = if (show) View.VISIBLE else View.GONE
                setText(if (show) R.string.harness_hide_diagnostics else R.string.harness_show_diagnostics)
            }
        }, LinearLayout.LayoutParams(-2, -2))
        setup.addView(diagnosticScroll, LinearLayout.LayoutParams(-1,0,1f))
        root.addView(setup, LinearLayout.LayoutParams(-1,0,1f))
        web = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(ivory)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = settings.userAgentString.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace("; wv", "").replace(" Mobile", "") + " OperitHarness/0.1"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && HarnessSecurity.nativeControl(launch, view.url, request.url.toString())) {
                        showNativePanel()
                        return true
                    }
                    if (launch?.let { HarnessSecurity.sameOrigin(it, request.url.toString()) } == true) return false
                    if (request.isForMainFrame) openExternal(request.url)
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame || request.url.toString() == "about:blank") return
                    pageDiagnostics = HarnessStartup.diagnostic(error.description.toString(), "").take(2000)
                    AlertDialog.Builder(this@HarnessActivity).setTitle(R.string.harness_page_error)
                        .setMessage(R.string.harness_page_error_description)
                        .setPositiveButton(R.string.harness_reload) { _, _ -> launch?.let(view::loadUrl) }
                        .setNeutralButton(R.string.harness_show_diagnostics) { _, _ -> showNativePanel() }
                        .setNegativeButton(R.string.harness_cancel, null).show()
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (launch?.let { HarnessSecurity.sameOrigin(it, request.url.toString()) } == true) return null
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked origin", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try { files.launch(params.createIntent().apply { addCategory(Intent.CATEGORY_OPENABLE) }); true }
                    catch (e: ActivityNotFoundException) { fileCallback = null; callback.onReceiveValue(null); false }
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else moveTaskToBack(true) }
        })
        lifecycleScope.launch {
            HarnessService.state.collect { state ->
                updateNativeStatus(state)
                status.setText(state.statusRes)
                failureMessage.visibility = if (state.error == null) View.GONE else View.VISIBLE
                state.error?.let(failureMessage::setText)
                activityIndicator.visibility = if (state.busy) View.VISIBLE else View.GONE
                startButton.visibility = if (state.busy) View.GONE else View.VISIBLE
                startButton.setText(if (state.error != null) R.string.harness_retry else R.string.harness_start)
                stopButton.visibility = if (state.busy || state.url != null) View.VISIBLE else View.GONE
                stopButton.isEnabled = state.stage != HarnessStartup.Stage.STOPPING
                val ready = state.url != null
                setup.visibility = if (ready) View.GONE else View.VISIBLE
                runtimeActions.visibility = if (ready) View.GONE else View.VISIBLE
                web.visibility = if (ready) View.VISIBLE else View.GONE
                if (state.url != launch) {
                    launch = state.url
                    if (state.url != null) web.loadUrl(state.url) else { web.stopLoading(); web.loadUrl("about:blank") }
                }
            }
        }
        startRuntime()
        maybeAutoCheckUpdates()
    }

    private fun showNativePanel() {
        nativePanel?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ivory)
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        nativeStatus = TextView(this).apply {
            textSize = 16f
            setTextColor(ink)
            setPadding(0, 0, 0, dp(18))
        }.also(content::addView)
        updateNativeStatus(HarnessService.state.value)
        content.addView(TextView(this).apply {
            text = getString(R.string.harness_executor_description)
            textSize = 14f
            setTextColor(Color.rgb(110,106,96))
        })
        for (level in listOf(AndroidPermissionLevel.STANDARD, AndroidPermissionLevel.ACCESSIBILITY,
            AndroidPermissionLevel.DEBUGGER, AndroidPermissionLevel.ROOT)) {
            content.addView(Button(this).apply {
                text = getString(R.string.harness_choose_level, permissionLevelLabel(level))
                setTextColor(accent)
                setOnClickListener { selectPermissionLevel(level) }
            })
        }
        content.addView(button(R.string.harness_refresh_permissions) { refreshPermissionStatus() })
        content.addView(button(R.string.harness_notifications) {
            openPermissionSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        content.addView(button(R.string.harness_storage) { requestStorageAccess() })
        content.addView(button(R.string.harness_overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        content.addView(button(R.string.harness_accessibility) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        content.addView(Button(this).apply {
            text = getString(R.string.harness_update_current, HarnessUpdater.currentVersionName)
            setTextColor(accent)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { checkForUpdates(true) }
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.harness_device_description)
            textSize = 14f
            setTextColor(Color.rgb(110,106,96))
            setPadding(0, dp(18), 0, 0)
        })
        val diagnosticView = TextView(this).apply {
            text = diagnosticText(HarnessService.state.value)
            textSize = 12f; setTextColor(Color.rgb(110,106,96)); setTextIsSelectable(true)
        }
        nativeDiagnostics = diagnosticView
        val diagnosticScroll = ScrollView(this).apply { addView(diagnosticView); visibility = View.GONE }
        content.addView(Button(this).apply {
            text = getString(R.string.harness_show_diagnostics); setTextColor(accent); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val show = diagnosticScroll.visibility != View.VISIBLE
                diagnosticScroll.visibility = if (show) View.VISIBLE else View.GONE
                setText(if (show) R.string.harness_hide_diagnostics else R.string.harness_show_diagnostics)
            }
        })
        content.addView(diagnosticScroll, LinearLayout.LayoutParams(-1, dp(180)))
        nativePanel = AlertDialog.Builder(this).setTitle(R.string.harness_device_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.harness_done, null)
            .setNeutralButton(R.string.harness_stop) { _, _ ->
                startService(Intent(this, HarnessService::class.java).setAction(HarnessService.STOP))
            }.create().apply {
                setOnDismissListener { permissionRefresh?.cancel(); nativeStatus = null; nativeDiagnostics = null; nativePanel = null }
                show()
                window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ivory))
                getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
                getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(accent)
            }
        refreshPermissionStatus()
    }

    private fun updateNativeStatus(state: HarnessState) {
        val diagnostics = diagnosticText(state)
        if (::logs.isInitialized) logs.text = diagnostics
        nativeDiagnostics?.text = diagnostics
        nativeStatus?.text = buildString {
            append(getString(state.statusRes))
            if (executorStatus.isNotEmpty()) append("\n\n").append(executorStatus)
            state.error?.let { append("\n").append(getString(it)) }
            val notifications = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString)
                .any { it == ComponentName(this@HarnessActivity, com.ai.assistance.operit.services.notification.OperitNotificationListenerService::class.java) }
            append("\n").append(getString(R.string.harness_notification_state, permissionGrantLabel(notifications)))
            val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
                else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            append("\n").append(getString(R.string.harness_storage_state, permissionGrantLabel(storage)))
            append("\n\n").append(getString(if (Settings.canDrawOverlays(this@HarnessActivity)) R.string.harness_overlay_granted else R.string.harness_overlay_required))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        installPendingUpdate()
    }

    private fun selectPermissionLevel(level: AndroidPermissionLevel) {
        if (permissionSelection?.isActive == true) return
        permissionSelection = lifecycleScope.launch {
            try {
                permissionRefresh?.cancelAndJoin()
                val executor = withContext(Dispatchers.IO) {
                    androidPermissionPreferences.savePreferredPermissionLevel(level)
                    AndroidShellExecutor.clearPreferredPermissionLevelCache()
                    ShellExecutorFactory.clearCache()
                    ActionListenerFactory.clearCache()
                    ShellExecutorFactory.getExecutor(applicationContext, level)
                }
                refreshPermissionStatus()
                // RootShellExecutor may synchronously wait for libsu or an exec-su process.
                // Accessibility settings and Shizuku permission registration stay on Main.
                val requestDispatcher = if (level == AndroidPermissionLevel.ROOT) Dispatchers.IO else Dispatchers.Main.immediate
                withContext(requestDispatcher) {
                    executor.requestPermission { runOnUiThread { refreshPermissionStatus() } }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) {
                permissionDiagnostics = HarnessStartup.diagnostic("${error.javaClass.simpleName}: ${error.message.orEmpty()}", "").take(2000)
                executorStatus = getString(R.string.harness_permission_error)
                updateNativeStatus(HarnessService.state.value)
            }
        }
    }

    private fun refreshPermissionStatus() {
        if (nativeStatus == null) return
        permissionRefresh?.cancel()
        permissionRefresh = lifecycleScope.launch {
            executorStatus = try {
                withContext(Dispatchers.IO) {
                    val selected = androidPermissionPreferences.getPreferredPermissionLevel()
                    val effective = selected ?: AndroidPermissionLevel.STANDARD
                    val executor = ShellExecutorFactory.getExecutor(applicationContext, effective)
                    val grant = executor.hasPermission()
                    val available = executor.isAvailable()
                    permissionDiagnostics = HarnessStartup.diagnostic(grant.reason, "").take(2000)
                    getString(R.string.harness_executor_state,
                        selected?.let(::permissionLevelLabel) ?: getString(R.string.harness_level_unselected), permissionLevelLabel(effective),
                        getString(if (available) R.string.harness_available else R.string.harness_unavailable), permissionGrantLabel(grant.granted),
                        getString(when {
                            !available -> R.string.harness_executor_needs_service
                            !grant.granted -> R.string.harness_executor_needs_permission
                            else -> R.string.harness_executor_ready
                        }))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) {
                permissionDiagnostics = HarnessStartup.diagnostic("${error.javaClass.simpleName}: ${error.message.orEmpty()}", "").take(2000)
                getString(R.string.harness_permission_error)
            }
            updateNativeStatus(HarnessService.state.value)
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openPermissionSettings(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            storagePermissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun openPermissionSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (error: ActivityNotFoundException) {
            executorStatus = getString(R.string.harness_settings_unavailable)
            updateNativeStatus(HarnessService.state.value)
        }
    }

    private fun permissionLevelLabel(level: AndroidPermissionLevel): String = getString(when (level) {
        AndroidPermissionLevel.STANDARD -> R.string.harness_level_standard
        AndroidPermissionLevel.ACCESSIBILITY -> R.string.harness_level_accessibility
        AndroidPermissionLevel.DEBUGGER -> R.string.harness_level_debugger
        AndroidPermissionLevel.ROOT -> R.string.harness_level_root
        AndroidPermissionLevel.ADMIN -> R.string.harness_level_admin
    })

    private fun permissionGrantLabel(granted: Boolean): String = getString(if (granted) R.string.harness_granted else R.string.harness_not_granted)

    private fun diagnosticText(state: HarnessState): String = listOf(state.log, pageDiagnostics, permissionDiagnostics)
        .filter(String::isNotBlank).joinToString("\n").ifBlank { getString(R.string.harness_diagnostics_empty) }

    private fun startRuntime() { startForegroundService(Intent(this, HarnessService::class.java)) }
    private fun button(label: Int, action: () -> Unit) = Button(this).apply {
        text = getString(label); setTextColor(accent); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun openExternal(uri: Uri) {
        if (uri.scheme !in setOf("https", "http", "mailto")) return
        if (uri.host in setOf("127.0.0.1", "localhost", "::1")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (e: ActivityNotFoundException) { AlertDialog.Builder(this).setMessage(R.string.harness_no_browser).setPositiveButton(R.string.harness_done, null).show() }
    }
    // ---------------------------------------------------------------- 软件内更新
    private fun maybeAutoCheckUpdates() {
        val prefs = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(PREF_LAST_CHECK, 0L) < 3 * 60 * 60 * 1000L) return
        checkForUpdates(false)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateCheck?.isActive == true) return
        updateCheck = lifecycleScope.launch {
            try {
                val update = HarnessUpdater.check()
                getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit()
                    .putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
                if (update == null) {
                    if (manual) {
                        AlertDialog.Builder(this@HarnessActivity)
                            .setMessage(getString(R.string.harness_update_latest, HarnessUpdater.currentVersionName))
                            .setPositiveButton(R.string.harness_done, null).show()
                    }
                    return@launch
                }
                showUpdateDialog(update, manual)
            } catch (error: Exception) {
                if (manual) {
                    AlertDialog.Builder(this@HarnessActivity)
                        .setMessage(getString(R.string.harness_update_failed, error.message ?: error.javaClass.simpleName))
                        .setPositiveButton(R.string.harness_done, null).show()
                }
            }
        }
    }

    private fun showUpdateDialog(update: HarnessUpdate, manual: Boolean) {
        val prefs = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE)
        if (!manual && prefs.getInt(PREF_SKIPPED, 0) == update.versionCode) return
        val message = buildString {
            if (update.notes.isNotEmpty()) append(update.notes).append("\n\n")
            append(getString(R.string.harness_update_message, HarnessUpdater.currentVersionName))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.harness_update_found_title, update.versionName))
            .setMessage(message)
            .setPositiveButton(R.string.harness_update_download) { _, _ -> downloadUpdate(update) }
            .setNeutralButton(R.string.harness_update_page) { _, _ ->
                openExternal(Uri.parse(HarnessUpdater.RELEASES_PAGE))
            }
            .setNegativeButton(R.string.harness_update_later) { _, _ ->
                if (!manual) prefs.edit().putInt(PREF_SKIPPED, update.versionCode).apply()
            }
            .show()
    }

    private fun downloadUpdate(update: HarnessUpdate) {
        if (updateDownload?.isActive == true) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(4))
        }
        val progressText = TextView(this).apply {
            text = getString(R.string.harness_update_downloading, 0)
            textSize = 14f
            setTextColor(ink)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
        }
        content.addView(progressText)
        content.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.harness_update_found_title, update.versionName))
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.harness_cancel, null)
            .create()
        dialog.setOnDismissListener { updateDownload?.cancel() }
        dialog.show()
        updateDownload = lifecycleScope.launch {
            try {
                val file = HarnessUpdater.download(this@HarnessActivity, update) { percent ->
                    runOnUiThread {
                        bar.progress = percent
                        progressText.text = getString(R.string.harness_update_downloading, percent)
                    }
                }
                dialog.setOnDismissListener(null)
                dialog.dismiss()
                pendingUpdate = file
                updatePermissionAsked = false
                installPendingUpdate()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
                throw cancelled
            } catch (error: Exception) {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
                AlertDialog.Builder(this@HarnessActivity)
                    .setMessage(getString(R.string.harness_update_failed, error.message ?: error.javaClass.simpleName))
                    .setPositiveButton(R.string.harness_done, null).show()
            }
        }
    }

    private fun installPendingUpdate() {
        val apk = pendingUpdate ?: return
        if (!apk.isFile) {
            pendingUpdate = null
            return
        }
        if (!HarnessUpdater.canInstall(this)) {
            if (updatePermissionAsked) return
            updatePermissionAsked = true
            AlertDialog.Builder(this)
                .setMessage(R.string.harness_update_need_permission)
                .setPositiveButton(R.string.harness_update_grant) { _, _ -> HarnessUpdater.requestInstallPermission(this) }
                .setNegativeButton(R.string.harness_cancel, null)
                .show()
            return
        }
        pendingUpdate = null
        try {
            HarnessUpdater.install(this, apk)
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.harness_update_failed, error.message.orEmpty()))
                .setPositiveButton(R.string.harness_done, null).show()
        }
    }

    override fun onDestroy() {
        nativePanel?.dismiss()
        fileCallback?.onReceiveValue(null)
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val UPDATE_PREFS = "harness-update"
        const val PREF_LAST_CHECK = "lastCheck"
        const val PREF_SKIPPED = "skipped"
    }
}
