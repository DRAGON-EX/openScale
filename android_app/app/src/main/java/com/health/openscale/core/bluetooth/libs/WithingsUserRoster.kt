/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.libs

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Experimental local replacement roster. It is never inferred from measurement IDs.
 * The APK's SendSessionConversation/f99 provides the transport encoding; the profile
 * field map is independently documented by openwithings and the WPP protocol study.
 * No claim is made that an acknowledgement proves the firmware applied every field.
 */
object WithingsUserRoster {
    const val COMMAND = 0x010D
    const val CHUNK_TYPE = 0x0112
    private val EDITABLE_FIELDS = setOf("id", "fn", "sn", "wt", "ht", "agt", "sx", "fm", "att")

    data class Profile(
        val id: Long,
        val name: String,
        val shortName: String,
        val weightKg: Double,
        val heightCm: Double,
        val ageYears: Double,
        val sex: Int,
        val athlete: Boolean = false,
        val fatMethod: Int = 3,
        // Preserve device-owned fields (including cs, ft and cr) without interpreting
        // legacy screen numbers as Body Smart measurement-enable switches.
        val additionalFields: String = "{}"
    ) {
        fun validate() {
            require(id in 1..0xFFFFFFFFL)
            require(name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 64)
            require(shortName.matches(Regex("[A-Z0-9]{1,3}")))
            require(weightKg in 0.1..200.0 && heightCm in 20.0..250.0)
            require(ageYears in 0.0..120.0 && sex in 0..1 && fatMethod == 3)
            require(JSONObject(additionalFields).keys().asSequence().none { it in EDITABLE_FIELDS })
        }

        fun json(): JSONObject {
            validate()
            return JSONObject(additionalFields).put("id", id).put("fn", name).put("sn", shortName)
                .put("wt", weightKg).put("ht", heightCm / 100.0).put("agt", ageYears)
                .put("sx", sex).put("fm", fatMethod).put("att", if (athlete) 1 else 0)
        }
    }

    fun newId(existing: List<Profile>): Long {
        var candidate: Long
        do { candidate = UUID.randomUUID().leastSignificantBits and 0xFFFFFFFFL }
        while (candidate == 0L || existing.any { it.id == candidate })
        return candidate
    }

    fun validate(profiles: List<Profile>) {
        require(profiles.size <= 8)
        require(profiles.map { it.id }.distinct().size == profiles.size)
        profiles.forEach(Profile::validate)
    }

    fun encodeDraft(profiles: List<Profile>): String {
        validate(profiles)
        return JSONArray().apply { profiles.forEach { put(it.json()) } }.toString()
    }

    fun decodeDraft(json: String): List<Profile> {
        val array = JSONArray(json)
        require(array.length() <= 8)
        val profiles = List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            val additional = JSONObject(obj.toString()).apply { EDITABLE_FIELDS.forEach(::remove) }
            Profile(obj.getLong("id"), obj.getString("fn"), obj.getString("sn"),
                obj.getDouble("wt"), obj.getDouble("ht") * 100.0, obj.getDouble("agt"),
                obj.getInt("sx"), obj.getInt("att") == 1, obj.getInt("fm"),
                additional.toString())
        }
        validate(profiles)
        return profiles
    }

    /** Account-independent local session identifier. No vendor login/association keys. */
    fun messages(profiles: List<Profile>, sessionId: String = UUID.randomUUID().toString().replace("-", "")): List<ByteArray> {
        validate(profiles)
        require(sessionId.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        val users = JSONArray(encodeDraft(profiles))
        val body = JSONObject().put("sessionid", sessionId)
            .put("sp", JSONObject().put("users", users))
        // Deliberately omit global feature, Wi-Fi, owner-key, clock and firmware fields.
        val text = JSONObject().put("status", 0).put("body", body).toString() + "\\0"
        val chunks = mutableListOf<WithingsWppLib.Tlv>()
        val chunk = ByteArrayOutputStream()
        fun flush() {
            if (chunk.size() == 0) return
            val bytes = chunk.toByteArray()
            chunks += WithingsWppLib.Tlv(CHUNK_TYPE, byteArrayOf(bytes.size.toByte()) + bytes)
            chunk.reset()
        }
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (chunk.size() + bytes.size > 64) flush()
            chunk.write(bytes)
            offset += Character.charCount(codePoint)
        }
        flush()
        chunks += WithingsWppLib.Tlv(WithingsWppLib.NULL, byteArrayOf())
        val frames = mutableListOf<ByteArray>()
        var batch = mutableListOf<WithingsWppLib.Tlv>()
        var size = 5
        for (obj in chunks) {
            // Fit the tested 185-byte ATT MTU (182-byte characteristic payload).
            if (size + 4 + obj.value.size > 180) {
                frames += WithingsWppLib.encode(COMMAND, *batch.toTypedArray())
                batch = mutableListOf(); size = 5
            }
            batch += obj; size += 4 + obj.value.size
        }
        if (batch.isNotEmpty()) frames += WithingsWppLib.encode(COMMAND, *batch.toTypedArray())
        return frames
    }

    /** A queued write is bound to one explicit target, including after app restarts. */
    fun approval(target: String, profiles: List<Profile>, createdAt: Long = System.currentTimeMillis()): String {
        require(target.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")))
        return JSONObject().put("version", 1).put("approvedAt", createdAt).put("target", target.uppercase())
            .put("profiles", JSONArray(encodeDraft(profiles))).toString()
    }

    fun approvedProfiles(json: String, actualTarget: String, now: Long = System.currentTimeMillis()): List<Profile> {
        val approval = JSONObject(json)
        require(approval.getInt("version") == 1)
        require(approval.getLong("approvedAt") in (now - 600_000)..(now + 5_000))
        require(approval.getString("target").equals(actualTarget, ignoreCase = true))
        return decodeDraft(approval.getJSONArray("profiles").toString())
    }
}
