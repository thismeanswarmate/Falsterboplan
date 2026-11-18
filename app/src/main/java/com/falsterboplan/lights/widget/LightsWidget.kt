package com.falsterboplan.lights.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.SwitchDefaults
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.falsterboplan.lights.MainActivity
import com.falsterboplan.lights.R
import com.falsterboplan.lights.data.BuyItem
import com.falsterboplan.lights.data.Device
import com.falsterboplan.lights.data.Hub
import com.falsterboplan.lights.data.HubJson
import com.falsterboplan.lights.data.Priority
import com.falsterboplan.lights.data.Shopping
import com.falsterboplan.lights.data.forDisplay
import com.falsterboplan.lights.data.setRoomOrEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class WidgetRoom(
    /** Null for lights that aren't in a room. */
    val id: String?,
    val name: String,
    val lightIds: List<String>,
    val anyOn: Boolean,
    /** Average brightness of the lights that are on, 1..100; null if none can dim. */
    val level: Int? = null,
    /** Warmest and coolest Kelvin every warmth-capable light in the room accepts; null if none. */
    val warmK: Int? = null,
    val coolK: Int? = null,
    val hasColor: Boolean = false,
) {
    val key get() = id ?: OTHER

    companion object {
        const val OTHER = "~other"
    }
}

@Serializable
data class WidgetSnapshot(val paired: Boolean, val rooms: List<WidgetRoom> = emptyList()) {
    companion object {
        fun of(lights: List<Device>): WidgetSnapshot = WidgetSnapshot(
            paired = true,
            rooms = lights.filter { it.isReachable }
                .groupBy { it.room?.id }
                .map { (roomId, members) -> roomOf(roomId, members) }
                .sortedWith(compareBy({ it.id == null }, { it.name.lowercase() })),
        )

        private fun roomOf(roomId: String?, members: List<Device>): WidgetRoom {
            val levels = members.filter { it.canDim && it.attributes.isOn }.mapNotNull { it.attributes.lightLevel }
            val tunable = members.filter { it.canSetTemperature }
            val warm = tunable.maxOfOrNull { it.temperatureRange.first }
            val cool = tunable.minOfOrNull { it.temperatureRange.last }
            val hasRange = warm != null && cool != null && warm < cool
            return WidgetRoom(
                id = roomId,
                name = members.first().room?.name?.ifBlank { null } ?: "Övriga lampor",
                lightIds = members.map { it.id },
                anyOn = members.any { it.attributes.isOn },
                level = if (members.any { it.canDim }) levels.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 100 else null,
                warmK = warm.takeIf { hasRange },
                coolK = cool.takeIf { hasRange },
                hasColor = members.any { it.canSetColor },
            )
        }
    }
}

// Brand colours, as in ui/Theme.kt.
private val Cream = Color(0xFFF5EAD6)
private val Sand = Color(0xFFEBD9C6)
private val Ink = Color(0xFF54332A)
private val InkSoft = Color(0xFF7A5549)
private val Pink = Color(0xFFE94E76)
private val Clay = Color(0xFFC6A49A)
private val Mint = Color(0xFFC5E5D8)

private const val TAB_LIGHTS = "lights"
private const val TAB_BUY = "buy"

/** Colour swatches offered in the widget: name, hue in degrees. */
private val HUES = listOf("Rosa" to 340, "Orange" to 30, "Grön" to 120, "Blå" to 215, "Lila" to 275)

object WidgetUpdater {
    val SNAPSHOT = stringPreferencesKey("snapshot")
    val TAB = stringPreferencesKey("tab")
    val EXPANDED = stringPreferencesKey("expanded")

    fun decode(prefs: Preferences): WidgetSnapshot? =
        prefs[SNAPSHOT]?.let { runCatching { HubJson.decodeFromString<WidgetSnapshot>(it) }.getOrNull() }

