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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.falsterboplan.lights.data.Moment
import com.falsterboplan.lights.data.Scene
import com.falsterboplan.lights.data.WEEK
import com.falsterboplan.lights.data.days
import com.falsterboplan.lights.data.moment
import com.falsterboplan.lights.data.turnsOn

/** What a timer or schedule acts on: one light, or all the lights in a room. */
data class Target(val label: String, val lightIds: List<String>)

private enum class Mode(val label: String) { TIMER("Timer"), SCHEDULE("Schema"), WAKE("Vakna"), WIND("Varva ned") }

/**
 * Sets a timer, a schedule, a wake-up or a wind-down for [target]. With [editing] it changes
 * that existing schedule instead and only offers the schedule settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimerDialog(target: Target, vm: MainViewModel, onDismiss: () -> Unit, editing: Scene? = null) {
    val was = editing?.moment
    var mode by remember { mutableStateOf(if (editing != null) Mode.SCHEDULE else Mode.TIMER) }
    var turnOn by remember { mutableStateOf(editing?.turnsOn ?: false) }
    var minutes by remember { mutableStateOf("30") }
    var days by remember { mutableStateOf((editing?.days ?: WEEK).toSet()) }
    // 0 = clock time, 1 = sunrise, 2 = sunset
    var kind by remember { mutableIntStateOf(if (was is Moment.Sun) (if (was.sunrise) 1 else 2) else 0) }
    var offset by remember { mutableIntStateOf((was as? Moment.Sun)?.offsetMinutes ?: 0) }
    var fade by remember { mutableIntStateOf(20) }
    val clock = was as? Moment.Clock
    val time = rememberTimePickerState(initialHour = clock?.hour ?: 22, initialMinute = clock?.minute ?: 0, is24Hour = true)
    val wakeTime = rememberTimePickerState(initialHour = 7, initialMinute = 0, is24Hour = true)

    val valid = when (mode) {
        Mode.TIMER -> (minutes.toIntOrNull() ?: 0) in 1..(7 * 24 * 60)
        else -> days.isNotEmpty()
    }
    val chosenDays = WEEK.filter { it in days }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "Ändra schema" else target.label) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (editing != null) {
                    Text(editing.info.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mode.entries.forEach { m ->
                            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                        }
                    }
                }

                when (mode) {
                    Mode.TIMER -> {
                        Choice(listOf("Släck", "Tänd"), if (turnOn) 1 else 0) { turnOn = it == 1 }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60, 120).forEach { value ->
                                FilterChip(
                                    selected = minutes == value.toString(),
                                    onClick = { minutes = value.toString() },
                                    label = { Text(duration(value)) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = minutes,
                            onValueChange = { minutes = it.filter(Char::isDigit).take(5) },
                            label = { Text("Minuter från nu") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Note("Körs en gång, från den här mobilen. Mobilen måste vara på hemmets wifi när tiden är ute.")
                    }

                    Mode.SCHEDULE -> {
                        Choice(listOf("Släck", "Tänd"), if (turnOn) 1 else 0) { turnOn = it == 1 }
                        Choice(listOf("Klockslag", "Soluppgång", "Solnedgång"), kind) { kind = it }
                        if (kind == 0) {
                            TimeInput(state = time)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(-60 to "1 h före", -30 to "30 min före", 0 to "Precis då", 30 to "30 min efter", 60 to "1 h efter")
                                    .forEach { (value, label) ->
                                        FilterChip(selected = offset == value, onClick = { offset = value }, label = { Text(label) })
                                    }
                            }
                            Note("Hubben räknar ut solens tider från platsen som är inställd i IKEA-appen.")
                        }
                        DayChips(days) { days = it }
                        Note("Upprepas varje vecka. Sparas i hubben och körs även när mobilen är borta.")
                    }

                    Mode.WAKE -> {
                        Label("Fullt ljus klockan")
                        TimeInput(state = wakeTime)
                        FadeChips(listOf(10, 20, 30), fade) { fade = it }
                        DayChips(days) { days = it }
                        Note("Lampan tänds svagt och blir starkare i fem steg fram till klockslaget. Sparas i hubben som fem scheman.")
                    }

                    Mode.WIND -> {
                        Label("Börja dämpa klockan")
                        TimeInput(state = time)
                        FadeChips(listOf(10, 20, 30, 60), fade) { fade = it }
                        DayChips(days) { days = it }
                        Note("Lampan dämpas i steg från klockslaget och släcks sedan. Sparas i hubben som fem scheman.")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    when (mode) {
                        Mode.TIMER -> vm.addTimer(target.label, target.lightIds, turnOn, minutes.toInt())
                        Mode.SCHEDULE -> {
                            val moment = if (kind == 0) Moment.Clock(time.hour, time.minute) else Moment.Sun(kind == 1, offset)
                            if (editing != null) {
                                vm.updateSchedule(editing, turnOn, moment, chosenDays)
                            } else {
                                vm.addSchedule(target.label, target.lightIds, turnOn, moment, chosenDays)
                            }
                        }
                        Mode.WAKE -> vm.addWakeUp(target.label, target.lightIds, wakeTime.hour, wakeTime.minute, fade, chosenDays)
                        Mode.WIND -> vm.addWindDown(target.label, target.lightIds, time.hour, time.minute, fade, chosenDays)
                    }
                    onDismiss()
                },
            ) { Text(if (editing != null) "Spara" else "Ställ in") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayChips(days: Set<String>, onChange: (Set<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WEEK.forEach { day ->
            FilterChip(
                selected = day in days,
                onClick = { onChange(if (day in days) days - day else days + day) },
                label = { Text(DAY_SV[day] ?: day) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FadeChips(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Label("Under så lång tid")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { value ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(duration(value)) })
        }
    }
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
                icon = {},
            ) { Text(label, maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
