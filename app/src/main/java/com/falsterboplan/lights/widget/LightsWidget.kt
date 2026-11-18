package com.falsterboplan.lights.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.SwitchDefaults
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.falsterboplan.lights.MainActivity
import com.falsterboplan.lights.R
import com.falsterboplan.lights.data.Device
import com.falsterboplan.lights.data.Hub
import com.falsterboplan.lights.data.HubJson
import com.falsterboplan.lights.data.setRoomOrEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class WidgetRoom(
    /** Null for lights that aren't in a room. */
    val id: String?,
    val name: String,
    val lightIds: List<String>,
    val anyOn: Boolean,
    val onCount: Int,
)

@Serializable
data class WidgetSnapshot(val paired: Boolean, val rooms: List<WidgetRoom> = emptyList()) {
    companion object {
        fun of(lights: List<Device>): WidgetSnapshot = WidgetSnapshot(
            paired = true,
            rooms = lights.filter { it.isReachable }
                .groupBy { it.room?.id }
                .map { (roomId, members) ->
                    WidgetRoom(
                        id = roomId,
                        name = members.first().room?.name?.ifBlank { null } ?: "Other lights",
                        lightIds = members.map { it.id },
                        anyOn = members.any { it.attributes.isOn },
                        onCount = members.count { it.attributes.isOn },
                    )
                }
                .sortedWith(compareBy({ it.id == null }, { it.name.lowercase() })),
        )
    }
}

// Brand colours, as in ui/Theme.kt.
private val Cream = Color(0xFFF5EAD6)
private val Ink = Color(0xFF54332A)
private val InkSoft = Color(0xFF7A5549)
private val Pink = Color(0xFFE94E76)
private val Clay = Color(0xFFC6A49A)

object WidgetUpdater {
    val SNAPSHOT = stringPreferencesKey("snapshot")

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
        provideContent {
            val snapshot = currentState<Preferences>()[WidgetUpdater.SNAPSHOT]
                ?.let { runCatching { HubJson.decodeFromString<WidgetSnapshot>(it) }.getOrNull() }
            GlanceTheme { Content(snapshot) }
        }
    }
}

@Composable
private fun Content(snapshot: WidgetSnapshot?) {
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
                contentDescription = null,
                modifier = GlanceModifier.size(36.dp).clickable(openApp),
            )
            Text(
                "Lights",
                modifier = GlanceModifier.defaultWeight().padding(start = 8.dp).clickable(openApp),
                style = TextStyle(color = ColorProvider(Ink), fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                colorFilter = ColorFilter.tint(ColorProvider(Ink)),
                modifier = GlanceModifier.size(32.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
        val hint = TextStyle(color = ColorProvider(InkSoft), fontSize = 14.sp)
        when {
            snapshot == null -> Text("Loading…", style = hint)
            !snapshot.paired ->
                Text("Open the app to connect your hub.", style = hint, modifier = GlanceModifier.clickable(openApp))
            snapshot.rooms.isEmpty() -> Text("No lights found.", style = hint)
            else -> LazyColumn {
                items(snapshot.rooms, itemId = { (it.id ?: "").hashCode().toLong() }) { room ->
                    Row(
                        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(GlanceModifier.defaultWeight()) {
                            Text(
                                room.name,
                                maxLines = 1,
                                style = TextStyle(color = ColorProvider(Ink), fontSize = 16.sp),
                            )
                            Text(
                                if (room.anyOn) "${room.onCount} of ${room.lightIds.size} on" else "Off",
                                style = TextStyle(color = ColorProvider(InkSoft), fontSize = 12.sp),
                            )
                        }
                        Switch(
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ColorProvider(Color.White),
                                uncheckedThumbColor = ColorProvider(Color.White),
                                checkedTrackColor = ColorProvider(Pink),
                                uncheckedTrackColor = ColorProvider(Clay),
                            ),
                            checked = room.anyOn,
                            onCheckedChange = actionRunCallback<ToggleRoomAction>(
                                actionParametersOf(ToggleRoomAction.ROOM to (room.id ?: "")),
                            ),
                        )
                    }
                }
            }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetUpdater.refresh(context)
    }
}

class ToggleRoomAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val roomId = parameters[ROOM]?.ifEmpty { null }
        val on = parameters[ToggleableStateKey] ?: return
        val client = Hub.client(context) ?: return WidgetUpdater.refresh(context)

        // Flip the switch on screen first, then tell the hub, then show what really happened.
        var lightIds = emptyList<String>()
        updateAppWidgetState(context, glanceId) { prefs ->
            val snapshot = prefs[WidgetUpdater.SNAPSHOT]
                ?.let { runCatching { HubJson.decodeFromString<WidgetSnapshot>(it) }.getOrNull() }
                ?: return@updateAppWidgetState
            lightIds = snapshot.rooms.firstOrNull { it.id == roomId }?.lightIds.orEmpty()
            prefs[WidgetUpdater.SNAPSHOT] = HubJson.encodeToString(
                snapshot.copy(
                    rooms = snapshot.rooms.map {
                        if (it.id == roomId) it.copy(anyOn = on, onCount = if (on) it.lightIds.size else 0) else it
                    },
                ),
            )
        }
        LightsWidget().update(context, glanceId)

        runCatching { client.setRoomOrEach(roomId, lightIds, buildJsonObject { put("isOn", on) }) }
        delay(700)
        WidgetUpdater.refresh(context)
    }

    companion object {
        val ROOM = ActionParameters.Key<String>("room")
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