    /** Asks the hub for the current state and redraws every placed widget. */
    suspend fun refresh(context: Context) {
        val client = Hub.client(context)
        if (client == null) {
            push(context, WidgetSnapshot(paired = false))
        } else {
            runCatching { client.lights() }.getOrNull()?.let { push(context, WidgetSnapshot.of(it)) }
        }
    }

    suspend fun push(context: Context, snapshot: WidgetSnapshot) {
        val json = HubJson.encodeToString(snapshot)
        GlanceAppWidgetManager(context).getGlanceIds(LightsWidget::class.java).forEach { id ->
            updateAppWidgetState(context, id) { it[SNAPSHOT] = json }
            LightsWidget().update(context, id)
        }
    }
}

class LightsWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val shopping = Shopping.items(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val items by shopping.collectAsState()
            Content(
                snapshot = WidgetUpdater.decode(prefs),
                tab = prefs[WidgetUpdater.TAB] ?: TAB_LIGHTS,
                expanded = prefs[WidgetUpdater.EXPANDED],
                toBuy = items.forDisplay().filter { !it.bought },
            )
        }
    }
}

private fun text(size: Int, color: Color = Ink, bold: Boolean = false) =
    TextStyle(color = ColorProvider(color), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable
private fun Content(snapshot: WidgetSnapshot?, tab: String, expanded: String?, toBuy: List<BuyItem>) {
    val openApp = actionStartActivity<MainActivity>()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ColorProvider(Cream))
            .cornerRadius(24.dp)
            .padding(12.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.house),
                contentDescription = "Öppna Falsterboplan",
                modifier = GlanceModifier.size(36.dp).clickable(openApp),
            )
            TabLabel("Lampor", selected = tab == TAB_LIGHTS, target = TAB_LIGHTS)
            TabLabel(if (toBuy.isEmpty()) "Att köpa" else "Att köpa (${toBuy.size})", selected = tab == TAB_BUY, target = TAB_BUY)
            Spacer(GlanceModifier.defaultWeight())
            if (tab == TAB_LIGHTS) {
                Image(
                    provider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Uppdatera",
                    colorFilter = ColorFilter.tint(ColorProvider(Ink)),
                    modifier = GlanceModifier.size(32.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
                )
            }
        }
        if (tab == TAB_BUY) BuyList(toBuy) else Lights(snapshot, expanded, openApp)
    }
}

@Composable
private fun TabLabel(label: String, selected: Boolean, target: String) {
    Column(
        modifier = GlanceModifier
            .padding(start = 10.dp, top = 4.dp, bottom = 2.dp)
            .clickable(actionRunCallback<TabAction>(actionParametersOf(TabAction.TAB to target))),
    ) {
        Text(label, style = text(16, if (selected) Ink else InkSoft, bold = selected), maxLines = 1)
        Box(GlanceModifier.width(28.dp).height(3.dp).background(ColorProvider(if (selected) Pink else Cream))) {}
    }
}

@Composable
private fun Lights(snapshot: WidgetSnapshot?, expanded: String?, openApp: Action) {
    when {
        snapshot == null -> Text("Laddar…", style = text(14, InkSoft))
        !snapshot.paired ->
            Text("Öppna appen för att ansluta hubben.", style = text(14, InkSoft), modifier = GlanceModifier.clickable(openApp))
        snapshot.rooms.isEmpty() -> Text("Inga lampor hittades.", style = text(14, InkSoft))
        else -> LazyColumn {
            items(snapshot.rooms, itemId = { it.key.hashCode().toLong() }) { room -> RoomItem(room, open = expanded == room.key) }
        }
    }
}

