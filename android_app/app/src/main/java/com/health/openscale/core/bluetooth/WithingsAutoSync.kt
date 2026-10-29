/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.facade.BluetoothFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** An OS-owned filtered scan wakes the app only when the preferred scale is advertising. */
@Singleton
class WithingsAutoSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsFacade,
) {
    companion object {
        const val PREFIX = "ble/WithingsBodySmartHandler/"
        const val ENABLED = PREFIX + "automaticSync"
        const val ACTION = "com.health.openscale.WITHINGS_ADVERTISEMENT"
        const val WORK = "withings-weigh-in"
        fun isBodySmart(name: String) = Regex("Body Smart(?: [0-9a-f]{2})?", RegexOption.IGNORE_CASE).matches(name)
        fun hasPermissions(context: Context) = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var registered: Pair<String, String>? = null
    private var initialized = false
    private val callback: PendingIntent get() = PendingIntent.getBroadcast(context, 701,
        Intent(context, WithingsAdvertisementReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    fun start() {
        if (observer != null) return
        observer = scope.launch {
            combine(settings.observeSavedDevice(), settings.observeSetting(ENABLED, 1), settings.developerModeEnabled) { device, enabled, debug ->
                device?.takeIf { enabled == 1 && !debug && isBodySmart(it.name) }?.let { it.address to it.name }
            }.distinctUntilChanged().collect { apply(it) }
        }
    }

    fun refresh(force: Boolean = false): Job = scope.launch {
        val device = settings.observeSavedDevice().first()
        val enabled = settings.observeSetting(ENABLED, 1).first() == 1 && !settings.developerModeEnabled.first()
        apply(device?.takeIf { enabled && isBodySmart(it.name) }?.let { it.address to it.name }, force = force)
    }

    @SuppressLint("MissingPermission")
    private fun apply(target: Pair<String, String>?, force: Boolean = false) {
        if (!hasPermissions(context)) {
            WithingsListeningService.ensure(context, false)
            registered = null
            return
        }
        WithingsListeningService.ensure(context, target != null)
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) { registered = null; initialized = false; return }
        if (!force && initialized && registered == target) return
        try {
            scanner.stopScan(callback)
            initialized = true
            registered = null
            if (target == null || !BluetoothAdapter.checkBluetoothAddress(target.first)) return
            // The advertised radio address can change after reboot; the saved device name
            // provides a second filter. The receiver and worker recheck the selected scale.
            val filters = listOf(ScanFilter.Builder().setDeviceAddress(target.first).build(),
                ScanFilter.Builder().setDeviceName(target.second).build())
            val result = scanner.startScan(filters, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)
            if (result == 0) registered = target
            else LogManager.w("WithingsAutoSync", "Could not register background scan: $result")
        } catch (_: SecurityException) {
            registered = null
        } catch (e: IllegalStateException) {
            registered = null
            LogManager.w("WithingsAutoSync", "Bluetooth changed while registering scan", e)
        }
    }
}

@AndroidEntryPoint
class WithingsAdvertisementReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsFacade

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WithingsAutoSync.ACTION || !WithingsAutoSync.hasPermissions(context)) return
        if (intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0) != 0) return
        @Suppress("DEPRECATION")
        val results = intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val saved = settings.observeSavedDevice().first() ?: return@launch
                if (!WithingsAutoSync.isBodySmart(saved.name) || settings.developerModeEnabled.first() ||
                    settings.observeSetting(WithingsAutoSync.ENABLED, 1).first() != 1) return@launch
                val result = results.firstOrNull {
                    it.device.address.equals(saved.address, true) || it.scanRecord?.deviceName == saved.name
                } ?: return@launch
                // Several advertisements can describe the same weigh-in. Keep one transfer
                // in flight so competing workers do not open concurrent GATT sessions.
                WorkManager.getInstance(context).enqueueUniqueWork(WithingsAutoSync.WORK, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<WithingsSyncWorker>()
                        .setInputData(workDataOf("savedAddress" to saved.address, "address" to result.device.address,
                            "seenAt" to System.currentTimeMillis()))
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
                    .result.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                LogManager.w("WithingsAutoSync", "Could not schedule weigh-in sync", e)
            } finally { pending.finish() }
        }
    }
}

/** Restore the filtered scan after boot, Bluetooth enable or an app update. */
@AndroidEntryPoint
class WithingsScanRestoreReceiver : BroadcastReceiver() {
    @Inject lateinit var watcher: WithingsAutoSync
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                BluetoothAdapter.ACTION_STATE_CHANGED)) return
        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) return
        val pending = goAsync()
        watcher.refresh(force = true).invokeOnCompletion { pending.finish() }
    }
}

@HiltWorker
class WithingsSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val settings: SettingsFacade,
    private val bluetooth: BluetoothFacade,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val savedAddress = inputData.getString("savedAddress") ?: return Result.failure()
        val address = inputData.getString("address") ?: return Result.failure()
        val now = System.currentTimeMillis()
        // Stale work must not keep trying to connect to a sleeping scale.
        if (now - inputData.getLong("seenAt", 0) !in 0..45_000 || !WithingsAutoSync.hasPermissions(applicationContext)) return Result.success()
        if (settings.observeSetting(WithingsAutoSync.ENABLED, 1).first() != 1 || settings.developerModeEnabled.first()) return Result.success()
        val lastKey = WithingsAutoSync.PREFIX + "lastAttempt/$savedAddress"
        val last = settings.observeSetting(lastKey, 0L).first()
        if (now - last in 0..60_000) return Result.success()
        val started = bluetooth.connectToObservedBodySmart(savedAddress, address)
        if (!started) return Result.success()
        settings.saveSetting(lastKey, now)
        try {
            withTimeoutOrNull(50_000) {
                bluetooth.connectionStatus.first { it == ConnectionStatus.DISCONNECTED || it == ConnectionStatus.FAILED }
            }
        } finally {
            // Never interrupt a different connection that the user started in the meantime.
            if (bluetooth.connectedDeviceAddress.value == address && bluetooth.connectionStatus.value in
                setOf(ConnectionStatus.CONNECTING, ConnectionStatus.CONNECTED)) bluetooth.disconnect()
        }
        return Result.success() // A new advertisement triggers a new attempt; no blind retries.
    }
}
