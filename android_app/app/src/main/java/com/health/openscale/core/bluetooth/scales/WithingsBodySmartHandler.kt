/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.core.bluetooth.scales

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.map
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.WithingsWppLib as Wpp
import com.health.openscale.core.bluetooth.libs.WithingsUserRoster as Roster
import com.health.openscale.core.bluetooth.libs.WithingsLocalSetup as LocalSetup
import com.health.openscale.core.bluetooth.libs.WithingsScreenConfig as ScreenConfig
import java.time.Instant
import java.time.ZoneId
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import com.welie.blessed.BondState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt

/** Body Smart WPP reads, mutual authentication and explicitly approved local setup.
 * Physical setup mode remains necessary once when replacing unknown association keys.
 * Subsequent sessions are triggered by normal weigh-in advertisements.
 */
class WithingsBodySmartHandler(private val now: () -> Long = System::currentTimeMillis) : ScaleDeviceHandler() {
    companion object {
        val SERVICE: UUID = UUID.fromString("00000020-5749-5448-0010-000000000000")
        val CHARACTERISTIC: UUID = UUID.fromString("00000024-5749-5448-0010-000000000000")
        internal val METABOLIC_AGE = MeasurementType.deviceFloat("metabolic_age", R.string.body_smart_metabolic_age)
        internal const val USE_CURRENT_DATE = "latestUndatedWithCurrentDate"
        private val NAME = Regex("Body Smart(?: [0-9a-f]{2})?", RegexOption.IGNORE_CASE)
        private const val MIN_TIME = 1577836800L // 2020; predates this scale model.
    }

    private enum class State {
        PROBE, AUTH, TIME, STATUS, RECORDS, CHOOSE_USER, IMPORTING,
        PROFILE_WRITE, PROFILE_ACK, SCREEN_WRITE, SCREEN_ACK,
        LOCAL_KEYS, SYNC, SETUP_END, DISCONNECT, DONE
    }
    private var state = State.DONE
    private var decoder = Wpp.Decoder()
    private var records = Wpp.Records()
    private var sessionUser = ScaleUser()
    private var timeout: Job? = null
    private var bonding: Job? = null
    private var rosterSending: Job? = null
    private var importing: Job? = null
    private var choices = emptyList<Long>()
    private var pending = emptyList<Wpp.Record>()
    private var boundAddress = ""
    private var authentication: Wpp.Authentication? = null
    private var localSetup = false
    private var rosterIds = emptySet<Long>()
    private var rosterScreens = emptyList<ByteArray>()

    override val logRawPackets = false

