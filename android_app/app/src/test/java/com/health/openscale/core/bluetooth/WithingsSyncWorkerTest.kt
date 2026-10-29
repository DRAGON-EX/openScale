/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.facade.BluetoothFacade
import com.health.openscale.core.facade.SettingsFacadeImpl
import com.health.openscale.testutil.FakeBluetoothFacade
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WithingsSyncWorkerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = SettingsFacadeImpl(PreferenceDataStoreFactory.create(scope = scope,
        produceFile = { File(context.cacheDir, "withings-${System.nanoTime()}.preferences_pb") }))
    private val fake = FakeBluetoothFacade()
    private var attempts = 0
    private val bluetooth = object : BluetoothFacade by fake {
        override suspend fun connectToObservedBodySmart(savedAddress: String, observedAddress: String): Boolean {
            attempts++
            fake.connectionStatus.value = ConnectionStatus.DISCONNECTED
            return true
        }
    }
    @After fun tearDown() { scope.cancel() }
    private fun worker(seenAt: Long = System.currentTimeMillis()): WithingsSyncWorker {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        return TestListenableWorkerBuilder<WithingsSyncWorker>(context)
            .setInputData(workDataOf("savedAddress" to "AA:BB:CC:DD:EE:FF", "address" to "AA:BB:CC:DD:EE:FF", "seenAt" to seenAt))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    WithingsSyncWorker(appContext, workerParameters, settings, bluetooth)
            }).build()
    }
    @Test fun `one advertising burst connects once and keeps shared facade alive`() = runBlocking {
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        worker().doWork()
        assertThat(attempts).isEqualTo(1)
        assertThat(fake.closeCalled).isFalse()
        assertThat(fake.disconnectCalled).isFalse()
    }
    @Test fun `stale advertisements disabled scanning and developer mode cannot start reads`() = runBlocking {
        worker(System.currentTimeMillis() - 60_000).doWork()
        settings.saveSetting(WithingsAutoSync.ENABLED, 0)
        worker().doWork()
        settings.saveSetting(WithingsAutoSync.ENABLED, 1)
        settings.saveSetting("saved_bluetooth_developer_mode", true)
        worker().doWork()
        assertThat(attempts).isEqualTo(0)
    }
}
