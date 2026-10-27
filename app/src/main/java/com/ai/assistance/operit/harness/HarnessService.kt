package com.ai.assistance.operit.harness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.ai.assistance.operit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HarnessService : Service() {
    companion object {
        const val STOP = "com.ai.assistance.operit.harness.STOP"
        private val mutableState = MutableStateFlow(HarnessState())
        val state = mutableState.asStateFlow()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var runtime: HarnessRuntime? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("harness", getString(R.string.harness_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, HarnessActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, HarnessService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(7901, Notification.Builder(this, "harness").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.harness_name)).setContentText(getString(R.string.harness_background))
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, getString(R.string.harness_stop), stop).build()).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopping = true
            mutableState.value = mutableState.value.copy(stage = HarnessStartup.Stage.STOPPING, url = null, error = null)
            scope.launch {
                runtime?.stop()
                job?.cancelAndJoin()
                mutableState.value = HarnessState()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            stopping = false
            runtime = HarnessRuntime(this) { if (!stopping) mutableState.value = it }
            job = scope.launch {
                runtime!!.run()
                if (!stopping) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        runtime?.close()
        scope.cancel()
        super.onDestroy()
    }
}
