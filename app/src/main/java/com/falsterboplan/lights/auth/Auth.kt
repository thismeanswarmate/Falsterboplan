package com.falsterboplan.lights.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google sign-in through Firebase Authentication. It needs app/google-services.json from the
 * Firebase console at build time; without it [configured] is false and nothing else is called.
 */
object Auth {
    fun configured(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null }.getOrDefault(false) &&
            webClientId(context) != null

    /** Name (or e-mail) of whoever is signed in, or null when nobody is. */
    fun userName(context: Context): String? {
        if (!configured(context)) return null
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return user.displayName?.takeIf { it.isNotBlank() } ?: user.email ?: "Google-konto"
    }

    /**
     * Whether the app may be used. Firebase remembers the signed-in account on the phone, so this
     * works without internet after the first sign-in. A build without Firebase has no way to sign
     * in, so it is not locked.
     */
    fun allowed(context: Context): Boolean = !configured(context) || FirebaseAuth.getInstance().currentUser != null

    /** The "web client" id that the google-services plugin generates from google-services.json. */
    private fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id)
    }

    /**
     * Shows Google's account picker and signs in to Firebase with the chosen account.
     * [activity] must be an Activity. Returns null on success, or a message to show.
     */
    suspend fun signIn(activity: Context): String? {
        val clientId = webClientId(activity) ?: return "Inloggning är inte inställd i den här versionen av appen."
        return try {
            val option = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false).setServerClientId(clientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return "Google svarade med en oväntad typ av inloggning."
            }
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            suspendCancellableCoroutine { continuation ->
                FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                    .addOnCompleteListener { task ->
                        continuation.resume(if (task.isSuccessful) null else "Inloggningen misslyckades: ${task.exception?.message}")
                    }
            }
        } catch (_: GetCredentialCancellationException) {
            null // the person closed the picker
        } catch (e: Exception) {
            "Inloggningen misslyckades: ${e.message}"
        }
    }

    fun signOut(context: Context) {
        if (configured(context)) FirebaseAuth.getInstance().signOut()
    }
}
