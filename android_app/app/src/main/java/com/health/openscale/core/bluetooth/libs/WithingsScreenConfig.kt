/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.libs

import java.nio.ByteBuffer

/** Body Smart's built-in Bluetooth-mode screens, verified on model 16 / firmware 881.
 * Pairs are manufacturer screen ID / firmware embedded ID, not measurement types.
 * The official app sends these in a separate ScreenList transaction after the roster.
 */
object WithingsScreenConfig {
    const val COMMAND = 0x050C
    const val SCREEN = 0x0516

    private val bluetoothScreens = listOf(66L to 11, 116L to 1, 54L to 16, 302L to 22, 118L to 3)

    data class Screen(val id: Long, val userId: Long, val embeddedId: Int) {
        fun tlv(): WithingsWppLib.Tlv {
            require(id in 1..0xFFFFFFFFL && userId in 1..0xFFFFFFFFL && embeddedId in 1..255)
            // Wire order: screen ID, user ID, start/end dates, embedded ID, source.
            // These built-in Bluetooth screens use zero for both dates.
            return WithingsWppLib.Tlv(SCREEN, ByteBuffer.allocate(18)
                .putInt(id.toInt()).putInt(userId.toInt()).putInt(0).putInt(0)
                .put(embeddedId.toByte()).put(1).array()) // Source 1: built-in, no downloaded content.
        }
    }

    fun screens(profiles: List<WithingsUserRoster.Profile>): List<Screen> {
        WithingsUserRoster.validate(profiles)
        return profiles.flatMap { profile ->
            bluetoothScreens.map { (id, embeddedId) -> Screen(id, profile.id, embeddedId) }
        }
    }

    fun frames(profiles: List<WithingsUserRoster.Profile>): List<ByteArray> {
        // Null ends the whole screen list, including when the list spans several writes.
        val objects = screens(profiles).map(Screen::tlv) + WithingsWppLib.Tlv(WithingsWppLib.NULL, byteArrayOf())
        val frames = mutableListOf<ByteArray>()
        var batch = mutableListOf<WithingsWppLib.Tlv>()
        var size = 5
        for (obj in objects) {
            // Count the WPP header and TLV headers against the verified 180-byte write limit.
            if (size + 4 + obj.value.size > 180) {
                frames += WithingsWppLib.encode(COMMAND, *batch.toTypedArray())
                batch = mutableListOf()
                size = 5
            }
            batch += obj
            size += 4 + obj.value.size
        }
        if (batch.isNotEmpty()) frames += WithingsWppLib.encode(COMMAND, *batch.toTypedArray())
        return frames
    }
}
