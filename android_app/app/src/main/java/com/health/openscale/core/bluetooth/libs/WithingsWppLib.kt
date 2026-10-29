/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.core.bluetooth.libs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.pow

/** WPP framing and stored records, verified with a Body Smart firmware 881 capture.
 * Protocol reference: https://github.com/pierosavi/withings-local/blob/main/docs/protocol.md
 * Integers are big endian; numerical values carry a signed decimal exponent.
 */
object WithingsWppLib {
    const val PROBE = 0x0101
    const val CHALLENGE = 0x0128
    const val ERROR = 0x0100
    const val STORED_MEASURE = 0x010F
    // WPP reuses 0x0100: it is an error command, but a transfer terminator in the TLV list.
    const val NULL = 0x0100
    const val META = 0x0116
    const val DATA = 0x0117
    const val TIME_SET = 0x0501
    const val SYNC_OK = 0x0115
    const val DISCONNECT = 0x0110
    const val ASSOCIATION_KEYS_SET = 0x0134
    const val SETUP_OK = 0x0113

    data class Tlv(val type: Int, val value: ByteArray)
    data class Message(val command: Int, val objects: List<Tlv>)

    fun encode(command: Int, vararg objects: Tlv): ByteArray {
        require(command in 0..0xFFFF)
        val size = objects.sumOf { 4 + it.value.size }
        require(size <= 0xFFFF)
        return ByteBuffer.allocate(5 + size).order(ByteOrder.BIG_ENDIAN).apply {
            put(1.toByte()); putShort(command.toShort()); putShort(size.toShort())
            for (obj in objects) {
                require(obj.type in 0..0xFFFF)
                putShort(obj.type.toShort()); putShort(obj.value.size.toShort()); put(obj.value)
            }
        }.array()
    }

    fun probe() = encode(PROBE,
        Tlv(0x012A, byteArrayOf(1, 1, 0, 0, 0, 1)),
        Tlv(0x0928, byteArrayOf(0, 34)))

    /** Preserve the exact MAC string: its spelling and case are part of the digest. */
    data class Challenge(val mac: String, val nonce: ByteArray)

    fun challenge(message: Message): Challenge {
        require(message.command == CHALLENGE)
        val value = requireNotNull(message.objects.singleOrNull { it.type == 0x0122 }).value
        require(value.isNotEmpty())
        val nameSize = value[0].toInt() and 255
        require(nameSize == 17 && value.size == nameSize + 18)
        val mac = value.copyOfRange(1, 1 + nameSize).toString(Charsets.US_ASCII)
        require(Regex("[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}").matches(mac))
        require(value[1 + nameSize] == 16.toByte())
        return Challenge(mac, value.copyOfRange(2 + nameSize, value.size))
    }

    fun answer(nonce: ByteArray, mac: String, key: String): ByteArray {
        require(nonce.size == 16)
        return MessageDigest.getInstance("SHA-1")
            .digest(nonce + mac.toByteArray(Charsets.UTF_8) + key.toByteArray(Charsets.UTF_8))
    }

    class Authentication(challenge: Challenge, key: String, nonce: ByteArray = randomBytes(16)) {
        private val expected = answer(nonce, challenge.mac, key)
        val request = encode(CHALLENGE,
            Tlv(0x0123, sized(answer(challenge.nonce, challenge.mac, key))),
            Tlv(0x0122, sized(challenge.mac.toByteArray(Charsets.UTF_8)) + sized(nonce)))

        fun accepts(reply: Message): Boolean {
            val response = reply.objects.singleOrNull { it.type == 0x0123 }?.value ?: return false
            return reply.command == PROBE && response.size == 21 && response[0] == 20.toByte() &&
                MessageDigest.isEqual(expected, response.copyOfRange(1, response.size))
        }
    }

    fun setTime(utcSeconds: Long, offsetSeconds: Int, nextChange: Long = 0, nextOffsetSeconds: Int = offsetSeconds): ByteArray {
        require(utcSeconds in 1577836800L..0xFFFFFFFFL && nextChange in 0..0xFFFFFFFFL)
        return encode(TIME_SET, Tlv(TIME_SET, ByteBuffer.allocate(16)
            .putInt(utcSeconds.toInt()).putInt(offsetSeconds).putInt(nextChange.toInt()).putInt(nextOffsetSeconds).array()))
    }

    /** Explicit one-time local ownership setup, never part of a normal measurement read. */
    fun associationKeys(accountId: Long, key: String, advertisingKey: String): ByteArray {
        require(accountId in 1..0xFFFFFFFFL)
        require(Regex("[0-9a-f]{32}").matches(key) && Regex("[0-9a-f]{32}").matches(advertisingKey))
        return encode(ASSOCIATION_KEYS_SET,
            Tlv(0x0135, ByteBuffer.allocate(4).putInt(accountId.toInt()).array() + sized(key.toByteArray(Charsets.US_ASCII))),
            Tlv(0x0136, sized(advertisingKey.toByteArray(Charsets.US_ASCII))))
    }

