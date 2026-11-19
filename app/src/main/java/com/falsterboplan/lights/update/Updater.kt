package com.falsterboplan.lights.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.falsterboplan.lights.MainActivity
import com.falsterboplan.lights.R
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.appdistribution.AppDistributionRelease
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.FirebaseAppDistributionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** A newer build waiting in Firebase App Distribution. */
data class Release(val versionCode: Long, val versionName: String, val notes: String)

sealed interface UpdateState {
    data object None : UpdateState

    /** This phone hasn't been linked to App Distribution yet, which is a one-time Google sign-in. */
    data object NotEnabled : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val percent: Int) : UpdateState

    /** Downloaded; Android is installing it and may ask for confirmation. */
    data object Installing : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Keeps the app up to date without an app store, through Firebase App Distribution: the release
 * workflow uploads each build there, and the people invited as testers can fetch it. This object
 * asks App Distribution for a newer build, and has it download and install one.
 */
object Updater {
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = mutable

    private const val CHANNEL = "updates"
    private const val WORK = "update-check"

    private fun service(context: Context): FirebaseAppDistribution? =
        runCatching { if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAppDistribution.getInstance() }.getOrNull()

    /** Waits for a Google Play services task; null with the failure passed to [onError] if it fails. */
    private suspend fun <T> Task<T>.result(onError: (Exception) -> Unit = {}): T? = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!task.isSuccessful) task.exception?.let(onError)
            continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }

    private fun AppDistributionRelease.toRelease() = Release(versionCode, displayVersion, releaseNotes.orEmpty())

    /**
     * Looks for a newer build and shows the outcome in [state]. A failed check is normally not
     * worth a banner (the phone may just be offline); with [reportFailure] the reason is shown.
     */
    suspend fun check(context: Context, reportFailure: Boolean = false) {
        if (mutable.value is UpdateState.Downloading || mutable.value is UpdateState.Installing) return
        val distribution = service(context) ?: return
        if (!distribution.isTesterSignedIn) {
            mutable.value = UpdateState.NotEnabled
            return
        }
        var problem: Exception? = null
        val newer = distribution.checkForNewRelease().result { problem = it }
        mutable.value = when {
            newer != null -> UpdateState.Available(newer.toRelease())
            problem != null && reportFailure -> UpdateState.Failed(describe(problem))
            // Keep a reported failure on screen rather than wiping it on the next quiet check.
            problem != null && mutable.value is UpdateState.Failed -> mutable.value
            else -> UpdateState.None
        }
    }

    /** The one-time step: App Distribution opens a Google sign-in to recognise this person as invited. */
    suspend fun enable(context: Context) {
        val distribution = service(context) ?: return
        var problem: Exception? = null
        distribution.signInTester().result { problem = it }
        if (distribution.isTesterSignedIn) {
            check(context, reportFailure = true)
        } else {
            mutable.value = UpdateState.Failed(problem?.let(::describe) ?: "Inloggningen blev inte klar. Sidan i webbläsaren ska själv skicka dig tillbaka till appen.")
        }
    }

    /** App Distribution's own reason, with its error code, so a problem can be told apart. */
    private fun describe(e: Exception): String {
        val code = (e as? FirebaseAppDistributionException)?.errorCode?.name
        return listOfNotNull(e.message, code?.let { "($it)" }).joinToString(" ").ifBlank { "okänt fel" }
    }

    /** Downloads the newest build and hands it to Android to install. */
    fun install(context: Context) {
        val distribution = service(context) ?: return
        mutable.value = UpdateState.Downloading(0)
        distribution.updateApp()
            .addOnProgressListener { progress ->
                val total = progress.apkFileTotalBytes
                mutable.value = if (total > 0 && progress.apkBytesDownloaded < total) {
                    UpdateState.Downloading((progress.apkBytesDownloaded * 100 / total).toInt())
                } else {
                    UpdateState.Installing
                }
            }
            .addOnFailureListener { mutable.value = UpdateState.Failed(it.message ?: "okänt fel") }
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

    internal suspend fun checkInBackground(context: Context) {
        val distribution = service(context) ?: return
        if (!distribution.isTesterSignedIn) return
        val newer = distribution.checkForNewRelease().result() ?: return
        announce(context, newer.toRelease())
    }

    /** Notifies about [release] once; later checks stay quiet until there is an even newer one. */
    private fun announce(context: Context, release: Release) {
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (prefs.getLong("announcedCode", 0) >= release.versionCode) return
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
        prefs.edit().putLong("announcedCode", release.versionCode).apply()
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Updater.checkInBackground(applicationContext) }
        return Result.success()
    }
}
