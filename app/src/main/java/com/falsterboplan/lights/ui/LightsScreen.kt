package com.falsterboplan.lights.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.falsterboplan.lights.data.Scene
import com.falsterboplan.lights.data.WEEK
import com.falsterboplan.lights.timer.LightTimer
import java.text.DateFormat
import java.util.Date
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.falsterboplan.lights.data.Device
import kotlin.math.roundToInt

private const val NO_ROOM = ""

private sealed interface Row {
    data class RoomHeader(
        val id: String,
        val name: String,
        val anyOn: Boolean,
        val canSwitch: Boolean,
        val lightIds: List<String>,
    ) : Row
    data class Light(val device: Device) : Row
}

private fun rowsOf(lights: List<Device>): List<Row> = lights
    .groupBy { it.room?.id ?: NO_ROOM }
    .toList()
    .sortedWith(compareBy({ it.first == NO_ROOM }, { it.second.first().room?.name.orEmpty().lowercase() }))
    .flatMap { (roomId, members) ->
        val header = Row.RoomHeader(
            id = roomId,
            name = members.first().room?.name?.ifBlank { null } ?: "No room",
            anyOn = members.any { it.attributes.isOn && it.isReachable },
            canSwitch = roomId != NO_ROOM && members.any { it.isReachable },
            lightIds = members.map { it.id },
        )
        listOf(header) + members.sortedBy { it.name.lowercase() }.map { Row.Light(it) }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightsScreen(state: UiState, vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    var timerTarget by remember { mutableStateOf<Target?>(null) }
    timerTarget?.let { TimerDialog(it, vm, onDismiss = { timerTarget = null }) }

    var addLamp by remember { mutableStateOf(false) }
    if (addLamp) {
        AddLampDialog(state.addLamp, vm, onDismiss = {
            addLamp = false
            vm.stopAddLamp()
        })
    }

    var confirmChaos by remember { mutableStateOf(false) }
    if (confirmChaos) {
        AlertDialog(
            onDismissRequest = { confirmChaos = false },
            title = { Text("CHAOS") },
            text = { Text("Are you sure you want to start chaos mode?") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmChaos = false
                        vm.startChaos()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CHAOS_RED, contentColor = Color.White),
                ) { Text("CHAOS", fontWeight = FontWeight.Black) }
            },
            dismissButton = { TextButton(onClick = { confirmChaos = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        bottomBar = {
            Button(
                onClick = { confirmChaos = true },
                enabled = !state.chaos && state.lights.any { it.isReachable },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CHAOS_RED,
                    contentColor = Color.White,
                    disabledContainerColor = CHAOS_RED.copy(alpha = 0.5f),
                    disabledContentColor = Color.White,
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                Text(if (state.chaos) "CHAOS…" else "CHAOS", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Lights") },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Add a lamp") },
                            onClick = {
                                menu = false
                                addLamp = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Forget hub") },
                            onClick = {
                                menu = false
                                vm.forgetHub()
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val rows = remember(state.lights) { rowsOf(state.lights) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (rows.isEmpty() && !state.loading) {
                    item {
                        Text(
                            "No lights found on this hub. Pull down to refresh.",
                            modifier = Modifier.padding(top = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(
                    rows,
                    key = { if (it is Row.Light) "l:${it.device.id}" else "r:${(it as Row.RoomHeader).id}" },
                    contentType = { it is Row.Light },
                ) { row ->
                    when (row) {
                        is Row.RoomHeader -> RoomHeader(
                            row,
                            onToggle = { vm.setRoomOn(row.id, it) },
                            onTimer = { timerTarget = Target(row.name, row.lightIds) },
                        )
                        is Row.Light -> LightCard(
                            row.device,
                            vm,
                            onTimer = { timerTarget = Target(row.device.name, listOf(row.device.id)) },
                        )
                    }
                }
                if (state.timers.isNotEmpty() || state.schedules.isNotEmpty()) {
                    item(key = "planned") {
                        Text(
                            "Timers and schedules",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                        )
                    }
                    items(state.timers, key = { "t:${it.id}" }) { TimerRow(it, onCancel = { vm.cancelTimer(it.id) }) }
                    items(state.schedules, key = { "s:${it.id}" }) { ScheduleRow(it, onDelete = { vm.deleteSchedule(it.id) }) }
                }
            }
        }
    }
}

@Composable
private fun RoomHeader(room: Row.RoomHeader, onToggle: (Boolean) -> Unit, onTimer: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(room.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (room.canSwitch) {
            IconButton(onClick = onTimer) {
                Icon(Icons.Outlined.Schedule, contentDescription = "Timer or schedule for ${room.name}")
            }
            Switch(
                checked = room.anyOn,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = "All lights in ${room.name}" },
            )
        }
    }
}

@Composable
private fun LightCard(device: Device, vm: MainViewModel, onTimer: () -> Unit) {
    val attrs = device.attributes
    val on = attrs.isOn && device.isReachable
    var expanded by remember { mutableStateOf(false) }
    val hasExtras = device.canSetTemperature || device.canSetColor

    Card(Modifier.fillMaxWidth().alpha(if (device.isReachable) 1f else 0.5f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = device.isReachable) { expanded = !expanded },
            ) {
                Icon(
                    if (on) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = if (on) glowOf(device) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            !device.isReachable -> "Unreachable"
                            !on -> "Off"
                            attrs.lightLevel != null -> "${attrs.lightLevel}%"
                            else -> "On"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (device.isReachable) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide controls" else if (hasExtras) "Colour, warmth and timer" else "Timer",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = { vm.setOn(device, it) },
                    enabled = device.isReachable,
                    modifier = Modifier.semantics { contentDescription = device.name },
                )
            }

            if (device.canDim && on) {
                CommitSlider(
                    value = (attrs.lightLevel ?: 100).toFloat(),
                    range = 1f..100f,
                    label = "Brightness of ${device.name}",
                    onCommit = { vm.setLevel(device, it.roundToInt()) },
                )
            }

            AnimatedVisibility(visible = expanded && device.isReachable) {
                Column {
                    if (hasExtras && !on) {
                        Text(
                            "Turn the light on to change its colour.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (device.canSetTemperature && on) {
                        val range = device.temperatureRange
                        Text("Warmth", style = MaterialTheme.typography.labelMedium)
                        CommitSlider(
                            value = (attrs.colorTemperature ?: range.first)
                                .coerceIn(range.first, range.last).toFloat(),
                            range = range.first.toFloat()..range.last.toFloat(),
                            label = "Colour temperature of ${device.name}",
                            brush = Brush.horizontalGradient(listOf(WARM, Color.White, COOL)),
                            onCommit = { vm.setTemperature(device, it.roundToInt()) },
                        )
                    }
                    if (device.canSetColor && on) {
                        val hue = (attrs.colorHue ?: 0.0).toFloat().coerceIn(0f, 359f)
                        val saturation = (attrs.colorSaturation ?: 1.0).toFloat().coerceIn(0f, 1f)
                        Text("Colour", style = MaterialTheme.typography.labelMedium)
                        CommitSlider(
                            value = hue,
                            range = 0f..359f,
                            label = "Colour of ${device.name}",
                            brush = RAINBOW,
                            onCommit = { vm.setColor(device, it.toDouble(), saturation.coerceAtLeast(0.2f).toDouble()) },
                        )
                        Text("Intensity", style = MaterialTheme.typography.labelMedium)
                        CommitSlider(
                            value = saturation,
                            range = 0f..1f,
                            label = "Colour intensity of ${device.name}",
                            brush = Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))),
                            onCommit = { vm.setColor(device, hue.toDouble(), it.toDouble()) },
                        )
                    }
                    TextButton(onClick = onTimer) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Timer or schedule")
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerRow(timer: LightTimer, onCancel: () -> Unit) {
    val at = remember(timer.atMillis) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timer.atMillis)) }
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
            headlineContent = { Text("${timer.label} ${if (timer.on) "on" else "off"} at $at") },
            supportingContent = { Text("Timer, from this phone") },
            trailingContent = {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel timer") }
            },
        )
    }
}

@Composable
private fun ScheduleRow(scene: Scene, onDelete: () -> Unit) {
    val days = scene.timeTrigger?.days
    val daysText = when {
        days == null || days.size == 7 -> "Every day"
        days == WEEK.take(5) -> "Weekdays"
        days == WEEK.drop(5) -> "Weekends"
        else -> days.joinToString(", ")
    }
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
            headlineContent = { Text(scene.info.name) },
            supportingContent = { Text("$daysText, on the hub") },
            trailingContent = {
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = "Delete schedule") }
            },
        )
    }
}