    private fun sized(value: ByteArray): ByteArray {
        require(value.size <= 255)
        return byteArrayOf(value.size.toByte()) + value
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    /** Only the two read actions are exposed. Action 2 would delete the scale's store. */
    fun storedMeasurements(action: Int): ByteArray {
        require(action == 0 || action == 1)
        return encode(STORED_MEASURE, Tlv(0x0114, byteArrayOf(action.toByte(), 0)))
    }

    /** ATT notifications can split a WPP frame or contain several frames; keep the unfinished tail. */
    class Decoder {
        private var pending = byteArrayOf()
        fun reset() { pending = byteArrayOf() }

        fun feed(fragment: ByteArray): List<Message> {
            try {
                require(fragment.size <= 65540 && pending.size + fragment.size <= 131080)
                pending += fragment
                val messages = mutableListOf<Message>()
                var consumed = 0
                while (pending.size - consumed >= 5) {
                    require(pending[consumed] == 1.toByte()) { "Unexpected WPP version" }
                    val header = ByteBuffer.wrap(pending, consumed + 1, 4)
                    val command = header.short.toInt() and 0xFFFF
                    val size = header.short.toInt() and 0xFFFF
                    val end = consumed + 5 + size
                    if (pending.size < end) break
                    var pos = consumed + 5
                    val objects = mutableListOf<Tlv>()
                    while (pos < end) {
                        require(end - pos >= 4) { "Truncated WPP object header" }
                        val obj = ByteBuffer.wrap(pending, pos, 4)
                        val type = obj.short.toInt() and 0xFFFF
                        val length = obj.short.toInt() and 0xFFFF
                        pos += 4
                        require(length <= end - pos) { "Truncated WPP object value" }
                        objects += Tlv(type, pending.copyOfRange(pos, pos + length))
                        pos += length
                    }
                    messages += Message(command, objects)
                    consumed = end
                }
                pending = pending.copyOfRange(consumed, pending.size)
                return messages
            } catch (e: IllegalArgumentException) {
                reset()
                throw e
            }
        }
    }

    data class Record(
        val time: Long,
        val userIds: List<Long>,
        val values: MutableMap<Int, Double> = linkedMapOf()
    ) {
        /** Multiple valid IDs mean ambiguous attribution; never silently choose one. */
        val userId: Long? get() = when (userIds.size) {
            0 -> 0L
            1 -> userIds.single()
            else -> null
        }
    }

    /** Keep state across messages: a record may cross both ATT and WPP boundaries. */
    class Records {
        private val records = linkedMapOf<Pair<Long, List<Long>>, Record>()
        private var current: Record? = null
        var complete = false
            private set

        fun accept(message: Message) {
            require(message.command == STORED_MEASURE && !complete)
            for (obj in message.objects) {
                require(!complete) { "Objects follow the transfer terminator" }
                when (obj.type) {
                    META -> {
                        current = null
                        val record = parseMeta(obj.value)
                        // Heart rate can arrive in another group for the same weigh-in. Reusing
                        // the record keeps it together with the weight and composition values.
                        current = records.getOrPut(record.time to record.userIds) { record }
                    }
                    DATA -> {
                        require(obj.value.size == 8) { "Invalid measurement value length" }
                        val buffer = ByteBuffer.wrap(obj.value)
                        val raw = buffer.int
                        val type = buffer.short.toInt() and 0xFFFF
                        val exponent = buffer.short.toInt()
                        // Unknown/unsupported exponents are not guessed or allowed to overflow.
                        if (exponent in -12..12) current?.values?.set(type, raw * 10.0.pow(exponent))
                    }
                    NULL -> {
                        require(obj.value.isEmpty()) { "Invalid transfer terminator" }
                        complete = true
                        current = null
                    }
                }
            }
        }

        fun all(): List<Record> = records.values.map { it.copy(values = LinkedHashMap(it.values)) }
    }

    private fun parseMeta(value: ByteArray): Record {
        require(value.size >= 11) { "Truncated measurement metadata" }
        val buffer = ByteBuffer.wrap(value)
        buffer.int // UID is not a reliable identity; merge by timestamp and attributed users.
        val valid = buffer.get().toInt() and 0xFF
        val slots = buffer.get().toInt() and 0xFF
        require(valid <= slots && buffer.remaining() == slots * 4 + 5)
        // Only the first `valid` slots belong to this reading. The remaining slots may
        // contain stale IDs and must not be mistaken for additional users.
        val ids = List(slots) { buffer.int.toLong() and 0xFFFFFFFFL }
            .take(valid).filter { it != 0L }.distinct()
        buffer.get() // attribution flags, retained on the device
        return Record(buffer.int.toLong() and 0xFFFFFFFFL, ids)
    }
}
