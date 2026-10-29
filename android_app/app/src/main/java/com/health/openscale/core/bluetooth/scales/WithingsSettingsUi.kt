/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.core.bluetooth.scales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.libs.WithingsLocalSetup

@Composable
internal fun WithingsSettingsUi(
    target: String,
    automatic: Boolean,
    onAutomaticChange: (Boolean) -> Unit,
    useCurrentDate: Boolean,
    onCurrentDateChange: (Boolean) -> Unit,
    queued: Boolean,
    result: String?,
    onQueue: (String) -> Unit,
    onCancel: () -> Unit
) {
    var showSetup by remember(target) { mutableStateOf(false) }
    var understood by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.body_smart_instructions),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WithingsSwitchRow(
            title = stringResource(R.string.body_smart_automatic),
            description = stringResource(R.string.body_smart_automatic_description),
            checked = automatic, onCheckedChange = onAutomaticChange)
        HorizontalDivider()
        WithingsSwitchRow(
            title = stringResource(R.string.body_smart_current_date_title),
            description = stringResource(R.string.body_smart_current_date_option),
            checked = useCurrentDate, onCheckedChange = onCurrentDateChange)
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.body_smart_local_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.body_smart_local_description),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(), enabled = target.isNotBlank() && !queued,
                onClick = { understood = false; showSetup = true }
            ) { Text(stringResource(R.string.body_smart_local_setup)) }
        }
        if (queued) {
            WithingsStatusCard(stringResource(R.string.body_smart_pending_title),
                stringResource(R.string.body_smart_local_queued), onCancel)
        } else {
            val message = when (result) {
                "setup-acknowledged" -> R.string.body_smart_local_done
                "sending", "keys-acknowledged" -> R.string.body_smart_local_waiting
                "invalid" -> R.string.body_smart_local_expired
                null, "" -> null
                else -> R.string.body_smart_local_failed
            }
            message?.let { WithingsStatusCard(stringResource(R.string.body_smart_last_setup), stringResource(it)) }
        }
    }

    if (showSetup) AlertDialog(
        onDismissRequest = { showSetup = false },
        title = { Text(stringResource(R.string.body_smart_local_setup)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.body_smart_roster_target, target), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.body_smart_local_warning))
                WithingsConsentRow(stringResource(R.string.body_smart_local_understood), understood) { understood = it }
            }
        },
        confirmButton = {
            TextButton(enabled = understood, onClick = {
                onQueue(WithingsLocalSetup.approval(target, WithingsLocalSetup.create(), System.currentTimeMillis()))
                showSetup = false
            }) { Text(stringResource(R.string.body_smart_local_confirm)) }
        },
        dismissButton = { TextButton(onClick = { showSetup = false }) { Text(stringResource(R.string.cancel_button)) } }
    )
}

/** One accessible touch target keeps the label, supporting text and control aligned. */
@Composable
internal fun WithingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, description: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun WithingsConsentRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun WithingsStatusCard(title: String, description: String, onCancel: (() -> Unit)? = null) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onCancel?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.body_smart_roster_cancel))
                }
            }
        }
    }
}
