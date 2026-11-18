package com.falsterboplan.lights.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/** Save the current light settings as a scene, and remove scenes that are no longer wanted. */
@Composable
fun SceneDialog(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    // null = every light in the home
    var roomId by remember { mutableStateOf<String?>(null) }
    val rooms = state.rooms.filter { r -> state.lights.any { it.room?.id == r.id } }.sortedBy { it.name.lowercase() }

    @Composable
    fun Scope(id: String?, label: String) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .selectable(selected = roomId == id, role = Role.RadioButton, onClick = { roomId = id }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = roomId == id, onClick = null)
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scener") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Spara hur lamporna lyser just nu, så kan du få tillbaka det med ett tryck.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("Scenens namn") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Scope(null, "Alla lampor")
                rooms.forEach { Scope(it.id, it.name) }
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.saveScene(name, state.lights.filter { roomId == null || it.room?.id == roomId }.map { it.id })
                        name = ""
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Spara scen") }

                if (state.scenes.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Sparade scener", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    state.scenes.forEach { scene ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(scene.info.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteScene(scene.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Ta bort ${scene.info.name}")
                            }
                        }
                    }
                    Text(
                        "Här visas även scener som skapats i IKEA-appen. Tar du bort en försvinner den där också.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Klar") } },
    )
}

/** Choose which rooms should look lived in while nobody is home. */
@Composable
fun AwayDialog(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val active = state.routines.firstOrNull { it.kind == "away" }
    val rooms = state.rooms.filter { r -> state.lights.any { it.room?.id == r.id } }.sortedBy { it.name.lowercase() }
    var chosen by remember { mutableStateOf(rooms.map { it.id }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bortaläge") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (active != null) {
                    Text("Bortaläge är på för: ${active.summary}.")
                    Text("Rummen tänds runt solnedgången och släcks sent på kvällen, på lite olika tider.")
                } else {
                    Text(
                        "Valda rum tänds runt solnedgången och släcks mellan 22.30 och midnatt, på tider som " +
                            "skiljer sig mellan rum och dagar. Hubben sköter det, så mobilen kan följa med på resan.",
                    )
                    rooms.forEach { room ->
                        val checked = room.id in chosen
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { chosen = if (it) chosen + room.id else chosen - room.id },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(room.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    if (rooms.isEmpty()) Text("Det finns inga rum med lampor än.")
                }
            }
        },
        confirmButton = {
            if (active != null) {
                TextButton(onClick = {
                    vm.deleteRoutine(active.id)
                    onDismiss()
                }) { Text("Stäng av bortaläge") }
            } else {
                TextButton(
                    enabled = chosen.isNotEmpty(),
                    onClick = {
                        vm.startAway(rooms.filter { it.id in chosen }.map { it.id })
                        onDismiss()
                    },
                ) { Text("Starta") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (active != null) "Stäng" else "Avbryt") } },
    )
}
