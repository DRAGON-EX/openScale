/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WithingsScreenConfigTest {
    private val profile = WithingsUserRoster.Profile(17, "Example", "EXA", 70.0, 180.0, 30.0, 0)

    @Test fun `ScreenList matches the APK 18 byte big endian wire format`() {
        val tlv = WithingsScreenConfig.Screen(0x01020304, 0x55667788, 16).tlv()
        assertThat(tlv.type).isEqualTo(0x0516)
        assertThat(tlv.value).isEqualTo(hex("010203045566778800000000000000001001"))
    }

    @Test fun `every profile receives the manufacturer Bluetooth-mode screen sequence`() {
        val screens = WithingsScreenConfig.screens(listOf(profile, profile.copy(id = 18)))
        for (id in listOf(17L, 18L)) {
            assertThat(screens.filter { it.userId == id }.map { it.id to it.embeddedId })
                .containsExactly(66L to 11, 116L to 1, 54L to 16, 302L to 22, 118L to 3).inOrder()
        }
    }

    @Test fun `eight user lists fit ATT frames and have exactly one final terminator`() {
        val profiles = List(8) { profile.copy(id = it + 1L) }
        val frames = WithingsScreenConfig.frames(profiles)
        val objects = frames.flatMap { frame ->
            assertThat(frame.size).isAtMost(180)
            WithingsWppLib.Decoder().feed(frame).single().also {
                assertThat(it.command).isEqualTo(0x050C)
            }.objects
        }
        assertThat(objects.count { it.type == WithingsWppLib.NULL }).isEqualTo(1)
        assertThat(objects.last().type).isEqualTo(WithingsWppLib.NULL)
        assertThat(objects.dropLast(1).map { it.value.toList() })
            .containsExactlyElementsIn(WithingsScreenConfig.screens(profiles).map { it.tlv().value.toList() }).inOrder()
    }

    @Test fun `explicit removal of all profiles also clears their screen list`() {
        val message = WithingsWppLib.Decoder().feed(WithingsScreenConfig.frames(emptyList()).single()).single()
        assertThat(message.command).isEqualTo(0x050C)
        assertThat(message.objects.single().type).isEqualTo(WithingsWppLib.NULL)
        assertThat(message.objects.single().value).isEmpty()
    }
}
