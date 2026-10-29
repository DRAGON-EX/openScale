/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WithingsLocalSetupTest {
    @Test fun `approval binds exact credentials to one scale and expires without replay`() {
        val credentials = WithingsLocalSetup.create()
        val approval = WithingsLocalSetup.approval("AA:BB:CC:DD:EE:FF", credentials, 1000)
        assertThat(WithingsLocalSetup.approved(approval, "AA:BB:CC:DD:EE:FF", 2000)).isEqualTo(credentials)
        assertThrows(IllegalArgumentException::class.java) { WithingsLocalSetup.approved(approval, "OTHER", 2000) }
        assertThrows(IllegalArgumentException::class.java) { WithingsLocalSetup.approved(approval, "AA:BB:CC:DD:EE:FF", 601001) }
        assertThrows(IllegalArgumentException::class.java) { WithingsLocalSetup.approved(approval, "AA:BB:CC:DD:EE:FF", 999) }
        assertThat(WithingsLocalSetup.create().key).isNotEqualTo(credentials.key)
    }
}
