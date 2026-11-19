package com.falsterboplan.lights.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.falsterboplan.lights.BuildConfig
import com.falsterboplan.lights.MainActivity
import com.falsterboplan.lights.R
import com.falsterboplan.lights.data.HubJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** What the download page says is the latest build: the contents of version.json. */
@Serializable
data class Release(
    val versionCode: Int,
    val versionName: String = "",
    /** File name of the APK, next to version.json. */
    val apk: String = "falsterboplan.apk",
    val notes: String = "",
)

fun Release.isNewerThan(installed: Int) = versionCode > installed

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val percent: Int) : UpdateState

    /** Handed to Android, which installs it (and may ask for confirmation). */
    data object Installing : UpdateState
    data class Failed(val release: Release, val reason: String) : UpdateState
}

/**
 * Keeps the app up to date without an app store. The release workflow puts version.json and the
 * APK on the Firebase project's hosting site; the app compares that with its own version code.
 * Android only installs the download if it is signed with the same key as the installed app.
 */
object Updater {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = mutable

    private const val CHANNEL = "updates"
    private const val WORK = "update-check"

    /** Asks the download page for the latest release; null if it can't be reached or reads oddly. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(BuildConfig.UPDATE_URL + "version.json").build()).execute().use { response ->
                if (!response.isSuccessful) null else HubJson.decodeFromString<Release>(response.body!!.string())
            }
        }.getOrNull()
    }

    /** Looks for a newer build and shows it in [state]. */
    suspend fun check() {
        if (mutable.value is UpdateState.Downloading || mutable.value is UpdateState.Installing) return
        val release = latest() ?: return
        mutable.value = if (release.isNewerThan(BuildConfig.VERSION_CODE)) UpdateState.Available(release) else UpdateState.None
    }

    /** Downloads [release] straight into Android's installer and asks it to install. */
    suspend fun install(context: Context, release: Release) {
        val app = context.applicationContext
        mutable.value = UpdateState.Downloading(0)
        try {
            withContext(Dispatchers.IO) {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android may then skip its confirmation when the app updates itself.
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    http.newCall(Request.Builder().url(BuildConfig.UPDATE_URL + release.apk).build()).execute().use { response ->
                        if (!response.isSuccessful) error("Nedladdningen misslyckades (${response.code}).")
                        val body = response.body!!
                        val total = body.contentLength()
                        session.openWrite("app.apk", 0, total).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            val input = body.byteStream()
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                if (total > 0) mutable.value = UpdateState.Downloading((done * 100 / total).toInt())
                            }
                            session.fsync(out)
                        }
                    }
                    val result = PendingIntent.getBroadcast(
                        app,
                        sessionId,
                        Intent(app, InstallReceiver::class.java),
                        // Android adds the outcome to the intent, so it must be mutable.
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
                    )
                    session.commit(result.intentSender)
                }
            }
            mutable.value = UpdateState.Installing
        } catch (e: Exception) {
            mutable.value = UpdateState.Failed(release, e.message ?: "okänt fel")
        }
    }

    internal fun failed(reason: String) {
        val release = (mutable.value as? UpdateState.Available)?.release ?: Release(0)
        mutable.value = UpdateState.Failed(release, reason)
    }

    /** Checks twice a day in the background, so a notification can announce a new version. */
    fun schedule(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Notifies about [release] once; later checks stay quiet until there is an even newer one. */
    internal fun announce(context: Context, release: Release) {
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (prefs.getInt("announced", 0) >= release.versionCode) return
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Uppdateringar", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            2,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Ny version av Falsterboplan")
                .setContentText("Öppna appen för att uppdatera till ${release.versionName.ifBlank { "den nya versionen" }}.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        prefs.edit().putInt("announced", release.versionCode).apply()
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val release = Updater.latest()
        if (release != null && release.isNewerThan(BuildConfig.VERSION_CODE)) Updater.announce(applicationContext, release)
        return Result.success()
    }
}

/** Hears back from Android's installer about an update this app asked for. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the person to confirm; show its own dialog.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is restarted in its new version
            else -> Updater.failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installationen avbröts.")
        }
    }
}
