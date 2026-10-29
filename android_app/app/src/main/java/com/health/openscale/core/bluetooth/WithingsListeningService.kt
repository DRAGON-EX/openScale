/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.health.openscale.MainActivity
import com.health.openscale.R
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** Samsung freezes even PendingIntent BLE scans for cached apps. Keep the explicitly
 * enabled listener in connected-device foreground priority until the user disables it. */
@AndroidEntryPoint
class WithingsListeningService : Service() {
    companion object {
        private const val CHANNEL = "body-smart-sync"
        private const val STOP = "stop-body-smart-sync"
        @Volatile private var active = false
        fun ensure(context: Context, enabled: Boolean) {
            val intent = Intent(context, WithingsListeningService::class.java)
            if (!enabled) { context.stopService(intent); return }
            if (active) return
            try {
                active = true
                ContextCompat.startForegroundService(context, intent)
            } catch (e: RuntimeException) {
                active = false
                LogManager.w("WithingsAutoSync", "Listener can start when openScale is opened", e)
            }
        }
    }
    @Inject lateinit var settings: SettingsFacade
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        active = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.body_smart_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 702, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 703, Intent(this, WithingsListeningService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.body_smart_notification_title))
            .setContentText(getString(R.string.body_smart_notification_text))
            .setContentIntent(open).setOngoing(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null, getString(R.string.body_smart_notification_stop), stop).build())
            .build()
        try { startForeground(701, notification) }
        catch (_: SecurityException) { stopSelf(); return }
        scope.launch {
            combine(settings.observeSavedDevice(), settings.observeSetting(WithingsAutoSync.ENABLED, 1),
                settings.developerModeEnabled) { device, enabled, debug ->
                enabled == 1 && !debug && device != null && WithingsAutoSync.isBodySmart(device.name)
            }.distinctUntilChanged().collect { if (!it) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) scope.launch {
            settings.saveSetting(WithingsAutoSync.ENABLED, 0)
            stopSelf()
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        active = false
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