@Composable
private fun RoomItem(room: WidgetRoom, open: Boolean) {
    val params = actionParametersOf(RoomAction.ROOM to room.key)
    Column(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = GlanceModifier.defaultWeight().clickable(actionRunCallback<ExpandAction>(params)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(room.name, maxLines = 1, style = text(16, bold = true))
                    Text(
                        if (!room.anyOn) "Av" else room.level?.let { "På · $it %" } ?: "På",
                        style = text(12, InkSoft),
                    )
                }
                Image(
                    provider = ImageProvider(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = if (open) "Dölj ljusstyrka och färg" else "Ljusstyrka och färg",
                    colorFilter = ColorFilter.tint(ColorProvider(InkSoft)),
                    modifier = GlanceModifier.size(28.dp).padding(2.dp),
                )
            }
            Switch(
                checked = room.anyOn,
                onCheckedChange = actionRunCallback<ToggleRoomAction>(params),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ColorProvider(Color.White),
                    uncheckedThumbColor = ColorProvider(Color.White),
                    checkedTrackColor = ColorProvider(Pink),
                    uncheckedTrackColor = ColorProvider(Clay),
                ),
            )
        }
        if (open && !room.anyOn) {
            Text("Tänd rummet för att ändra ljusstyrka och färg.", style = text(12, InkSoft))
        }
        if (open && room.anyOn) {
            if (room.level != null) BrightnessBar(room, room.level)
            if (room.warmK != null && room.coolK != null || room.hasColor) Swatches(room)
        }
    }
}

/** Widgets can't hold a draggable slider, so brightness is five tappable steps drawn as a bar. */
@Composable
private fun BrightnessBar(room: WidgetRoom, level: Int) {
    Row(GlanceModifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (step in 1..5) {
            val value = step * 20
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(36.dp)
                    .padding(horizontal = 2.dp, vertical = 11.dp)
                    .semantics { contentDescription = "Ljusstyrka $value procent" }
                    .clickable(adjust(room, "level", value)),
            ) {
                Box(
                    GlanceModifier.fillMaxSize().cornerRadius(7.dp)
                        .background(ColorProvider(if (level >= value - 10) Pink else Sand)),
                ) {}
            }
        }
    }
}

@Composable
private fun Swatches(room: WidgetRoom) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val warm = room.warmK
        val cool = room.coolK
        if (warm != null && cool != null) {
            Swatch("Varmvit", Color(0xFFFFC870), adjust(room, "temp", warm))
            Swatch("Neutralvit", Color(0xFFFFF3DD), adjust(room, "temp", (warm + cool) / 2))
            Swatch("Kallvit", Color(0xFFBFD9FF), adjust(room, "temp", cool))
        }
        if (room.hasColor) {
            HUES.forEach { (name, hue) -> Swatch(name, Color.hsv(hue.toFloat(), 0.7f, 1f), adjust(room, "hue", hue)) }
        }
    }
}

@Composable
private fun androidx.glance.layout.RowScope.Swatch(name: String, color: Color, action: Action) {
    Box(
        modifier = GlanceModifier.defaultWeight().height(36.dp)
            .semantics { contentDescription = name }
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        // A clay ring keeps the pale swatches visible on cream.
        Box(GlanceModifier.size(26.dp).cornerRadius(13.dp).background(ColorProvider(Clay)), contentAlignment = Alignment.Center) {
            Box(GlanceModifier.size(22.dp).cornerRadius(11.dp).background(ColorProvider(color))) {}
        }
    }
}

private fun adjust(room: WidgetRoom, kind: String, value: Int): Action = actionRunCallback<AdjustRoomAction>(
    actionParametersOf(RoomAction.ROOM to room.key, AdjustRoomAction.KIND to kind, AdjustRoomAction.VALUE to value),
)

