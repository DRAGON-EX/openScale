/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.scales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.libs.WithingsUserRoster as Roster

/** A local draft editor. A single, explicit replacement approval queues device I/O. */
@Composable
internal fun WithingsUserRosterUi(
    target: String,
    initialDraft: String,
    queued: Boolean,
    result: String?,
    onSave: (String) -> Unit,
    onQueue: (String) -> Unit,
    onCancel: () -> Unit,
    onResetMapping: () -> Unit
) {
    var profiles by remember(target) {
        mutableStateOf(runCatching { Roster.decodeDraft(initialDraft) }.getOrDefault(emptyList()))
    }
    var editIndex by remember(target) { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var understood by remember { mutableStateOf(false) }
    var mappingReset by remember(target) { mutableStateOf(false) }

    fun save(updated: List<Roster.Profile>) {
        profiles = updated
        onSave(Roster.encodeDraft(updated))
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.body_smart_roster_explanation),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (target.isEmpty()) Text(stringResource(R.string.body_smart_roster_connect_first))
        else {
            Text(stringResource(R.string.body_smart_roster_title), style = MaterialTheme.typography.titleSmall)
            if (profiles.isEmpty()) {
                WithingsStatusCard(stringResource(R.string.body_smart_roster_empty),
                    stringResource(R.string.body_smart_roster_empty_description))
            }
            profiles.forEachIndexed { index, profile ->
                ProfileCard(profile, onEdit = { editIndex = index },
                    onRemove = { save(profiles.filterIndexed { i, _ -> i != index }) })
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(),
                    onClick = { editIndex = profiles.size }, enabled = profiles.size < 8) {
                    Text(stringResource(R.string.body_smart_roster_add))
                }
                Button(modifier = Modifier.fillMaxWidth(), enabled = !queued,
                    onClick = { understood = false; confirm = true }) {
                    Text(stringResource(R.string.body_smart_roster_apply))
                }
            }
            if (queued) {
                WithingsStatusCard(stringResource(R.string.body_smart_pending_title),
                    stringResource(R.string.body_smart_roster_queued), onCancel)
            } else {
                val message = when (result) {
                    "acknowledged" -> R.string.body_smart_roster_acknowledged
                    "sending", "profiles-acknowledged" -> R.string.body_smart_roster_waiting
                    "screens-rejected", "screens-timeout", "screens-incomplete" -> R.string.body_smart_screens_incomplete
                    "rejected" -> R.string.body_smart_roster_rejected
                    "invalid" -> R.string.body_smart_roster_invalid_approval
                    null, "" -> null
                    else -> R.string.body_smart_roster_timeout
                }
                message?.let { WithingsStatusCard(stringResource(R.string.body_smart_last_user_sync), stringResource(it)) }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.body_smart_assignment_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (mappingReset) R.string.body_smart_assignment_queued else R.string.body_smart_assignment_description),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !mappingReset,
                    onClick = { onResetMapping(); mappingReset = true }) {
                    Text(stringResource(R.string.body_smart_reset_user_choice))
                }
            }
        }
    }

    editIndex?.let { index ->
        RosterProfileDialog(profiles.getOrNull(index), profiles, onDismiss = { editIndex = null }) { profile ->
            val updated = profiles.toMutableList()
            if (index == updated.size) updated += profile else updated[index] = profile
            save(updated)
            editIndex = null
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.body_smart_roster_replace_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.body_smart_roster_replace_warning))
                Text(stringResource(R.string.body_smart_roster_target, target), style = MaterialTheme.typography.bodySmall)
                if (profiles.isEmpty()) Text(stringResource(R.string.body_smart_roster_empty))
                profiles.forEach { ProfileCard(it) }
                WithingsConsentRow(stringResource(R.string.body_smart_roster_confirm_understood), understood) { understood = it }
            }
        },
        confirmButton = {
            TextButton(enabled = understood, onClick = {
                onQueue(Roster.approval(target, profiles))
                confirm = false
            }) { Text(stringResource(R.string.body_smart_roster_queue)) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel_button)) } }
    )
}

