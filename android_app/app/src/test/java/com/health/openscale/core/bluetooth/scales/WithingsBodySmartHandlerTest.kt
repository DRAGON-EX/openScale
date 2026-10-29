/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.WithingsWppLib as Wpp
import com.health.openscale.core.bluetooth.libs.WithingsUserRoster as Roster
import com.health.openscale.core.bluetooth.libs.WithingsLocalSetup as LocalSetup
import com.health.openscale.core.bluetooth.libs.WithingsScreenConfig as ScreenConfig
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class WithingsBodySmartHandlerTest {
    private val now = 1793235000000L

    private class Settings : ScaleDeviceHandler.DriverSettings {
        val strings = mutableMapOf<String, String>()
        val ints = mutableMapOf<String, Int>()
        var failDurableWrites = false
        override suspend fun putStringAndAwait(key: String, value: String) {
            check(!failDurableWrites) { "Simulated unavailable storage" }
            putString(key, value)
        }
        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { strings.remove(key); ints.remove(key) }
    }

    private class Transport : ScaleDeviceHandler.Transport {
        val writes = mutableListOf<ByteArray>()
        val notifications = mutableListOf<Pair<UUID, UUID>>()
        var disconnected = false
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
        override fun setNotifyOn(service: UUID, characteristic: UUID) { notifications += service to characteristic }
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) { writes += payload.copyOf() }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() { disconnected = true }
    }

    private class Callbacks(private val scope: CoroutineScope) : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        val warnings = mutableListOf<Int>()
        var choices: IntArray? = null
        var autoSave = true
        val receipts = mutableListOf<suspend () -> Unit>()
        override fun onPublish(measurement: ScaleMeasurement) { published += measurement.snapshot() }
        override fun onPublish(measurement: ScaleMeasurement, onSaved: suspend () -> Unit) {
            onPublish(measurement)
            receipts += onSaved
            if (autoSave) scope.launch(start = CoroutineStart.UNDISPATCHED) { onSaved() }
        }
        override fun onWarn(resId: Int, vararg args: Any) { warnings += resId }
        override fun onUserInteractionRequired(interactionType: UserInteractionType, data: Any?) {
            @Suppress("UNCHECKED_CAST")
            choices = (data as Pair<Array<CharSequence>, IntArray>).second
        }
        override fun resolveString(resId: Int, vararg args: Any) = "res:$resId ${args.joinToString()}"
    }

    private inner class Fixture(scope: CoroutineScope, val settings: Settings = Settings(), target: String? = null, appUserId: Int = 1) {
        val handler = WithingsBodySmartHandler { now }
        val transport = Transport()
        val callbacks = Callbacks(scope)
        val user = ScaleUser(id = appUserId)
        init {
            target?.let(handler::bindDevice)
            val data = object : ScaleDeviceHandler.DataProvider {
                override fun currentUser() = user
                override fun usersForDevice() = listOf(user)
                override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
            }
            handler.attach(transport, callbacks, settings, data, scope)
            handler.handleConnected(user)
        }
        fun receive(command: Int, vararg objects: Wpp.Tlv) {
            handler.handleNotification(WithingsBodySmartHandler.CHARACTERISTIC, Wpp.encode(command, *objects))
        }
        fun beginRecords() {
            receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
            receive(Wpp.TIME_SET)
            receive(Wpp.STORED_MEASURE, Wpp.Tlv(0x0115, hex("00030000000000")))
        }
        fun end() = receive(Wpp.STORED_MEASURE, Wpp.Tlv(Wpp.NULL, byteArrayOf()))
        fun syncDone() { receive(Wpp.SYNC_OK); receive(Wpp.DISCONNECT) }
        suspend fun select(index: Int, appUserId: Int = user.id) {
            handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, appUserId, index)
            if (appUserId == user.id) syncDone()
        }
    }

    private fun meta(user: Long, time: Long = 1700000000L): Wpp.Tlv = Wpp.Tlv(Wpp.META,
        ByteBuffer.allocate(23).apply {
            putInt(1); put(if (user == 0L) 0 else 1); put(3)
            putInt(user.toInt()); putInt(99); putInt(100); put(0); putInt(time.toInt())
        }.array())

    private fun value(type: Int, raw: Int, exponent: Int = 0) = Wpp.Tlv(Wpp.DATA,
        ByteBuffer.allocate(8).putInt(raw).putShort(type.toShort()).putShort(exponent.toShort()).array())

    @Test fun `matches Body Smart names without claiming other Withings products`() {
        val handler = WithingsBodySmartHandler()
        assertThat(handler.supportFor(device("Body Smart 32"))).isNotNull()
        assertThat(handler.supportFor(device("body smart AF"))).isNotNull()
        assertThat(handler.supportFor(device("Body Smart"))).isNotNull()
        assertThat(handler.supportFor(device("Body Scan"))).isNull()
        assertThat(handler.supportFor(device("Body+ 3C"))).isNull()
        val support = handler.supportFor(device("Body Smart 32"))!!
        assertThat(support.implemented).contains(DeviceCapability.USER_SYNC)
        assertThat(support.hasUserSettings).isTrue()
        assertThat(handler.logRawPackets).isFalse()
    }

    @Test fun `reads use only the verified notification characteristic and read commands`() = runTest {
        val f = Fixture(this)
        f.beginRecords()
        assertThat(f.transport.notifications).containsExactly(WithingsBodySmartHandler.SERVICE to WithingsBodySmartHandler.CHARACTERISTIC)
        assertThat(f.transport.writes.flatMap { Wpp.Decoder().feed(it) }.map { it.command }).containsExactly(
            Wpp.PROBE, Wpp.TIME_SET, Wpp.STORED_MEASURE, Wpp.STORED_MEASURE).inOrder()
        f.handler.handleDisconnected()
    }

    @Test fun `fragmented records publish only after complete transfer and explicit user choice`() = runTest {
        val f = Fixture(this)
        f.beginRecords()
        f.receive(Wpp.STORED_MEASURE, meta(0), value(1, 14000, -3), meta(17), value(1, 70000, -3), value(8, 1400, -2))
        val remainder = Wpp.encode(Wpp.STORED_MEASURE,
            value(5, 5600, -2), value(76, 5250, -2), value(77, 3500, -2), value(88, 350, -2),
            value(170, 33, -1), value(11, 65), value(201, -61, -3))
        remainder.asList().chunked(3).forEach {
            f.handler.handleNotification(WithingsBodySmartHandler.CHARACTERISTIC, it.toByteArray())
        }
        assertThat(f.callbacks.published).isEmpty()
        f.end()
        assertThat(f.callbacks.published).isEmpty()
        assertThat(f.callbacks.choices!!.toList()).containsExactly(0, 1).inOrder()
        f.select(1, appUserId = 2) // a different active app profile must not receive this transfer
        assertThat(f.callbacks.published).isEmpty()
        f.select(1)
        val m = f.callbacks.published.single()
        assertThat(m.userId).isEqualTo(1)
        assertThat(m.dateTime!!.time).isEqualTo(1700000000000L)
        assertThat(m[MeasurementType.WEIGHT]!!.value).isEqualTo(70f)
        assertThat(m[MeasurementType.BODY_FAT]!!.value).isEqualTo(20f)
        assertThat(m[MeasurementType.MUSCLE]!!.value).isEqualTo(75f)
        assertThat(m[MeasurementType.WATER]!!.value).isEqualTo(50f)
        assertThat(m[MeasurementType.LBM]!!.value).isEqualTo(56f)
        assertThat(m[MeasurementType.BONE]!!.value).isEqualTo(3.5f)
        assertThat(m[MeasurementType.HEART_RATE]!!.value).isEqualTo(65)
        assertThat(m[MeasurementType.VISCERAL_FAT]).isEqualTo(3.3f)
        assertThat(f.transport.disconnected).isTrue()
    }

    @Test fun `unset clock never silently imports old readings as today`() = runTest {
        val f = Fixture(this)
        f.beginRecords()
        f.receive(Wpp.STORED_MEASURE, meta(17, 978307243), value(1, 70000, -3))
        f.end(); f.select(0)
        assertThat(f.callbacks.published).isEmpty()
        assertThat(f.callbacks.warnings).contains(R.string.body_smart_clock_unset)
    }

    @Test fun `explicit undated option imports only newest reading and deduplicates later sessions`() = runTest {
        val settings = Settings().apply { ints[WithingsBodySmartHandler.USE_CURRENT_DATE] = 1 }
        repeat(2) { attempt ->
            val f = Fixture(this, settings)
            f.beginRecords()
            f.receive(Wpp.STORED_MEASURE, meta(17, 978307220), value(1, 70000, -3), meta(17, 978307243), value(1, 71000, -3))
            f.end()
            if (attempt == 0) f.select(0)
            assertThat(f.callbacks.published).hasSize(if (attempt == 0) 1 else 0)
            if (attempt == 0) {
                assertThat(f.callbacks.published.single()[MeasurementType.WEIGHT]!!.value).isEqualTo(71f)
                assertThat(f.callbacks.published.single().dateTime!!.time).isEqualTo(now)
            }
        }
    }

    @Test fun `challenge and malformed transfer fail without importing or guessing a key`() = runTest {
        val challenge = Fixture(this)
        challenge.receive(Wpp.CHALLENGE, Wpp.Tlv(0x0122, ByteArray(18)))
        assertThat(challenge.callbacks.published).isEmpty()
        assertThat(challenge.callbacks.warnings).contains(R.string.body_smart_key_required)
        assertThat(challenge.transport.writes).hasSize(1)

        val malformed = Fixture(this)
        malformed.beginRecords()
        malformed.receive(Wpp.STORED_MEASURE, meta(17), value(1, 70000, -3))
        malformed.receive(Wpp.STORED_MEASURE, Wpp.Tlv(Wpp.META, byteArrayOf()))
        malformed.end()
        assertThat(malformed.callbacks.published).isEmpty()
        assertThat(malformed.callbacks.warnings).contains(R.string.body_smart_invalid_response)
    }

    @Test fun `empty store completes sync and disconnects without deleting measurements`() = runTest {
        val f = Fixture(this)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        f.receive(Wpp.TIME_SET)
        f.receive(Wpp.STORED_MEASURE, Wpp.Tlv(0x0115, hex("00000000000000")))
        assertThat(f.transport.writes.last()).isEqualTo(Wpp.encode(Wpp.SYNC_OK))
        assertThat(f.transport.disconnected).isFalse()
        f.syncDone()
        assertThat(f.transport.writes).hasSize(5)
        assertThat(f.transport.disconnected).isTrue()
        assertThat(f.callbacks.published).isEmpty()
    }

    private val rosterTarget = "AA:BB:CC:DD:EE:01"
    private val roster = listOf(Roster.Profile(17, "Example", "EXA", 70.0, 180.0, 30.0, 0))
    private fun approvedSettings(approval: String = Roster.approval(rosterTarget, roster, now)): Settings = Settings().apply {
        strings["roster/approved/$rosterTarget"] = approval
    }

    @Test fun `approved roster uses a separate consumed once write transaction`() = runTest {
        val settings = approvedSettings()
        val f = Fixture(this, settings, rosterTarget)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        runCurrent()
        assertThat(settings.strings["roster/approved/$rosterTarget"]).isEmpty()
        advanceTimeBy(10_000); runCurrent()
        val frames = f.transport.writes.drop(1).flatMap { Wpp.Decoder().feed(it) }
        assertThat(frames).isNotEmpty()
        assertThat(frames.map { it.command }.toSet()).containsExactly(Roster.COMMAND)
        assertThat(frames.last().objects.last().type).isEqualTo(Wpp.NULL)
        assertThat(f.callbacks.published).isEmpty()
        f.receive(Roster.COMMAND)
        runCurrent()
        assertThat(settings.strings["roster/result/$rosterTarget"]).isEqualTo("profiles-acknowledged")
        val screenFrame = Wpp.Decoder().feed(f.transport.writes.last()).single()
        assertThat(screenFrame.command).isEqualTo(ScreenConfig.COMMAND)
        assertThat(screenFrame.objects.count { it.type == ScreenConfig.SCREEN }).isEqualTo(5)
        f.receive(ScreenConfig.COMMAND)
        runCurrent()
        f.syncDone()
        assertThat(settings.strings["roster/result/$rosterTarget"]).isEqualTo("acknowledged")
        assertThat(f.transport.disconnected).isTrue()

        val second = Fixture(this, settings, rosterTarget)
        second.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        assertThat(Wpp.Decoder().feed(second.transport.writes.last()).single().command).isEqualTo(Wpp.TIME_SET)
        second.handler.handleDisconnected()
    }

    @Test fun `wrong target and stale roster approvals send no profile data`() = runTest {
        val approvals = listOf(
            Roster.approval("AA:BB:CC:DD:EE:02", roster, now),
            Roster.approval(rosterTarget, roster, now - 600_001))
        for (approval in approvals) {
            val f = Fixture(this, approvedSettings(approval), rosterTarget)
            f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
            runCurrent()
            assertThat(f.transport.writes).hasSize(1)
            assertThat(f.callbacks.warnings).contains(R.string.body_smart_roster_invalid_approval)
        }
    }

    @Test fun `rejection stops remaining chunks and never retries on reconnect`() = runTest {
        val settings = approvedSettings()
        val f = Fixture(this, settings, rosterTarget)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        runCurrent()
        val written = f.transport.writes.size
        assertThat(written).isEqualTo(2) // probe and first configuration chunk
        f.receive(Wpp.ERROR, Wpp.Tlv(0x0110, hex("010dfffffffd")))
        advanceTimeBy(5_000); runCurrent()
        assertThat(f.transport.writes).hasSize(written)
        assertThat(settings.strings["roster/approved/$rosterTarget"]).isEmpty()
        assertThat(settings.strings["roster/result/$rosterTarget"]).isEqualTo("rejected")
        assertThat(f.callbacks.published).isEmpty()
    }

    @Test fun `unacknowledged roster transfer times out without automatic retry`() = runTest {
        val settings = approvedSettings()
        val f = Fixture(this, settings, rosterTarget)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        advanceTimeBy(30_000); runCurrent()
        assertThat(settings.strings["roster/approved/$rosterTarget"]).isEmpty()
        assertThat(settings.strings["roster/result/$rosterTarget"]).isEqualTo("timeout")
        assertThat(f.callbacks.warnings).contains(R.string.body_smart_roster_timeout)
        assertThat(f.transport.disconnected).isTrue()
    }
    @Test fun `normal weigh-in challenge authenticates before clock or measurement commands`() = runTest {
        val credentials = LocalSetup.create()
        val settings = Settings().apply { strings["local/credentials/unknown"] = credentials.encode() }
        // The handler uppercases its fallback address just like a real Bluetooth address.
        settings.strings["local/credentials/UNKNOWN"] = credentials.encode()
        val f = Fixture(this, settings)
        val mac = "aa:bb:cc:dd:ee:ff"
        val nonce = ByteArray(16) { it.toByte() }
        f.receive(Wpp.CHALLENGE, Wpp.Tlv(0x0122, byteArrayOf(17) + mac.toByteArray() + byteArrayOf(16) + nonce))
        val request = Wpp.Decoder().feed(f.transport.writes.last()).single()
        assertThat(request.command).isEqualTo(Wpp.CHALLENGE)
        val ours = Wpp.challenge(request)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()),
            Wpp.Tlv(0x0123, byteArrayOf(20) + Wpp.answer(ours.nonce, mac, credentials.key)))
        assertThat(Wpp.Decoder().feed(f.transport.writes.last()).single().command).isEqualTo(Wpp.TIME_SET)
        f.handler.handleDisconnected()
    }

    @Test fun `local setup persists recovery keys and consumes approval before sending once`() = runTest {
        val credentials = LocalSetup.create()
        val settings = Settings().apply {
            strings["local/approved/$rosterTarget"] = LocalSetup.approval(rosterTarget, credentials, now)
        }
        val f = Fixture(this, settings, rosterTarget)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        assertThat(f.transport.writes).hasSize(1)
        runCurrent()
        assertThat(settings.strings["local/approved/$rosterTarget"]).isEmpty()
        assertThat(settings.strings["local/credentials/$rosterTarget"]).isEqualTo(credentials.encode())
        assertThat(f.transport.writes.last()).isEqualTo(credentials.packet())
        f.receive(Wpp.ASSOCIATION_KEYS_SET); f.receive(Wpp.TIME_SET); f.receive(Wpp.SYNC_OK)
        assertThat(f.transport.writes.last()).isEqualTo(Wpp.encode(Wpp.SETUP_OK))
        f.receive(Wpp.SETUP_OK); f.receive(Wpp.DISCONNECT)
        assertThat(f.callbacks.published).isEmpty()
        assertThat(f.transport.disconnected).isTrue()
        val second = Fixture(this, settings, rosterTarget)
        second.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf())); runCurrent()
        assertThat(second.transport.writes.flatMap { Wpp.Decoder().feed(it) }.map { it.command }).doesNotContain(Wpp.ASSOCIATION_KEYS_SET)
        second.handler.handleDisconnected()
    }

    @Test fun `key setup cannot start when durable storage is unavailable or approval is wrong`() = runTest {
        val credentials = LocalSetup.create()
        for (wrongTarget in listOf(false, true)) {
            val settings = Settings().apply {
                failDurableWrites = !wrongTarget
                strings["local/approved/$rosterTarget"] = LocalSetup.approval(if (wrongTarget) "OTHER" else rosterTarget, credentials, now)
            }
            val f = Fixture(this, settings, rosterTarget)
            f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf())); runCurrent()
            assertThat(f.transport.writes).hasSize(1)
            assertThat(f.transport.disconnected).isTrue()
        }
    }

    @Test fun `an unauthenticated name match cannot redirect approved writes to another radio`() = runTest {
        val otherAddress = "AA:BB:CC:DD:EE:02"
        for (keySetup in listOf(true, false)) {
            val settings = if (keySetup) Settings().apply {
                strings["local/approved/$rosterTarget"] = LocalSetup.approval(rosterTarget, LocalSetup.create(), now)
            } else approvedSettings()
            settings.strings["alias/$otherAddress"] = rosterTarget
            val f = Fixture(this, settings, otherAddress)
            f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf())); runCurrent()
            assertThat(f.transport.writes).hasSize(1)
            assertThat(f.transport.disconnected).isTrue()
        }
    }

    @Test fun `early roster acknowledgement cannot truncate the replacement and normal sync is completed`() = runTest {
        val f = Fixture(this, approvedSettings(), rosterTarget)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf())); runCurrent()
        f.receive(Roster.COMMAND)
        assertThat(f.transport.disconnected).isFalse()
        advanceTimeBy(10_000); runCurrent()
        val rosterFrames = f.transport.writes.flatMap { Wpp.Decoder().feed(it) }.filter { it.command == Roster.COMMAND }
        assertThat(rosterFrames.last().objects.last().type).isEqualTo(Wpp.NULL)
        f.receive(Roster.COMMAND)
        runCurrent()
        f.receive(ScreenConfig.COMMAND)
        runCurrent()
        assertThat(f.transport.writes.last()).isEqualTo(Wpp.encode(Wpp.SYNC_OK))
        f.syncDone()
        assertThat(f.transport.disconnected).isTrue()
    }

    @Test fun `editing an existing scale profile retains its app user mapping`() = runTest {
        val settings = approvedSettings().apply { strings["user/$rosterTarget/1"] = "17" }
        val f = Fixture(this, settings, rosterTarget)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        advanceTimeBy(10_000); runCurrent()
        f.receive(Roster.COMMAND); runCurrent()
        f.receive(ScreenConfig.COMMAND); runCurrent(); f.syncDone()
        assertThat(settings.strings["user/$rosterTarget/1"]).isEqualTo("17")
    }

    @Test fun `multi profile sync waits for the final screen frame before accepting acknowledgement`() = runTest {
        val profiles = List(8) { roster.single().copy(id = it + 1L) }
        val settings = approvedSettings(Roster.approval(rosterTarget, profiles, now))
        val f = Fixture(this, settings, rosterTarget)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        advanceTimeBy(10_000); runCurrent()
        f.receive(Roster.COMMAND); runCurrent()
        f.receive(ScreenConfig.COMMAND); runCurrent() // before all 40 screen records have been sent
        assertThat(settings.strings["roster/result/$rosterTarget"]).isEqualTo("profiles-acknowledged")
        advanceTimeBy(5_000); runCurrent()
        val objects = f.transport.writes.flatMap { Wpp.Decoder().feed(it) }
            .filter { it.command == ScreenConfig.COMMAND }.flatMap { it.objects }
        assertThat(objects.count { it.type == ScreenConfig.SCREEN }).isEqualTo(40)
        assertThat(objects.last().type).isEqualTo(Wpp.NULL)
        f.receive(ScreenConfig.COMMAND); runCurrent(); f.syncDone()
        assertThat(settings.strings["roster/result/$rosterTarget"]).isEqualTo("acknowledged")
    }

    @Test fun `screen rejection reports incomplete user sync and never replays a consumed roster`() = runTest {
        val settings = approvedSettings()
        val f = Fixture(this, settings, rosterTarget)
        f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        advanceTimeBy(10_000); runCurrent()
        f.receive(Roster.COMMAND); runCurrent()
        f.receive(Wpp.ERROR)
        assertThat(settings.strings["roster/result/$rosterTarget"]).isEqualTo("screens-rejected")
        assertThat(f.callbacks.warnings).contains(R.string.body_smart_screens_incomplete)
        assertThat(f.transport.disconnected).isTrue()
        val second = Fixture(this, settings, rosterTarget)
        second.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
        assertThat(second.transport.writes.map { Wpp.Decoder().feed(it).single().command })
            .containsExactly(Wpp.PROBE, Wpp.TIME_SET).inOrder()
        second.handler.handleDisconnected()
    }

    @Test fun `all documented scale-reported metabolic and composition fields retain their units`() = runTest {
        val f = Fixture(this)
        f.beginRecords()
        f.receive(Wpp.STORED_MEASURE, meta(17), value(1, 80000, -3), value(6, 25),
            value(8, 20000, -3), value(5, 60000, -3), value(76, 56000, -3),
            value(77, 40000, -3), value(88, 4000, -3), value(170, 35, -1),
            value(11, 72), value(226, 1800), value(227, 32), value(189, 123))
        f.end(); f.select(0)
        val m = f.callbacks.published.single()
        assertThat(m[MeasurementType.BODY_FAT]!!.value).isEqualTo(25f)
        assertThat(m[MeasurementType.WATER]!!.value).isEqualTo(50f)
        assertThat(m[MeasurementType.MUSCLE]!!.value).isEqualTo(70f)
        assertThat(m[MeasurementType.LBM]!!.value).isEqualTo(60f)
        assertThat(m[MeasurementType.BONE]!!.value).isEqualTo(4f)
        assertThat(m[MeasurementType.VISCERAL_FAT]).isEqualTo(3.5f)
        assertThat(m[MeasurementType.HEART_RATE]!!.value).isEqualTo(72)
        assertThat(m[MeasurementType.BMR]!!.value).isEqualTo(1800f)
        assertThat(m[WithingsBodySmartHandler.METABOLIC_AGE]).isEqualTo(32f)
    }

    @Test fun `interrupted user sync records which stage was not confirmed`() = runTest {
        for (profilesAcknowledged in listOf(false, true)) {
            val settings = approvedSettings()
            val f = Fixture(this, settings, rosterTarget)
            f.receive(Wpp.PROBE, Wpp.Tlv(0x0101, byteArrayOf()))
            advanceTimeBy(10_000); runCurrent()
            if (profilesAcknowledged) { f.receive(Roster.COMMAND); runCurrent() }
            f.handler.handleDisconnected()
            assertThat(settings.strings["roster/result/$rosterTarget"])
                .isEqualTo(if (profilesAcknowledged) "screens-incomplete" else "incomplete")
            assertThat(settings.strings["roster/approved/$rosterTarget"]).isEmpty()
        }
    }

    @Test fun `first sync imports the newest complete reading instead of lifetime history`() = runTest {
        val f = Fixture(this)
        f.beginRecords()
        f.receive(Wpp.STORED_MEASURE,
            meta(17, 1700000300), value(1, 73000, -3), value(6, 21),
            meta(17, 1700000100), value(1, 71000, -3),
            meta(17, 1700000200), value(1, 72000, -3),
            meta(17, 1700000300), value(11, 65))
        f.end(); f.select(0)
        val saved = f.callbacks.published.single()
        assertThat(saved.dateTime!!.time).isEqualTo(1700000300000L)
        assertThat(saved[MeasurementType.BODY_FAT]!!.value).isEqualTo(21f)
        assertThat(saved[MeasurementType.HEART_RATE]!!.value).isEqualTo(65)
    }

    @Test fun `deleted phone readings stay deleted and later offline readings each import once`() = runTest {
        val settings = Settings()
        val first = Fixture(this, settings)
        first.beginRecords()
        first.receive(Wpp.STORED_MEASURE, meta(17, 1700000000), value(1, 70000, -3))
        first.end(); first.select(0)
        assertThat(first.callbacks.published).hasSize(1)
        // A new handler has no saved phone measurements; its persistent receipts survive deletion.
        first.callbacks.published.clear()
        repeat(2) { attempt ->
            val next = Fixture(this, settings)
            next.beginRecords()
            next.receive(Wpp.STORED_MEASURE,
                meta(17, 1700000200), value(1, 72000, -3),
                meta(17, 1700000000), value(1, 70000, -3),
                meta(17, 1699999900), value(1, 69000, -3),
                meta(17, 1700000100), value(1, 71000, -3))
            next.end(); next.syncDone()
            assertThat(next.callbacks.published.map { it.dateTime!!.time }).containsExactlyElementsIn(
                if (attempt == 0) listOf(1700000100000L, 1700000200000L) else emptyList())
        }
    }

    @Test fun `a failed older save retries even when a newer save completed after disconnect`() = runTest {
        val settings = Settings()
        val first = Fixture(this, settings)
        first.callbacks.autoSave = false
        first.beginRecords()
        first.receive(Wpp.STORED_MEASURE, meta(17, 1700000000), value(1, 70000, -3))
        first.end(); first.select(0)
        assertThat(settings.strings.keys.none { "/saved/" in it }).isTrue()
        val second = Fixture(this, settings)
        second.callbacks.autoSave = false
        second.beginRecords()
        second.receive(Wpp.STORED_MEASURE,
            meta(17, 1700000000), value(1, 70000, -3), meta(17, 1700000100), value(1, 71000, -3))
        second.end(); second.syncDone(); second.handler.detach()
        assertThat(second.callbacks.published).hasSize(2)
        second.callbacks.receipts[1]() // The database saved the newer row, but failed the older one.
        val retry = Fixture(this, settings)
        retry.beginRecords()
        retry.receive(Wpp.STORED_MEASURE,
            meta(17, 1700000000), value(1, 70000, -3), meta(17, 1700000100), value(1, 71000, -3))
        retry.end(); retry.syncDone()
        assertThat(retry.callbacks.published.single().dateTime!!.time).isEqualTo(1700000000000L)
    }

    @Test fun `sync receipts remain independent for different scales and app users`() = runTest {
        val settings = Settings()
        for ((address, appUser) in listOf(rosterTarget to 1, "AA:BB:CC:DD:EE:02" to 1, rosterTarget to 2)) {
            val f = Fixture(this, settings, address, appUser)
            f.beginRecords()
            f.receive(Wpp.STORED_MEASURE, meta(17), value(1, 70000, -3))
            f.end(); f.select(0)
            assertThat(f.callbacks.published).hasSize(1)
        }
        assertThat(settings.strings.keys.count { "/saved/" in it }).isEqualTo(3)
    }

    @Test fun `failed durable initialization does not publish or discard the latest reading`() = runTest {
        val settings = Settings().apply { failDurableWrites = true }
        val failed = Fixture(this, settings)
        failed.beginRecords()
        failed.receive(Wpp.STORED_MEASURE, meta(17), value(1, 70000, -3))
        failed.end(); failed.select(0)
        assertThat(failed.callbacks.published).isEmpty()
        assertThat(failed.callbacks.warnings).contains(R.string.body_smart_import_failed)
        assertThat(settings.strings.keys.none { it.startsWith("history/") }).isTrue()
        settings.failDurableWrites = false
        val retry = Fixture(this, settings)
        retry.beginRecords()
        retry.receive(Wpp.STORED_MEASURE, meta(17), value(1, 70000, -3))
        retry.end(); retry.syncDone()
        assertThat(retry.callbacks.published).hasSize(1)
    }

}
