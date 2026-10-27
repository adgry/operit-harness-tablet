package com.ai.assistance.operit.harness

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.core.tools.system.shower.OperitShowerShellRunner
import com.ai.assistance.operit.data.preferences.MemorySpaceProfileDocumentRepository
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.util.TextSegmenter
import com.ai.assistance.showerclient.ShowerEnvironment
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Required native tool prerequisites, without legacy agent or scheduling startup. */
object HarnessNativeInitialization {
    private val lock = Mutex()
    private var ready = false

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (ready) return@withLock
            val app = context.applicationContext
            // Await migration before creating a default space; launching this asynchronously races
            // the first memory operation on a clean Harness-only start.
            val preferences = UserPreferencesManager.getInstance(app)
            MemorySpaceProfileDocumentRepository.getInstance(app).initialize()
            preferences.ensureDefaultMemorySpace(app.getString(R.string.default_profile))
            AndroidShellExecutor.setContext(app)
            ShowerEnvironment.shellRunner = OperitShowerShellRunner
            PDFBoxResourceLoader.init(app)
            TextSegmenter.initialize(app)
            // Read-only workflow inspection never touches WorkflowRepository's lazy scheduler.
            // Do not initialize WorkManager or the old full application/lifecycle initializer:
            // those can restore persisted workflows, microphone services and legacy AI jobs.
            ready = true
        }
    }
}