@Composable
private fun ProfileCard(profile: Roster.Profile, onEdit: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(profile.shortName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                onEdit?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.body_smart_roster_edit_named, profile.name))
                    }
                }
                onRemove?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.body_smart_roster_remove_named, profile.name))
                    }
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileDetail(stringResource(R.string.body_smart_weight_label), stringResource(R.string.body_smart_weight_value, profile.weightKg), Modifier.weight(1f))
                ProfileDetail(stringResource(R.string.body_smart_height_label), stringResource(R.string.body_smart_height_value, profile.heightCm), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileDetail(stringResource(R.string.body_smart_age_label), stringResource(R.string.body_smart_age_value, profile.ageYears), Modifier.weight(1f))
                ProfileDetail(stringResource(R.string.body_smart_sex_label),
                    stringResource(if (profile.sex == 1) R.string.body_smart_profile_female else R.string.body_smart_profile_male), Modifier.weight(1f))
            }
            if (profile.athlete) Text(stringResource(R.string.body_smart_profile_athlete),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProfileDetail(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RosterProfileDialog(
    existing: Roster.Profile?, profiles: List<Roster.Profile>, onDismiss: () -> Unit, onSave: (Roster.Profile) -> Unit
) {
    val id = remember { existing?.id ?: Roster.newId(profiles) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var shortName by remember { mutableStateOf(existing?.shortName.orEmpty()) }
    var weight by remember { mutableStateOf(existing?.weightKg?.toString().orEmpty()) }
    var height by remember { mutableStateOf(existing?.heightCm?.toString().orEmpty()) }
    var age by remember { mutableStateOf(existing?.ageYears?.toString().orEmpty()) }
    var female by remember { mutableStateOf(existing?.sex == 1) }
    var athlete by remember { mutableStateOf(existing?.athlete ?: false) }
    val parsed = runCatching {
        Roster.Profile(id, name.trim(), shortName.trim().uppercase(), weight.replace(',', '.').toDouble(),
            height.replace(',', '.').toDouble(), age.replace(',', '.').toDouble(), if (female) 1 else 0, athlete,
            additionalFields = existing?.additionalFields ?: "{}").also { it.validate() }
    }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.body_smart_roster_add else R.string.body_smart_roster_edit)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.body_smart_profile_name)) })
                OutlinedTextField(value = shortName,
                    onValueChange = { shortName = it.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(3) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.body_smart_profile_short_name)) },
                    supportingText = { Text(stringResource(R.string.body_smart_profile_short_name_hint)) })
                ProfileNumberField(weight, { weight = it }, stringResource(R.string.body_smart_profile_weight),
                    stringResource(R.string.body_smart_profile_weight_hint), 0.1..200.0)
                ProfileNumberField(height, { height = it }, stringResource(R.string.body_smart_profile_height),
                    stringResource(R.string.body_smart_profile_height_hint), 20.0..250.0)
                ProfileNumberField(age, { age = it }, stringResource(R.string.body_smart_profile_age),
                    stringResource(R.string.body_smart_profile_age_hint), 0.0..120.0)
                Column(Modifier.selectableGroup()) {
                    Text(stringResource(R.string.body_smart_sex_label), style = MaterialTheme.typography.titleSmall)
                    listOf(false, true).forEach { value ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = female == value, role = Role.RadioButton, onClick = { female = value }),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = female == value, onClick = null)
                            Text(stringResource(if (value) R.string.body_smart_profile_female else R.string.body_smart_profile_male))
                        }
                    }
                }
                HorizontalDivider()
                WithingsSwitchRow(stringResource(R.string.body_smart_profile_athlete), athlete, { athlete = it })
                Text(stringResource(R.string.body_smart_profile_screen_status),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text(stringResource(R.string.body_smart_roster_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } }
    )
}

@Composable
private fun ProfileNumberField(value: String, onValueChange: (String) -> Unit, label: String, hint: String, range: ClosedFloatingPointRange<Double>) {
    OutlinedTextField(value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        label = { Text(label) }, supportingText = { Text(hint) },
        isError = value.isNotEmpty() && value.replace(',', '.').toDoubleOrNull()?.let { it in range } != true)
}
