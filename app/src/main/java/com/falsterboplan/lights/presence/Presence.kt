package com.falsterboplan.lights.presence

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.falsterboplan.lights.MainActivity
import com.falsterboplan.lights.R
import com.falsterboplan.lights.data.Hub
import com.falsterboplan.lights.data.HubJson
import com.falsterboplan.lights.data.onOff
import com.falsterboplan.lights.data.setRoomOrEach
import com.falsterboplan.lights.widget.WidgetUpdater
import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.concurrent.TimeUnit

@Serializable
data class PresenceSettings(
    /** Turn lights on when the phone comes home in the evening. */
    val arrive: Boolean = false,
    val roomIds: List<String> = emptyList(),
    /** Minute of the day from which it counts as evening (until 02:00). */
    val eveningFrom: Int = 17 * 60,
    /** Notify when the phone leaves home with lights still on. */
    val leaveReminder: Boolean = false,
) {
    val enabled get() = arrive || leaveReminder
}

@Serializable
data class PresenceState(
    val home: Boolean = true,
    /** When [home] last changed. */
    val since: Long = 0,
    /** Lights that were on the last time the hub answered. */
    val lightsOn: Int = 0,
    /** Checks in a row where the hub did not answer. */
    val misses: Int = 0,
)

data class Decision(val state: PresenceState, val turnOn: Boolean = false, val remindAbout: Int = 0)

private const val MIN_AWAY_MS = 10 * 60_000L

/**
 * The whole rule set, free of Android so it can be tested. "Home" simply means the hub answers,
 * which only happens on the home network; no location permission is needed.
 */
fun decide(
    prev: PresenceState,
    settings: PresenceSettings,
    hubReachable: Boolean,
    onWifi: Boolean,
    lightsOn: Int,
    now: Long,
    minuteOfDay: Int,
): Decision {
    if (hubReachable) {
        val arrived = !prev.home && now - prev.since >= MIN_AWAY_MS
        val evening = minuteOfDay >= settings.eveningFrom || minuteOfDay < 2 * 60
        return Decision(
            state = PresenceState(home = true, since = if (prev.home) prev.since else now, lightsOn = lightsOn),
            turnOn = arrived && settings.arrive && settings.roomIds.isNotEmpty() && evening && lightsOn == 0,
        )
    }
    val misses = prev.misses + 1
    // Off Wi-Fi means gone. On Wi-Fi but no answer could be a hiccup, so wait for a second miss.
    val left = prev.home && (!onWifi || misses >= 2)
    return if (left) {
        Decision(
            state = PresenceState(home = false, since = now, lightsOn = prev.lightsOn, misses = misses),
            remindAbout = if (settings.leaveReminder) prev.lightsOn else 0,
        )
    } else {
        Decision(prev.copy(misses = misses))
    }
}

class PresenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("presence", Context.MODE_PRIVATE)

    var settings: PresenceSettings
        get() = prefs.getString("settings", null)?.let { runCatching { HubJson.decodeFromString<PresenceSettings>(it) }.getOrNull() }
            ?: PresenceSettings()
        set(value) = prefs.edit().putString("settings", HubJson.encodeToString(value)).apply()

    var state: PresenceState
        get() = prefs.getString("state", null)?.let { runCatching { HubJson.decodeFromString<PresenceState>(it) }.getOrNull() }
            ?: PresenceState()
        set(value) = prefs.edit().putString("state", HubJson.encodeToString(value)).apply()
}

object Presence {
    private const val PERIODIC = "presence"
    private const val NOW = "presence-now"
    private const val CHANNEL = "presence"

    /** Starts or stops the background checks to match the saved settings. */
    fun apply(context: Context) {
        val app = context.applicationContext
        val work = WorkManager.getInstance(app)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wake = networkIntent(app)
        runCatching { cm.unregisterNetworkCallback(wake) }
        if (!PresenceStore(app).settings.enabled) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        // Every 15 minutes (the shortest Android allows), plus right away whenever Wi-Fi connects.
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PresenceWorker>(15, TimeUnit.MINUTES).build(),
        )
        val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(wifi, wake) }
    }

    fun checkSoon(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PresenceWorker>().setInitialDelay(5, TimeUnit.SECONDS).build(),
        )
    }

    private fun networkIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PresenceReceiver::class.java).setAction(PresenceReceiver.ACTION_NETWORK),
        // The system fills in which network it was, so the intent has to be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
    )

    suspend fun check(context: Context) {
        val app = context.applicationContext
        val store = PresenceStore(app)
        val settings = store.settings
        if (!settings.enabled) return
        val client = Hub.client(app) ?: return

        val cm = app.getSystemService(ConnectivityManager::class.java)
        val onWifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val lights = if (onWifi) runCatching { client.lights() }.getOrNull() else null
        val calendar = Calendar.getInstance()

        val decision = decide(
            prev = store.state,
            settings = settings,
            hubReachable = lights != null,
            onWifi = onWifi,
            lightsOn = lights?.count { it.isReachable && it.attributes.isOn } ?: 0,
            now = System.currentTimeMillis(),
            minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE),
        )
        store.state = decision.state

        if (decision.turnOn && lights != null) {
            settings.roomIds.forEach { roomId ->
                val members = lights.filter { it.room?.id == roomId }.map { it.id }
                if (members.isNotEmpty()) runCatching { client.setRoomOrEach(roomId, members, onOff(true)) }
            }
            WidgetUpdater.refresh(app)
        }
        if (decision.remindAbout > 0) remind(app, decision.remindAbout)
    }

    private fun remind(context: Context, count: Int) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Hemma och borta", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (count == 1) "En lampa var tänd när du gick hemifrån." else "$count lampor var tända när du gick hemifrån."
        manager.notify(
            1,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Lampor tända hemma")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class PresenceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Presence.check(applicationContext)
        return Result.success()
    }
}

class PresenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // The Wi-Fi watch doesn't survive a reboot.
            Intent.ACTION_BOOT_COMPLETED -> Presence.apply(context)
            ACTION_NETWORK -> if (PresenceStore(context).settings.enabled) Presence.checkSoon(context)
        }
    }

    companion object {
        const val ACTION_NETWORK = "com.falsterboplan.lights.WIFI_CONNECTED"
    }
}
