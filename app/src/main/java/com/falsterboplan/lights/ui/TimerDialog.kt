package com.falsterboplan.lights.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.falsterboplan.lights.data.WEEK

/** What a timer or schedule acts on: one light, or all the lights in a room. */
data class Target(val label: String, val lightIds: List<String>)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimerDialog(target: Target, vm: MainViewModel, onDismiss: () -> Unit) {
    var schedule by remember { mutableStateOf(false) }
    var turnOn by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf("30") }
    var days by remember { mutableStateOf(WEEK.toSet()) }
    val time = rememberTimePickerState(initialHour = 22, initialMinute = 0, is24Hour = true)

    val valid = if (schedule) days.isNotEmpty() else (minutes.toIntOrNull() ?: 0) in 1..(7 * 24 * 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.label) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Choice(listOf("Timer", "Schedule"), if (schedule) 1 else 0) { schedule = it == 1 }
                Choice(listOf("Turn off", "Turn on"), if (turnOn) 1 else 0) { turnOn = it == 1 }

                if (!schedule) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h").forEach { (value, label) ->
                            FilterChip(
                                selected = minutes == value.toString(),
                                onClick = { minutes = value.toString() },
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(5) },
                        label = { Text("Minutes from now") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Note("Runs once, from this phone. The phone has to be on your home Wi-Fi when the time is up.")
                } else {
                    TimeInput(state = time)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WEEK.forEach { day ->
                            FilterChip(
                                selected = day in days,
                                onClick = { days = if (day in days) days - day else days + day },
                                label = { Text(day) },
                            )
                        }
                    }
                    Note("Repeats every week. Saved on the hub, so it runs even when your phone is away.")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (schedule) {
                        vm.addSchedule(target.label, target.lightIds, turnOn, time.hour, time.minute, WEEK.filter { it in days })
                    } else {
                        vm.addTimer(target.label, target.lightIds, turnOn, minutes.toInt())
                    }
                    onDismiss()
                },
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selected,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