    internal override fun bindDevice(address: String) { boundAddress = address.uppercase() }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!NAME.matches(device.name)) return null
        val features = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.HISTORY_READ, DeviceCapability.USER_SYNC)
        return DeviceSupport("Withings Body Smart", features, features, hasUserSettings = true)
    }

    @Composable
    override fun DeviceConfigurationUi() {
        val target = configurationTarget()
        var automatic by remember { mutableStateOf(settingsGetInt("automaticSync", 1) == 1) }
        var useCurrent by remember { mutableStateOf(settingsGetInt(USE_CURRENT_DATE, 0) == 1) }
        val queued by remember(target) {
            settingsObserveString("local/approved/$target").map { !it.isNullOrBlank() }
        }.collectAsState(false)
        val result by remember(target) { settingsObserveString("local/result/$target") }.collectAsState(null)
        WithingsSettingsUi(
            target = target, automatic = automatic,
            onAutomaticChange = { automatic = it; settingsPutInt("automaticSync", if (it) 1 else 0) },
            useCurrentDate = useCurrent,
            onCurrentDateChange = { useCurrent = it; settingsPutInt(USE_CURRENT_DATE, if (it) 1 else 0) },
            queued = queued, result = result,
            onQueue = {
                settingsPutString("local/result/$target", "")
                settingsPutString("local/approved/$target", it)
            },
            onCancel = { settingsPutString("local/approved/$target", "") }
        )
    }

    @Composable
    override fun UserConfigurationUi() {
        val target = configurationTarget()
        val queued by remember(target) {
            settingsObserveString("roster/approved/$target").map { !it.isNullOrBlank() }
        }.collectAsState(false)
        val result by remember(target) { settingsObserveString("roster/result/$target") }.collectAsState(null)
        WithingsUserRosterUi(
            target = target, initialDraft = settingsGetString("roster/draft/$target", "[]") ?: "[]",
            queued = queued, result = result,
            onSave = {
                // Editing a draft invalidates its previous one-time approval and result.
                settingsPutString("roster/approved/$target", "")
                settingsPutString("roster/result/$target", "")
                settingsPutString("roster/draft/$target", it)
            },
            onQueue = {
                settingsPutString("roster/result/$target", "")
                settingsPutString("roster/approved/$target", it)
            },
            onCancel = { settingsPutString("roster/approved/$target", "") },
            onResetMapping = { settingsPutInt("resetUserChoice", 1) }
        )
    }

    override fun onConnected(user: ScaleUser) {
        timeout?.cancel(); bonding?.cancel(); rosterSending?.cancel(); importing?.cancel()
        decoder = Wpp.Decoder(); records = Wpp.Records()
        pending = emptyList(); choices = emptyList()
        authentication = null; localSetup = false; rosterIds = emptySet()
        rosterScreens = emptyList()
        sessionUser = user.copy()
        getPeripheral()?.address?.let {
            boundAddress = it.uppercase()
            settingsPutString("lastDeviceAddress", boundAddress)
        }
        if (settingsGetInt("resetUserChoice", 0) == 1) {
            settingsPutString(mappingKey(), "")
            settingsPutInt("resetUserChoice", 0)
        }
        state = State.PROBE
        if (!hasCharacteristic(SERVICE, CHARACTERISTIC)) {
            fail(R.string.body_smart_service_missing)
            return
        }
        val peripheral = getPeripheral()
        if (peripheral == null || peripheral.bondState == BondState.BONDED) startRead()
        else {
            userInfo(R.string.body_smart_pairing)
            bonding = scope.launch {
                if (peripheral.bondState != BondState.BONDING && !peripheral.createBond()) {
                    fail(R.string.body_smart_pairing_failed)
                    return@launch
                }
                val paired = withTimeoutOrNull(60_000) {
                    while (peripheral.bondState != BondState.BONDED) delay(200)
                    true
                } ?: false
                if (paired && state != State.DONE) startRead()
                else fail(R.string.body_smart_pairing_failed)
            }
        }
    }

    private fun startRead() {
        setNotifyOn(SERVICE, CHARACTERISTIC)
        send(Wpp.probe())
    }

    private fun send(packet: ByteArray) {
        writeTo(SERVICE, CHARACTERISTIC, packet)
        armTimeout()
    }

    private fun armTimeout() {
        timeout?.cancel()
        timeout = scope.launch {
            delay(20_000)
            if (state == State.DISCONNECT) {
                finish()
            } else if (state in setOf(State.SCREEN_WRITE, State.SCREEN_ACK)) {
                settingsPutString("roster/result/${deviceKey()}", "screens-timeout")
                fail(R.string.body_smart_screens_incomplete)
            } else if (localSetup) {
                settingsPutString("local/result/${deviceKey()}", "incomplete")
                fail(R.string.body_smart_local_failed)
            } else if (state in setOf(State.PROFILE_WRITE, State.PROFILE_ACK)) {
                settingsPutString("roster/result/${deviceKey()}", "timeout")
                fail(R.string.body_smart_roster_timeout)
            } else fail(R.string.body_smart_timeout)
        }
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHARACTERISTIC || state in setOf(State.DONE, State.CHOOSE_USER, State.IMPORTING)) return
        try {
            for (message in decoder.feed(data)) {
                when {
                    message.command == Wpp.CHALLENGE && state == State.PROBE -> {
                        val credentials = runCatching { LocalSetup.decode(settingsGetString("local/credentials/${deviceKey()}").orEmpty()) }.getOrNull()
                        if (credentials == null) {
                            fail(R.string.body_smart_key_required)
                        } else {
                            val challenge = Wpp.challenge(message)
                            authentication = Wpp.Authentication(challenge, credentials.key)
                            state = State.AUTH
                            send(authentication!!.request)
                        }
                    }
                    message.command == Wpp.ERROR -> {
                        if (state in setOf(State.SCREEN_WRITE, State.SCREEN_ACK)) {
                            settingsPutString("roster/result/${deviceKey()}", "screens-rejected")
                            fail(R.string.body_smart_screens_incomplete)
                        } else if (state in setOf(State.PROFILE_WRITE, State.PROFILE_ACK)) {
                            settingsPutString("roster/result/${deviceKey()}", "rejected")
                            fail(R.string.body_smart_roster_rejected)
                        } else if (state == State.TIME && !localSetup) {
                            userWarn(R.string.body_smart_time_rejected)
                            readStatus()
                        } else if (state == State.LOCAL_KEYS || localSetup) {
                            settingsPutString("local/result/${deviceKey()}", "rejected-or-incomplete")
                            fail(R.string.body_smart_local_failed)
                        } else fail(R.string.body_smart_query_rejected)
                    }
                    message.command == Wpp.PROBE && (state == State.PROBE || state == State.AUTH) -> {
                        require(message.objects.any { it.type == 0x0101 })
                        if (state == State.AUTH && authentication?.accepts(message) != true) {
                            fail(R.string.body_smart_auth_failed)
                        } else if (state == State.PROBE && startApprovedLocalSetup()) {
                            // Setup mode permits a one-time, explicitly approved credential write.
                        } else if (!startApprovedRoster()) setClock()
                    }
                    message.command == ScreenConfig.COMMAND && state == State.SCREEN_ACK &&
                        (message.objects.isEmpty() || message.objects.all { it.type == Wpp.NULL && it.value.isEmpty() }) -> {
                        rosterSending = scope.launch {
                            try {
                                settingsPutStringAndAwait("roster/result/${deviceKey()}", "acknowledged")
                                if (state == State.SCREEN_ACK) {
                                    userInfo(R.string.body_smart_roster_acknowledged)
                                    completeSync()
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                fail(R.string.body_smart_screens_incomplete)
                            }
                        }
                    }
                    message.command == Wpp.ASSOCIATION_KEYS_SET && state == State.LOCAL_KEYS -> {
                        settingsPutString("local/result/${deviceKey()}", "keys-acknowledged")
                        setClock()
                    }
                    message.command == Wpp.TIME_SET && state == State.TIME -> {
                        if (localSetup) completeSync() else readStatus()
                    }
                    message.command == Wpp.SYNC_OK && state == State.SYNC -> {
                        if (localSetup) {
                            state = State.SETUP_END
                            send(Wpp.encode(Wpp.SETUP_OK))
                        } else protocolDisconnect()
                    }
                    message.command == Wpp.SETUP_OK && state == State.SETUP_END -> {
                        settingsPutString("local/result/${deviceKey()}", "setup-acknowledged")
                        userInfo(R.string.body_smart_local_done)
                        protocolDisconnect()
                    }
                    message.command == Wpp.DISCONNECT && state == State.DISCONNECT -> finish()
                    message.command == Roster.COMMAND && state == State.PROFILE_ACK &&
                        (message.objects.isEmpty() || message.objects.all { it.type == Wpp.NULL && it.value.isEmpty() }) -> {
                        if (settingsGetString(mappingKey())?.toLongOrNull() !in rosterIds) {
                            settingsPutString(mappingKey(), "")
                        }
                        startRosterScreens()
                    }
                    message.command == Wpp.STORED_MEASURE && state == State.STATUS -> {
                        val status = message.objects.firstOrNull { it.type == 0x0115 } ?: continue
                        require(status.value.size == 7)
                        val count = java.nio.ByteBuffer.wrap(status.value).short.toInt()
                        require(count >= 0)
                        if (count == 0) {
                            userInfo(R.string.body_smart_no_readings)
                            completeSync()
                        } else {
                            state = State.RECORDS
                            send(Wpp.storedMeasurements(1))
                        }
                    }
                    message.command == Wpp.STORED_MEASURE && state == State.RECORDS -> {
                        armTimeout()
                        records.accept(message)
                        if (records.complete) chooseUser()
                    }
                }
                if (state == State.DONE || state == State.CHOOSE_USER) break
            }
        } catch (_: IllegalArgumentException) {
            fail(R.string.body_smart_invalid_response)
        }
    }

    /**
     * The roster acknowledgement confirms the users, not their display settings. A new user
     * can be recognized by the scale yet see only weight until this separate list is sent.
     */
    private fun startRosterScreens() {
        state = State.SCREEN_WRITE
        armTimeout()
        rosterSending = scope.launch {
            try {
                settingsPutStringAndAwait("roster/result/${deviceKey()}", "profiles-acknowledged")
                for ((index, frame) in rosterScreens.withIndex()) {
                    if (state != State.SCREEN_WRITE) return@launch
                    if (index == rosterScreens.lastIndex) state = State.SCREEN_ACK
                    writeTo(SERVICE, CHARACTERISTIC, frame)
                    armTimeout()
                    delay(500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(R.string.body_smart_screens_incomplete)
            }
        }
    }

    private fun deviceKey(): String {
        val address = getPeripheral()?.address.orEmpty().ifEmpty { boundAddress }.ifEmpty { "unknown" }.uppercase()
        return settingsGetString("alias/$address")?.takeIf { it.isNotBlank() } ?: address
    }

    private fun configurationTarget(): String {
        val address = boundAddress.ifEmpty { settingsGetString("lastDeviceAddress").orEmpty() }.uppercase()
        return settingsGetString("alias/$address")?.takeIf { it.isNotBlank() } ?: address
    }

    private fun isSelectedRadioAddress() =
        (getPeripheral()?.address ?: boundAddress).equals(deviceKey(), ignoreCase = true)

    private fun setClock() {
        val instant = Instant.ofEpochMilli(now())
        val rules = ZoneId.systemDefault().rules
        val transition = rules.nextTransition(instant)
        state = State.TIME
        send(Wpp.setTime(instant.epochSecond, rules.getOffset(instant).totalSeconds,
            transition?.instant?.epochSecond ?: 0, transition?.offsetAfter?.totalSeconds ?: rules.getOffset(instant).totalSeconds))
    }

    private fun readStatus() {
        state = State.STATUS
        send(Wpp.storedMeasurements(0))
    }

    private fun completeSync() {
        state = State.SYNC
        send(Wpp.encode(Wpp.SYNC_OK))
    }

    private fun protocolDisconnect() {
        state = State.DISCONNECT
        send(Wpp.encode(Wpp.DISCONNECT))
        timeout?.cancel()
        timeout = scope.launch { delay(1000); finish() }
    }

    private fun startApprovedLocalSetup(): Boolean {
        val target = deviceKey()
        val approval = settingsGetString("local/approved/$target")?.takeIf { it.isNotBlank() } ?: return false
        val credentials = runCatching {
            // A name-only background match is not sufficient to replace a device's keys.
            require(isSelectedRadioAddress())
            LocalSetup.approved(approval, target, now())
        }.getOrElse {
            settingsPutString("local/approved/$target", "")
            settingsPutString("local/result/$target", "invalid")
            fail(R.string.body_smart_local_expired)
            return true
        }
        state = State.LOCAL_KEYS
        localSetup = true
        armTimeout()
        rosterSending = scope.launch {
            // Save recovery credentials durably before touching the scale. Approval is also
            // consumed durably, so a crash cannot cause an unnoticed provisioning replay.
            // The scale may accept the new keys and disconnect before acknowledging them.
            try {
                val old = settingsGetString("local/credentials/$target").orEmpty()
                if (old.isNotEmpty()) settingsPutStringAndAwait("local/previousCredentials/$target", old)
                settingsPutStringAndAwait("local/credentials/$target", credentials.encode())
                settingsPutStringAndAwait("local/approved/$target", "")
                settingsPutStringAndAwait("local/result/$target", "sending")
                if (state == State.LOCAL_KEYS) send(credentials.packet())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(R.string.body_smart_local_failed)
            }
        }
        return true
    }
    private fun mappingKey() = "user/${deviceKey()}/${sessionUser.id}"
    private fun isDated(record: Wpp.Record) = record.time in MIN_TIME..(now() / 1000 + 86400)

    /** Consume one explicit approval before any write; errors never trigger automatic replay. */
    private fun startApprovedRoster(): Boolean {
        val key = "roster/approved/${deviceKey()}"
        val approval = settingsGetString(key)?.takeIf { it.isNotBlank() } ?: return false
        val frames = runCatching {
            // An address alias is accepted for writes only after mutual authentication.
            require(state == State.AUTH || isSelectedRadioAddress())
            val profiles = Roster.approvedProfiles(approval, deviceKey(), now())
            rosterIds = profiles.map { it.id }.toSet()
            rosterScreens = ScreenConfig.frames(profiles)
            Roster.messages(profiles)
        }.getOrElse {
            settingsPutString(key, "")
            settingsPutString("roster/result/${deviceKey()}", "invalid")
            fail(R.string.body_smart_roster_invalid_approval)
            return true
        }
        state = State.PROFILE_WRITE
        userInfo(R.string.body_smart_roster_sending)
        armTimeout()
        rosterSending = scope.launch {
            try {
                settingsPutStringAndAwait(key, "")
                settingsPutStringAndAwait("roster/result/${deviceKey()}", "sending")
                for ((index, frame) in frames.withIndex()) {
                    if (state != State.PROFILE_WRITE) return@launch
                    // An early acknowledgement must not truncate the replacement list.
                    if (index == frames.lastIndex) state = State.PROFILE_ACK
                    writeTo(SERVICE, CHARACTERISTIC, frame)
                    armTimeout()
                    delay(500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(R.string.body_smart_roster_timeout)
            }
        }
        return true
    }

    private fun chooseUser() {
        timeout?.cancel()
        pending = records.all().filter {
            it.userId != null && (it.values[1] ?: 0.0) in 0.01..1000.0
        }
        if (pending.isEmpty()) {
            fail(R.string.body_smart_no_readings)
            return
        }
        val mapped = settingsGetString(mappingKey())?.toLongOrNull()
        if (mapped != null) {
            importFor(mapped)
            return
        }
        choices = pending.mapNotNull { it.userId }.distinct().sorted()
        state = State.CHOOSE_USER
        val labels = choices.map { id ->
            if (id == 0L) resolveString(R.string.body_smart_guest)
            else resolveString(R.string.body_smart_scale_user, id)
        }.toTypedArray<CharSequence>()
        userInfo(R.string.body_smart_choose_user)
        requestUserInteraction(UserInteractionType.CHOOSE_USER, labels to IntArray(choices.size) { it })
    }

    override suspend fun onUserInteractionFeedback(interactionType: UserInteractionType, appUserId: Int, feedbackData: Any) {
        if (state != State.CHOOSE_USER || interactionType != UserInteractionType.CHOOSE_USER || appUserId != sessionUser.id) return
        val index = feedbackData as? Int ?: return
        val selected = choices.getOrNull(index) ?: return
        settingsPutString(mappingKey(), selected.toString())
        importFor(selected)
    }

    private fun importFor(scaleUser: Long) {
        val matching = pending.filter { it.userId == scaleUser }
        if (matching.isEmpty()) {
            fail(R.string.body_smart_no_matching_readings)
            return
        }
        val historyKey = "history/${deviceKey()}/${sessionUser.id}/$scaleUser"
        val undatedKey = "undated/${deviceKey()}/${sessionUser.id}/$scaleUser"
        state = State.IMPORTING
        importing = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val dated = matching.filter(::isDated).sortedBy { it.time }
                if (dated.isNotEmpty()) {
                    // Start with the latest reading, not the scale's lifetime history. Persist
                    // the starting boundary before publication; a failed save can retry it.
                    val startKey = "$historyKey/start"
                    val start = settingsGetString(startKey)?.toLongOrNull() ?: dated.last().time.also {
                        settingsPutStringAndAwait(startKey, it.toString())
                    }
                    for (record in dated.filter { it.time >= start }) {
                        val receiptKey = "$historyKey/saved/${record.time}"
                        if (settingsGetString(receiptKey) != "1") {
                            publish(toMeasurement(record, Date(record.time * 1000))) {
                                // This runs in the connector's save job, even after BLE disconnects.
                                // Save jobs can finish out of order. A single latest-timestamp marker
                                // would skip an older reading whose database save failed.
                                settingsPutStringAndAwait(receiptKey, "1")
                            }
                        }
                    }
                }
                val undated = matching.filterNot(::isDated)
                if (undated.isNotEmpty()) {
                    if (settingsGetInt(USE_CURRENT_DATE, 0) != 1) userWarn(R.string.body_smart_clock_unset)
                    else {
                        val latest = undated.maxBy { it.time }
                        val fingerprint = MessageDigest.getInstance("SHA-256")
                            .digest("${latest.time}/${latest.values.toSortedMap()}".toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        if (settingsGetString(undatedKey) != fingerprint) {
                            publish(toMeasurement(latest, Date(now()))) {
                                settingsPutStringAndAwait(undatedKey, fingerprint)
                            }
                        }
                    }
                }
                if (state == State.IMPORTING) completeSync()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(R.string.body_smart_import_failed)
            }
        }
    }

    private fun toMeasurement(record: Wpp.Record, date: Date): ScaleMeasurement {
        val values = record.values
        val weight = values.getValue(1).toFloat()
        fun mass(type: Int) = values[type]?.takeIf { it > 0 && it <= weight.toDouble() }
        return ScaleMeasurement(userId = sessionUser.id, dateTime = date).apply {
            this[MeasurementType.WEIGHT] = Kg(weight)
            values[6]?.takeIf { it in 0.0..100.0 }?.let { this[MeasurementType.BODY_FAT] = Percent(it.toFloat()) }
                ?: mass(8)?.let { this[MeasurementType.BODY_FAT] = Percent(it.toFloat() / weight * 100f) }
            mass(5)?.let { this[MeasurementType.LBM] = Kg(it.toFloat()) }
            mass(76)?.let { this[MeasurementType.MUSCLE] = Percent(it.toFloat() / weight * 100f) }
            mass(77)?.let { this[MeasurementType.WATER] = Percent(it.toFloat() / weight * 100f) }
            mass(88)?.let { this[MeasurementType.BONE] = Kg(it.toFloat()) }
            values[170]?.takeIf { it in 0.0..100.0 }?.let { this[MeasurementType.VISCERAL_FAT] = it.toFloat() }
            values[226]?.takeIf { it in 100.0..10000.0 }?.let { this[MeasurementType.BMR] = Kcal(it.toFloat()) }
            // Official Withings measure type 227. Do not derive a vendor metabolic age.
            values[227]?.takeIf { it in 1.0..150.0 }?.let { this[METABOLIC_AGE] = it.toFloat() }
            values[11]?.takeIf { it in 1.0..300.0 }?.let { this[MeasurementType.HEART_RATE] = Bpm(it.roundToInt()) }
        }
    }

    private fun fail(message: Int) {
        markInterruptedOperation()
        userWarn(message)
        finish()
    }

    private fun finish() {
        state = State.DONE
        timeout?.cancel()
        rosterSending?.cancel()
        importing?.cancel()
        requestDisconnect()
    }

    private fun markInterruptedOperation() {
        val rosterKey = "roster/result/${deviceKey()}"
        when (state) {
            State.PROFILE_WRITE, State.PROFILE_ACK ->
                if (settingsGetString(rosterKey) == "sending") settingsPutString(rosterKey, "incomplete")
            State.SCREEN_WRITE, State.SCREEN_ACK ->
                if (settingsGetString(rosterKey) == "profiles-acknowledged") settingsPutString(rosterKey, "screens-incomplete")
            else -> Unit
        }
        val localKey = "local/result/${deviceKey()}"
        if (localSetup && settingsGetString(localKey) in setOf("sending", "keys-acknowledged")) {
            settingsPutString(localKey, "incomplete")
        }
    }

    override fun onDisconnected() {
        markInterruptedOperation()
        state = State.DONE
        timeout?.cancel(); bonding?.cancel(); rosterSending?.cancel(); importing?.cancel()
        pending = emptyList(); choices = emptyList()
        rosterScreens = emptyList()
        decoder.reset()
    }
}
