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
import androidx.compose.material.icons.filled.Lightbulb
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
    data class RoomHeader(val id: String, val name: String, val anyOn: Boolean, val canSwitch: Boolean) : Row
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

    Scaffold(
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
                        is Row.RoomHeader -> RoomHeader(row, onToggle = { vm.setRoomOn(row.id, it) })
                        is Row.Light -> LightCard(row.device, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomHeader(room: Row.RoomHeader, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(room.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (room.canSwitch) {
            Switch(
                checked = room.anyOn,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = "All lights in ${room.name}" },
            )
        }
    }
}

@Composable
private fun LightCard(device: Device, vm: MainViewModel) {
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
                    .clickable(enabled = hasExtras && on) { expanded = !expanded },
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

            AnimatedVisibility(visible = expanded && on && hasExtras) {
                Column {
                    if (device.canSetTemperature) {
                        val range = device.temperatureRange
                        Text("Warmth", style = MaterialTheme.typography.labelMedium)
                        CommitSlider(
                            value = (attrs.colorTemperature ?: range.first)
                                .coerceIn(range.first, range.last).toFloat(),
                            range = range.first.toFloat()..range.last.toFloat(),
                            label = "Colour temperature of ${device.name}",
                            onCommit = { vm.setTemperature(device, it.roundToInt()) },
                        )
                    }
                    if (device.canSetColor) {
                        Text("Colour", style = MaterialTheme.typography.labelMedium)
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            SWATCHES.forEach { (name, hue) ->
                                val selected = attrs.colorMode == "color" &&
                                    attrs.colorHue?.let { kotlin.math.abs(it - hue) < 1 } == true
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color.hsv(hue, 0.85f, 1f))
                                        .border(
                                            width = if (selected) 3.dp else 0.dp,
                                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape,
                                        )
                                        .clickable { vm.setColor(device, hue.toDouble(), 1.0) }
                                        .semantics { contentDescription = name },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A slider that tracks the finger locally and only talks to the hub when the drag ends. */
@Composable
private fun CommitSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    if (!dragging) local = value
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
        modifier = Modifier.semantics { contentDescription = label },
    )
}

private val SWATCHES = listOf(
    "Red" to 0f, "Orange" to 30f, "Yellow" to 55f, "Green" to 120f,
    "Cyan" to 180f, "Blue" to 230f, "Purple" to 275f, "Pink" to 320f,
)

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