/** A slider that tracks the finger locally and only talks to the hub when the drag ends. */
@Composable
private fun CommitSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    brush: Brush? = null,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    if (!dragging) local = value
    Box(contentAlignment = Alignment.Center) {
        if (brush != null) {
            Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(brush))
        }
        Slider(
            value = local.coerceIn(range),
            onValueChange = {
                dragging = true
                local = it
            },
            onValueChangeFinished = {
                dragging = false
                onCommit(local)
            },
            valueRange = range,
            colors = if (brush == null) SliderDefaults.colors() else SliderDefaults.colors(
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private val RAINBOW = Brush.horizontalGradient((0..360 step 30).map { Color.hsv(it.toFloat().coerceAtMost(359f), 1f, 1f) })
private val CHAOS_RED = Color(0xFFD50000)

private val WARM = Color(0xFFFFB347)
private val COOL = Color(0xFFBFD9FF)

/** Approximate colour of the light the bulb is giving off, for the icon tint. */
private fun glowOf(device: Device): Color {
    val a = device.attributes
    val hue = a.colorHue
    val temperature = a.colorTemperature
    return when {
        a.colorMode == "color" && hue != null ->
            Color.hsv(hue.toFloat().coerceIn(0f, 360f), (a.colorSaturation ?: 1.0).toFloat().coerceIn(0.3f, 1f), 1f)
        temperature != null -> {
            val range = device.temperatureRange
            val span = (range.last - range.first).coerceAtLeast(1)
            lerp(WARM, COOL, ((temperature - range.first).toFloat() / span).coerceIn(0f, 1f))
        }
        else -> WARM
    }
}
