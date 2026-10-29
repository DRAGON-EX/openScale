/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.libs.WithingsUserRoster as Roster
import com.health.openscale.core.bluetooth.libs.WithingsWppLib as Wpp
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WithingsUserRosterTest {
    private val profile = Roster.Profile(17, "Example", "EXA", 70.0, 180.0, 30.0, 0)
    private val target = "AA:BB:CC:DD:EE:01"

    @Test fun `draft additions edits and removals keep stable local profile identities`() {
        val added = profile.copy(id = Roster.newId(listOf(profile)), name = "Second", shortName = "TWO")
        val draft = Roster.decodeDraft(Roster.encodeDraft(listOf(profile, added)))
        assertThat(draft).containsExactly(profile, added).inOrder()
        val edited = draft.map { if (it.id == 17L) it.copy(name = "Changed", heightCm = 190.0) else it }
        assertThat(edited[0].id).isEqualTo(profile.id)
        val removed = edited.filterNot { it.id == added.id }
        assertThat(Roster.decodeDraft(Roster.encodeDraft(removed))).containsExactly(edited[0])
    }

    @Test fun `invalid values duplicate IDs and overcapacity are rejected before transmission`() {
        for (invalid in listOf(profile.copy(id = 0), profile.copy(name = ""), profile.copy(shortName = "LONG"),
            profile.copy(weightKg = Double.NaN), profile.copy(heightCm = 0.0), profile.copy(ageYears = 121.0),
            profile.copy(sex = 2), profile.copy(fatMethod = 0))) {
            assertThrows(IllegalArgumentException::class.java) { Roster.encodeDraft(listOf(invalid)) }
        }
        assertThrows(IllegalArgumentException::class.java) { Roster.encodeDraft(listOf(profile, profile)) }
        assertThrows(IllegalArgumentException::class.java) { Roster.encodeDraft(List(9) { profile.copy(id = it + 1L) }) }
    }

    @Test fun `session encoding is UTF8 safe length prefixed and fits the tested ATT MTU`() {
        val profiles = List(8) { profile.copy(id = it + 1L, name = "Müller🙂".repeat(4), shortName = "P$it") }
        val frames = Roster.messages(profiles, "localtest")
        val decoder = Wpp.Decoder()
        val objects = frames.flatMap { frame ->
            assertThat(frame.size).isAtMost(180)
            decoder.feed(frame).single().also { assertThat(it.command).isEqualTo(0x010D) }.objects
        }
        assertThat(objects.last().type).isEqualTo(Wpp.NULL)
        assertThat(objects.last().value).isEmpty()
        val text = objects.dropLast(1).joinToString("") { obj ->
            assertThat(obj.type).isEqualTo(0x0112)
            val length = obj.value.first().toInt() and 0xFF
            assertThat(length).isAtMost(64)
            assertThat(obj.value.size).isEqualTo(length + 1)
            String(obj.value.drop(1).toByteArray(), Charsets.UTF_8).also { assertThat(it).doesNotContain("�") }
        }
        assertThat(text.endsWith("\\0")).isTrue()
        val json = JSONObject(text.dropLast(2))
        assertThat(json.getInt("status")).isEqualTo(0)
        val body = json.getJSONObject("body")
        assertThat(body.keys().asSequence().toList()).containsExactly("sessionid", "sp")
        val users = body.getJSONObject("sp").getJSONArray("users")
        assertThat(users.length()).isEqualTo(8)
        val first = users.getJSONObject(0)
        assertThat(first.getString("fn")).isEqualTo(profiles[0].name)
        assertThat(first.getDouble("ht")).isEqualTo(1.8)
        assertThat(first.getDouble("wt")).isEqualTo(70.0)
        assertThat(first.has("ft")).isFalse() // never invent feature flags
    }

    @Test fun `approval is limited to its device version and ten minute window`() {
        val approval = Roster.approval(target, listOf(profile), createdAt = 1_000_000)
        assertThat(Roster.approvedProfiles(approval, target.lowercase(), now = 1_100_000)).containsExactly(profile)
        assertThrows(IllegalArgumentException::class.java) { Roster.approvedProfiles(approval, "AA:BB:CC:DD:EE:02", 1_100_000) }
        assertThrows(IllegalArgumentException::class.java) { Roster.approvedProfiles(approval, target, 1_600_001) }
        assertThrows(IllegalArgumentException::class.java) { Roster.approvedProfiles(approval, target, 900_000) }
        val changed = JSONObject(approval).put("version", 2).toString()
        assertThrows(IllegalArgumentException::class.java) { Roster.approvedProfiles(changed, target, 1_100_000) }
    }
    @Test fun `profiles without a screen catalogue do not invent enable commands`() {
        val fresh = profile.json()
        assertThat(fresh.has("cs")).isFalse()
        assertThat(fresh.has("ft")).isFalse()
        val migrated = Roster.decodeDraft("[$fresh]").single()
        assertThat(migrated.id).isEqualTo(profile.id)
        assertThat(migrated.json().has("cs")).isFalse()
    }

    @Test fun `editing and approving a profile preserves all device fields including unknown screen selectors`() {
        val original = profile.json().put("cs", "11,6,1,999")
            .put("ft", org.json.JSONArray("[42,99]"))
            .put("cr", JSONObject().put("time", 123).put("value", 70))
            .put("future", JSONObject().put("nested", org.json.JSONArray("[true,null,\"Müller\"]")))
        val edited = Roster.decodeDraft("[$original]").single().copy(name = "Renamed", weightKg = 72.0)
        val approved = Roster.approvedProfiles(Roster.approval(target, listOf(edited), 1_000_000), target, 1_000_000)
        val frames = Roster.messages(approved, "test")
        val text = frames.flatMap { Wpp.Decoder().feed(it).single().objects }
            .filter { it.type == Roster.CHUNK_TYPE }
            .joinToString("") { it.value.drop(1).toByteArray().toString(Charsets.UTF_8) }
        val sent = JSONObject(text.dropLast(2)).getJSONObject("body").getJSONObject("sp").getJSONArray("users").getJSONObject(0)
        assertThat(sent.getString("fn")).isEqualTo("Renamed")
        assertThat(sent.getDouble("wt")).isEqualTo(72.0)
        for (key in listOf("cs", "ft", "cr", "future")) {
            assertThat(sent.get(key).toString()).isEqualTo(original.get(key).toString())
        }
    }

}