@Composable
private fun BuyList(toBuy: List<BuyItem>) {
    val add = actionStartActivity<MainActivity>(actionParametersOf(ActionParameters.Key<Int>(MainActivity.EXTRA_TAB) to 1))
    if (toBuy.isEmpty()) {
        Text("Inget att köpa. Tryck för att lägga till.", style = text(14, InkSoft), modifier = GlanceModifier.clickable(add))
        return
    }
    LazyColumn {
        items(toBuy, itemId = { it.id }) { item ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                CheckBox(
                    checked = false,
                    onCheckedChange = actionRunCallback<BoughtAction>(actionParametersOf(BoughtAction.ID to item.id)),
                    text = item.title,
                    style = text(15),
                    maxLines = 2,
                    colors = CheckboxDefaults.colors(checkedColor = ColorProvider(Pink), uncheckedColor = ColorProvider(InkSoft)),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Box(
                    GlanceModifier.size(12.dp).cornerRadius(6.dp).background(
                        ColorProvider(
                            when (item.priority) {
                                Priority.HIGH -> Pink
                                Priority.MEDIUM -> Clay
                                Priority.LOW -> Mint
                            },
                        ),
                    ),
                ) {}
            }
        }
        item {
            Text("Lägg till något…", style = text(14, InkSoft, bold = true), modifier = GlanceModifier.padding(top = 6.dp, bottom = 4.dp).clickable(add))
        }
    }
}

// ---- Actions ----------------------------------------------------------

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetUpdater.refresh(context)
    }
}

class TabAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[WidgetUpdater.TAB] = parameters[TAB] ?: TAB_LIGHTS }
        LightsWidget().update(context, glanceId)
    }

    companion object {
        val TAB = ActionParameters.Key<String>("widgetTab")
    }
}

class ExpandAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val room = parameters[RoomAction.ROOM] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            if (prefs[WidgetUpdater.EXPANDED] == room) prefs.remove(WidgetUpdater.EXPANDED) else prefs[WidgetUpdater.EXPANDED] = room
        }
        LightsWidget().update(context, glanceId)
    }
}

/** Shared plumbing for actions on a room: show the change at once, tell the hub, then show the truth. */
object RoomAction {
    val ROOM = ActionParameters.Key<String>("room")

    suspend fun run(
        context: Context,
        glanceId: GlanceId,
        roomKey: String,
        attributes: JsonObject,
        optimistic: (WidgetRoom) -> WidgetRoom,
    ) {
        val client = Hub.client(context) ?: return WidgetUpdater.refresh(context)
        var room: WidgetRoom? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            val snapshot = WidgetUpdater.decode(prefs) ?: return@updateAppWidgetState
            room = snapshot.rooms.firstOrNull { it.key == roomKey }
            prefs[WidgetUpdater.SNAPSHOT] = HubJson.encodeToString(
                snapshot.copy(rooms = snapshot.rooms.map { if (it.key == roomKey) optimistic(it) else it }),
            )
        }
        LightsWidget().update(context, glanceId)
        val target = room ?: return
        runCatching { client.setRoomOrEach(target.id, target.lightIds, attributes) }
        delay(700)
        WidgetUpdater.refresh(context)
    }
}

class ToggleRoomAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val on = parameters[ToggleableStateKey] ?: return
        RoomAction.run(context, glanceId, parameters[RoomAction.ROOM] ?: return, buildJsonObject { put("isOn", on) }) {
            it.copy(anyOn = on)
        }
    }
}

class AdjustRoomAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val value = parameters[VALUE] ?: return
        val attributes = buildJsonObject {
            when (parameters[KIND]) {
                "level" -> put("lightLevel", value)
                "temp" -> put("colorTemperature", value)
                "hue" -> {
                    put("colorHue", value.toDouble())
                    put("colorSaturation", 1.0)
                }
                else -> return
            }
        }
        RoomAction.run(context, glanceId, parameters[RoomAction.ROOM] ?: return, attributes) {
            if (parameters[KIND] == "level") it.copy(level = value) else it
        }
    }

    companion object {
        val KIND = ActionParameters.Key<String>("kind")
        val VALUE = ActionParameters.Key<Int>("value")
    }
}

class BoughtAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ID] ?: return
        Shopping.change(context) { items -> items.map { if (it.id == id) it.copy(bought = true) else it } }
        LightsWidget().update(context, glanceId)
    }

    companion object {
        val ID = ActionParameters.Key<Long>("item")
    }
}

class LightsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LightsWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000) { WidgetUpdater.refresh(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }
    }
}
