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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.falsterboplan.lights.R
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.falsterboplan.lights.data.Device
import com.falsterboplan.lights.data.Room
import kotlin.math.roundToInt

private const val NO_ROOM = ""

private sealed interface Row {
    data class RoomHeader(
        val id: String,
        val name: String,
        val anyOn: Boolean,
        val canSwitch: Boolean,
        val lightIds: List<String>,
        val onCount: Int,
    ) : Row
    data class Light(val device: Device) : Row
    data class Empty(val roomId: String) : Row
}

private fun rowsOf(lights: List<Device>, rooms: List<Room>): List<Row> {
    val byRoom = lights.groupBy { it.room?.id ?: NO_ROOM }
    // Every room the hub knows, plus any that only appear on a light, then lights with no room.
    val names = LinkedHashMap<String, String>()
    rooms.forEach { names[it.id] = it.name }
    lights.forEach { l -> l.room?.let { names.putIfAbsent(it.id, it.name) } }
    val ordered = names.entries.sortedBy { it.value.lowercase() }.map { it.key to it.value } +
        (if (NO_ROOM in byRoom) listOf(NO_ROOM to "No room") else emptyList())
    return ordered.flatMap { (roomId, name) ->
        val members = byRoom[roomId].orEmpty()
        val header = Row.RoomHeader(
            id = roomId,
            name = name.ifBlank { "Room" },
            anyOn = members.any { it.attributes.isOn && it.isReachable },
            canSwitch = roomId != NO_ROOM && members.any { it.isReachable },
            lightIds = members.map { it.id },
            onCount = members.count { it.attributes.isOn && it.isReachable },
        )
        listOf(header) + if (members.isEmpty()) {
            listOf(Row.Empty(roomId))
        } else {
            members.sortedBy { it.name.lowercase() }.map { Row.Light(it) }
        }
    }
}

/** The Lights tab: rooms and lights, then timers and schedules. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightsTab(state: UiState, vm: MainViewModel) {
    var timerTarget by remember { mutableStateOf<Target?>(null) }
    timerTarget?.let { TimerDialog(it, vm, onDismiss = { timerTarget = null }) }

    var renaming by remember { mutableStateOf<Device?>(null) }
    renaming?.let { RenameDialog(it, vm, onDismiss = { renaming = null }) }

    var moving by remember { mutableStateOf<Device?>(null) }
    moving?.let { MoveDialog(it, state.rooms, vm, onDismiss = { moving = null }) }

    Column(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val rows = remember(state.lights, state.rooms) { rowsOf(state.lights, state.rooms) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    key = {
                        when (it) {
                            is Row.Light -> "l:${it.device.id}"
                            is Row.RoomHeader -> "r:${it.id}"
                            is Row.Empty -> "e:${it.roomId}"
                        }
                    },
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
                            onRename = { renaming = row.device },
                            onMove = { moving = row.device },
                        )
                        is Row.Empty -> Text(
                            "No lights here yet. Open a light and choose Move to put it in this room.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp),
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
        if (room.onCount > 0) {
            Text(
                "${room.onCount} of ${room.lightIds.size} on",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(Brand.Mint, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (room.canSwitch) {
            IconButton(onClick = onTimer) {
                Icon(Icons.Outlined.Schedule, contentDescription = "Timer or schedule for ${room.name}")
            }
            Switch(
                checked = room.anyOn,
                onCheckedChange = onToggle,
                colors = brandSwitchColors(),
                modifier = Modifier.semantics { contentDescription = "All lights in ${room.name}" },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightCard(
    device: Device,
    vm: MainViewModel,
    onTimer: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
) {
    val attrs = device.attributes
    val on = attrs.isOn && device.isReachable
    var expanded by remember { mutableStateOf(false) }
    val hasExtras = device.canSetTemperature || device.canSetColor

    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (device.isReachable) 1f else 0.55f),
        border = if (expanded && device.isReachable) BorderStroke(2.dp, Brand.Mint) else null,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = device.isReachable) { expanded = !expanded },
            ) {
                // Brand bulb drawing; the small dot shows roughly the colour the light is giving off.
                Box(Modifier.size(52.dp)) {
                    Image(
                        painterResource(if (on) R.drawable.bulb_on else R.drawable.bulb_off),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (on && hasExtras) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(16.dp)
                                .background(glowOf(device), CircleShape)
                                .border(2.dp, Brand.Card, CircleShape),
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            !device.isReachable -> "Unreachable"
                            !on -> "Off"
                            attrs.lightLevel != null -> "${attrs.lightLevel}%" + lookOf(device)
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
                    colors = brandSwitchColors(),
                    modifier = Modifier.semantics { contentDescription = device.name },
                )
            }

            if (device.canDim && on) {
                SliderLabel("Brightness")
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
                        SliderLabel("Warmth")
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
                        SliderLabel("Colour")
                        CommitSlider(
                            value = hue,
                            range = 0f..359f,
                            label = "Colour of ${device.name}",
                            brush = RAINBOW,
                            onCommit = { vm.setColor(device, it.toDouble(), saturation.coerceAtLeast(0.2f).toDouble()) },
                        )
                        SliderLabel("Intensity")
                        CommitSlider(
                            value = saturation,
                            range = 0f..1f,
                            label = "Colour intensity of ${device.name}",
                            brush = Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))),
                            onCommit = { vm.setColor(device, hue.toDouble(), it.toDouble()) },
                        )
                    }
                    FlowRow(Modifier.padding(top = 4.dp)) {
                        TextButton(onClick = onTimer) {
                            Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Timer or schedule")
                        }
                        TextButton(onClick = onRename) { Text("Rename") }
                        TextButton(onClick = onMove) { Text("Move") }
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

@Composable
private fun SliderLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** " · warm", " · colour" and so on, for the line under a light's name. */
private fun lookOf(device: Device): String {
    val a = device.attributes
    val temperature = a.colorTemperature
    return when {
        device.canSetColor && a.colorMode == "color" -> " · colour"
        device.canSetTemperature && temperature != null -> {
            val range = device.temperatureRange
            val t = (temperature - range.first).toFloat() / (range.last - range.first).coerceAtLeast(1)
            if (t < 0.35f) " · warm" else if (t > 0.65f) " · cool" else " · neutral"
        }
        else -> ""
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
            colors = if (brush == null) {
                SliderDefaults.colors(thumbColor = Brand.Ink, activeTrackColor = Brand.Pink, inactiveTrackColor = Brand.Sand)
            } else {
                SliderDefaults.colors(
                    thumbColor = Brand.Ink,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                )
            },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private val RAINBOW = Brush.horizontalGradient((0..360 step 30).map { Color.hsv(it.toFloat().coerceAtMost(359f), 1f, 1f) })

private val WARM = Color(0xFFFFC870)
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
