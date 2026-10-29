/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.libs.WithingsWppLib as Wpp
import org.junit.Assert.assertThrows
import org.junit.Test

class WithingsWppLibTest {
    @Test fun `read commands match the independently verified wire format`() {
        assertThat(Wpp.probe()).isEqualTo(hex("0101010010012a0006010100000001092800020022"))
        assertThat(Wpp.storedMeasurements(0)).isEqualTo(hex("01010f0006011400020000"))
        assertThat(Wpp.storedMeasurements(1)).isEqualTo(hex("01010f0006011400020100"))
        assertThrows(IllegalArgumentException::class.java) { Wpp.storedMeasurements(2) }
    }

    @Test fun `fragmentation and adjacent messages preserve object boundaries`() {
        val decoder = Wpp.Decoder()
        val bytes = hex("01010f000601140002010001010f000401000000")
        val messages = bytes.flatMap { decoder.feed(byteArrayOf(it)) }
        assertThat(messages).hasSize(2)
        assertThat(messages[0].objects.single().type).isEqualTo(0x0114)
        assertThat(messages[0].objects.single().value).isEqualTo(hex("0100"))
        assertThat(messages[1].objects.single().type).isEqualTo(Wpp.NULL)
        assertThat(messages[1].objects.single().value).isEmpty()
    }

    @Test fun `malformed object is rejected and cannot poison the next session`() {
        val decoder = Wpp.Decoder()
        assertThrows(IllegalArgumentException::class.java) {
            decoder.feed(hex("01010f000401170008"))
        }
        assertThat(decoder.feed(hex("01010f000401000000"))).hasSize(1)
        assertThrows(IllegalArgumentException::class.java) { decoder.feed(ByteArray(131081)) }
    }

    @Test fun `metadata valid count excludes unused user slots and values cross messages`() {
        val decoder = Wpp.Decoder()
        // Synthetic metadata using the captured 23-byte layout: one valid ID, three slots.
        val meta = hex("01160017000000010103000000110000006300000064006553f100")
        val weight = hex("01170008000111700001fffd") // 70000 × 10^-3 = 70kg
        val muscle = hex("0117000800001388004cfffe") // 5000 × 10^-2 = 50kg
        val records = Wpp.Records()
        decoder.feed(Wpp.encode(Wpp.STORED_MEASURE, Wpp.Tlv(0x0114, hex("0100"))))
            .forEach(records::accept)
        // Fixed TLVs are framed independently of the library encoder.
        val payload = meta + weight
        val frame = hex("01010f") + byteArrayOf(0, payload.size.toByte()) + payload
        decoder.feed(frame).forEach(records::accept)
        decoder.feed(hex("01010f0010") + muscle + hex("01000000")).forEach(records::accept)

        val record = records.all().single()
        assertThat(record.userIds).containsExactly(17L)
        assertThat(record.time).isEqualTo(1700000000L)
        assertThat(record.values[1]).isEqualTo(70.0)
        assertThat(record.values[76]).isEqualTo(50.0)
        assertThat(records.complete).isTrue()
    }

    @Test fun `data without metadata is dropped and ambiguous user identities stay ambiguous`() {
        val records = Wpp.Records()
        records.accept(Wpp.Message(Wpp.STORED_MEASURE, listOf(
            Wpp.Tlv(Wpp.DATA, hex("000111700001fffd")),
            Wpp.Tlv(Wpp.META, hex("0000000102020000001100000012006553f100")),
            Wpp.Tlv(Wpp.DATA, hex("000111700001fffd")))))
        assertThat(records.all()).hasSize(1)
        assertThat(records.all().single().userId).isNull()
    }

    @Test fun `repeated metadata merges the same users weigh-in rather than duplicating it`() {
        val records = Wpp.Records()
        val meta = Wpp.Tlv(Wpp.META, hex("000000010103000000110000006300000064006553f100"))
        records.accept(Wpp.Message(Wpp.STORED_MEASURE, listOf(meta, Wpp.Tlv(Wpp.DATA, hex("000111700001fffd")))))
        records.accept(Wpp.Message(Wpp.STORED_MEASURE, listOf(meta, Wpp.Tlv(Wpp.DATA, hex("00000041000b0000")), Wpp.Tlv(Wpp.NULL, byteArrayOf()))))
        assertThat(records.all()).hasSize(1)
        assertThat(records.all().single().values.keys).containsExactly(1, 11)
    }
    @Test fun `authentication preserves exact MAC spelling and verifies mutual response`() {
        val nonce = ByteArray(16) { it.toByte() }
        val key = "0123456789abcdef0123456789abcdef"
        val mac = "aa:bb:cc:dd:ee:ff"
        assertThat(Wpp.answer(nonce, mac, key)).isEqualTo(hex("61ed7e08c65508721022c15778ad3ce238d08a97"))
        val authentication = Wpp.Authentication(Wpp.Challenge(mac, nonce), key, nonce)
        val challenge = Wpp.challenge(Wpp.Decoder().feed(authentication.request).single())
        assertThat(challenge.mac).isEqualTo(mac)
        assertThat(challenge.nonce).isEqualTo(nonce)
        val response = Wpp.Tlv(0x0123, byteArrayOf(20) + Wpp.answer(nonce, mac, key))
        assertThat(authentication.accepts(Wpp.Message(Wpp.PROBE, listOf(response)))).isTrue()
        assertThat(authentication.accepts(Wpp.Message(Wpp.PROBE, listOf(response.copy(value = response.value.copyOf().also { it[8] = (it[8].toInt() xor 1).toByte() }))))).isFalse()
        assertThat(authentication.accepts(Wpp.Message(Wpp.PROBE, emptyList()))).isFalse()
        assertThat(Wpp.answer(nonce, mac.uppercase(), key)).isNotEqualTo(Wpp.answer(nonce, mac, key))
        assertThrows(IllegalArgumentException::class.java) { Wpp.challenge(Wpp.Message(Wpp.CHALLENGE, emptyList())) }
    }

    @Test fun `clock and local credential provisioning use exact independent wire fixtures`() {
        assertThat(Wpp.setTime(1700000000, 3600)).isEqualTo(hex("0105010014050100106553f10000000e100000000000000e10"))
        val key = "0123456789abcdef0123456789abcdef"
        val packet = Wpp.associationKeys(7, key, key)
        assertThat(packet.copyOfRange(0, 14)).isEqualTo(hex("010134004e013500250000000720"))
        val objects = Wpp.Decoder().feed(packet).single().objects
        assertThat(objects.map { it.type }).containsExactly(0x0135, 0x0136).inOrder()
        assertThat(objects[1].value).isEqualTo(byteArrayOf(32) + key.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { Wpp.associationKeys(0, key, key) }
    }

}
