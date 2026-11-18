package com.falsterboplan.lights.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.falsterboplan.lights.presence.Presence
import com.falsterboplan.lights.presence.PresenceStore

/** Settings for what happens when the phone comes home or leaves. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresenceDialog(state: UiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PresenceStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    val rooms = state.rooms.filter { r -> state.lights.any { it.room?.id == r.id } }.sortedBy { it.name.lowercase() }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    @Composable
    fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = checked, onCheckedChange = null, colors = brandSwitchColors())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hemma och borta") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle("Tänd när jag kommer hem på kvällen", settings.arrive) { settings = settings.copy(arrive = it) }
                if (settings.arrive) {
                    Text("Tänd de här rummen", style = MaterialTheme.typography.labelLarge)
                    rooms.forEach { room ->
                        val checked = room.id in settings.roomIds
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = {
                                    settings = settings.copy(roomIds = if (it) settings.roomIds + room.id else settings.roomIds - room.id)
                                },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(room.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    Text("Kvällen börjar", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 16, 17, 18, 19, 20).forEach { hour ->
                            FilterChip(
                                selected = settings.eveningFrom == hour * 60,
                                onClick = { settings = settings.copy(eveningFrom = hour * 60) },
                                label = { Text("%02d:00".format(hour)) },
                            )
                        }
                    }
                    Text("Tänder bara om allt är släckt och du varit borta i minst tio minuter.", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Toggle("Påminn om lampor är tända när jag går", settings.leaveReminder) { settings = settings.copy(leaveReminder = it) }
                Text(
                    "Appen märker att du är hemma genom att hubben svarar. Den kollar ungefär var 15:e minut och när " +
                        "mobilen ansluter till wifi, så påminnelsen kan dröja en stund. Lamporna kan inte släckas på " +
                        "distans, eftersom hubben bara nås hemifrån.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.settings = settings
                Presence.apply(context)
                if (settings.leaveReminder && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                onDismiss()
            }) { Text("Spara") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}
