/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date

/** Exercises persistence acknowledgements through the real facade and Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleConnectorPersistenceTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var measurements: MeasurementFacade
    private lateinit var connector: BleConnector
    private var userId = 0
    private val device = ScannedDeviceInfo("Body Smart 32", "AA:BB:CC:DD:EE:01", -50, emptyList(), null)

    @Before fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())
        val settings = RoomTestSupport.settingsFacadeFor(scope,
            File(app.cacheDir, "ble-receipts-${System.nanoTime()}.preferences_pb"))
        val facades = RoomTestSupport.facadesFor(app, repo, settings)
        measurements = facades.measurementFacade
        userId = repo.insertUser(User(name = "Example", birthDate = 0L, gender = GenderType.MALE,
            heightCm = 175f, activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false)).toInt()
        connector = BleConnector(scope,
            ScaleFactory(app, settings, measurements, facades.userFacade), measurements) { userId }
    }

    @After fun tearDown() {
        connector.close()
        scope.cancel()
        db.close()
    }

    private fun reading() = ScaleMeasurement(userId = userId, dateTime = Date(1700000000000L)).apply {
        this[MeasurementType.WEIGHT] = Kg(70f)
    }

    @Test fun `receipt is acknowledged only after the values are saved even after disconnect`() = runBlocking {
        val receipt = CompletableDeferred<Int>()
        connector.handleBluetoothEvent(BluetoothEvent.MeasurementReceived(reading(), device.address) {
            val stored = measurements.getMeasurementsForUser(userId).first().single()
            val weightType = repo.getAllMeasurementTypes().first().single { it.key == MeasurementType.WEIGHT }
            assertThat(stored.values.single { it.value.typeId == weightType.id }.value.floatValue).isEqualTo(70f)
            receipt.complete(stored.measurement.id)
        }, device)
        connector.close() // A finished BLE session must not cancel pending database work.
        assertThat(withTimeout(5000) { receipt.await() }).isGreaterThan(0)
    }

    @Test fun `existing duplicate is acknowledged without inserting another row`() = runBlocking {
        repeat(2) {
            val receipt = CompletableDeferred<Unit>()
            connector.handleBluetoothEvent(BluetoothEvent.MeasurementReceived(reading(), device.address) {
                receipt.complete(Unit)
            }, device)
            withTimeout(5000) { receipt.await() }
        }
        assertThat(measurements.getMeasurementsForUser(userId).first()).hasSize(1)
    }

    @Test fun `a rejected database insert never acknowledges an import receipt`() = runBlocking {
        userId = Int.MAX_VALUE // No such user: Room must reject the foreign key.
        var acknowledged = false
        val failed = async(start = CoroutineStart.UNDISPATCHED) {
            connector.snackbarEvents.first { it.messageResId == R.string.bluetooth_connector_measurement_save_error }
        }
        connector.handleBluetoothEvent(BluetoothEvent.MeasurementReceived(reading(), device.address) {
            acknowledged = true
        }, device)
        withTimeout(5000) { failed.await() }
        assertThat(acknowledged).isFalse()
    }
}
