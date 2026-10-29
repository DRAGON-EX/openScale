/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.libs

import org.json.JSONObject
import java.nio.ByteBuffer

/** Locally generated credentials; these are never submitted to a Withings server. */
object WithingsLocalSetup {
    data class Credentials(val accountId: Long, val key: String, val advertisingKey: String) {
        fun encode(): String = JSONObject().put("accountId", accountId).put("key", key)
            .put("advertisingKey", advertisingKey).toString()
        fun packet() = WithingsWppLib.associationKeys(accountId, key, advertisingKey)
    }
    fun create(): Credentials {
        fun secret() = WithingsWppLib.randomBytes(16).joinToString("") { "%02x".format(it) }
        val id = (ByteBuffer.wrap(WithingsWppLib.randomBytes(4)).int.toLong() and 0xFFFFFFFFL).coerceAtLeast(1)
        return Credentials(id, secret(), secret())
    }
    fun decode(value: String): Credentials {
        val json = JSONObject(value)
        return Credentials(json.getLong("accountId"), json.getString("key"), json.getString("advertisingKey"))
            .also { it.packet() } // validate before using or sending
    }
    fun approval(target: String, credentials: Credentials, now: Long): String = JSONObject()
        .put("target", target).put("approvedAt", now).put("credentials", credentials.encode()).toString()
    fun approved(value: String, target: String, now: Long): Credentials {
        val json = JSONObject(value)
        require(json.getString("target") == target && now - json.getLong("approvedAt") in 0..600_000)
        return decode(json.getString("credentials"))
    }
}
