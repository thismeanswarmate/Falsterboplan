package com.falsterboplan.lights.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.falsterboplan.lights.data.Device
import com.falsterboplan.lights.data.Room

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(40)) },
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun RenameDialog(device: Device, vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(device.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename light") },
        text = { NameField(name, { name = it }, "Name") },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    vm.renameLight(device, name)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RenameRoomDialog(roomId: String, currentName: String, vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename room") },
        text = { NameField(name, { name = it }, "Room name") },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    vm.renameRoom(roomId, name)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NewRoomDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a room") },
        text = { NameField(name, { name = it }, "Room name") },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    vm.addRoom(name)
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick one of the hub's rooms for a light, or make a new room for it. */
@Composable
fun MoveDialog(device: Device, rooms: List<Room>, vm: MainViewModel, onDismiss: () -> Unit) {
    val sorted = remember(rooms) { rooms.sortedBy { it.name.lowercase() } }
    // null means "a new room".
    var selected by remember { mutableStateOf(device.room?.id ?: sorted.firstOrNull()?.id) }
    var newName by remember { mutableStateOf("") }

    @Composable
    fun Option(id: String?, label: String) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = selected == id, role = Role.RadioButton, onClick = { selected = id }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == id, onClick = null)
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${device.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sorted.forEach { Option(it.id, it.name.ifBlank { "Room" }) }
                Option(null, "New room")
                if (selected == null) NameField(newName, { newName = it }, "Room name")
            }
        },
        confirmButton = {
            TextButton(
                enabled = if (selected == null) newName.isNotBlank() else selected != device.room?.id,
                onClick = {
                    val room = sorted.firstOrNull { it.id == selected }
                    if (room != null) vm.moveLight(device, room) else vm.addRoom(newName, andMove = device)
                    onDismiss()
                },
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
