package com.falsterboplan.lights.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.falsterboplan.lights.data.Hub
import com.falsterboplan.lights.data.HubJson
import com.falsterboplan.lights.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A one-shot "turn these lights on/off at this moment", fired by the phone. */
@Serializable
data class LightTimer(
    val id: Int,
    val label: String,
    val lightIds: List<String>,
    val on: Boolean,
    val atMillis: Long,
)

class TimerStore(context: Context) {
    private val prefs = context.getSharedPreferences("timers", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<LightTimer> =
        prefs.getString("list", null)
            ?.let { runCatching { HubJson.decodeFromString<List<LightTimer>>(it) }.getOrNull() }
            .orEmpty()
            .sortedBy { it.atMillis }

    @Synchronized
    fun add(timer: LightTimer) = save(all() + timer)

    @Synchronized
    fun remove(id: Int): LightTimer? {
        val current = all()
        save(current.filterNot { it.id == id })
        return current.firstOrNull { it.id == id }
    }

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(timers: List<LightTimer>) {
        prefs.edit().putString("list", HubJson.encodeToString(timers)).apply()
    }
}

object TimerScheduler {
    const val ACTION_FIRE = "com.falsterboplan.lights.TIMER_FIRE"
    const val EXTRA_ID = "id"

    fun schedule(context: Context, timer: LightTimer) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.atMillis, intent(context, timer.id))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.atMillis, intent(context, timer.id))
        }
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(intent(context, id))
    }

    private fun intent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, TimerReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val store = TimerStore(app)
        when (intent.action) {
            // Alarms don't survive a reboot; put them back. Overdue ones fire straight away.
            Intent.ACTION_BOOT_COMPLETED -> store.all().forEach { TimerScheduler.schedule(app, it) }

            TimerScheduler.ACTION_FIRE -> {
                val timer = store.remove(intent.getIntExtra(TimerScheduler.EXTRA_ID, -1)) ?: return
                val client = Hub.client(app) ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Broadcast receivers get roughly ten seconds.
                        withTimeoutOrNull(9_000) {
                            val attributes = buildJsonObject { put("isOn", timer.on) }
                            var remaining = timer.lightIds
                            repeat(3) {
                                remaining = remaining.filter { id ->
                                    runCatching { client.setDevice(id, attributes) }.isFailure
                                }
                                if (remaining.isNotEmpty()) delay(1_000)
                            }
                            WidgetUpdater.refresh(app)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
