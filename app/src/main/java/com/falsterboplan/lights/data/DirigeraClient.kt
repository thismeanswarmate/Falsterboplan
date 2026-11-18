package com.falsterboplan.lights.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class HubException(val status: Int, message: String) : IOException(message)

/** PKCE helpers for the hub's OAuth-style pairing. */
object Pkce {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun newVerifier(length: Int = 128): String {
        val random = SecureRandom()
        return String(CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

/**
 * Talks to a DIRIGERA hub's local REST API (https://<hub>:8443/v1).
 *
 * @param baseUrl must end in "/v1/"
 */
class DirigeraClient(
    private val baseUrl: HttpUrl,
    private val http: OkHttpClient,
    private val token: String? = null,
) {
    // ---- Pairing -------------------------------------------------------

    /** Step 1: ask the hub for a one-time code. The user then presses the hub's action button. */
    suspend fun requestCode(verifier: String): String {
        val url = baseUrl.newBuilder()
            .addPathSegments("oauth/authorize")
            .addQueryParameter("audience", "homesmart.local")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("code_challenge", Pkce.challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .build()
        val body = call(Request.Builder().url(url).build())
        return HubJson.decodeFromString<AuthCode>(body).code
    }

    /** Step 2: returns the access token, or null while the button has not been pressed yet. */
    suspend fun exchangeToken(code: String, verifier: String, clientName: String): String? {
        val form = FormBody.Builder()
            .add("code", code)
            .add("name", clientName)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder().url(url("oauth/token")).post(form).build()
        return try {
            HubJson.decodeFromString<AuthToken>(call(request)).access_token
        } catch (e: HubException) {
            if (e.status == 403) null else throw e
        }
    }

    // ---- Devices -------------------------------------------------------

    /** Everything the hub knows about, including the hub itself (type "gateway"). */
    suspend fun devices(): List<Device> =
        HubJson.decodeFromString<List<Device>>(call(authed(url("devices")).build()))

    suspend fun lights(): List<Device> = devices().filter { it.isLight }

    /** Opens or closes the hub's pairing window for lamps that join without a code. */
    suspend fun setPairingMode(gatewayId: String, open: Boolean) =
        setDevice(gatewayId, buildJsonObject { put("permittingJoin", open) })

    suspend fun setDevice(deviceId: String, attributes: JsonObject) {
        call(authed(url("devices/$deviceId")).patch(patchBody(attributes)).build())
    }

    /** Applies attributes to every light in a room with one request. */
    suspend fun setRoomLights(roomId: String, attributes: JsonObject) {
        val url = baseUrl.newBuilder()
            .addPathSegments("devices/room/$roomId")
            .addQueryParameter("deviceType", "light")
            .build()
        call(authed(url).patch(patchBody(attributes)).build())
    }

    // ---- Schedules (stored on the hub as scenes with a time trigger) -----

    suspend fun scenes(): List<Scene> {
        val body = call(authed(url("scenes")).build())
        // Other apps' scenes can have shapes we don't model; skip those rather than fail.
        return HubJson.parseToJsonElement(body).jsonArray.mapNotNull {
            runCatching { HubJson.decodeFromJsonElement<Scene>(it) }.getOrNull()
        }
    }

    /** Creates a scene the hub runs by itself at [time] ("HH:MM") on [days]. Returns its id. */
    suspend fun createSchedule(name: String, on: Boolean, time: String, days: List<String>, lightIds: List<String>): String {
        // The community libraries disagree on which key names the target device, so try both.
        fun body(deviceKey: String) = buildJsonObject {
            putJsonObject("info") {
                put("name", name)
                put("icon", if (on) "scenes_wake_up" else "scenes_moon")
            }
            put("type", "userScene")
            putJsonArray("triggers") {
                addJsonObject {
                    put("type", "time")
                    put("disabled", false)
                    putJsonObject("trigger") {
                        putJsonArray("days") { days.forEach { add(it) } }
                        put("time", time)
                    }
                }
            }
            putJsonArray("actions") {
                lightIds.forEach { id ->
                    addJsonObject {
                        put("type", "device")
                        put("enabled", true)
                        put(deviceKey, id)
                        putJsonObject("attributes") { put("isOn", on) }
                    }
                }
            }
        }.toString().toRequestBody(JSON)

        val response = try {
            call(authed(url("scenes")).post(body("deviceId")).build())
        } catch (e: HubException) {
            if (e.status != 400) throw e
            call(authed(url("scenes")).post(body("id")).build())
        }
        return HubJson.decodeFromString<CreatedId>(response).id
    }

    suspend fun deleteScene(sceneId: String) {
        call(authed(url("scenes/$sceneId")).delete().build())
    }

    /** Emits once for every change the hub pushes over its websocket. Fails when the socket drops. */
    fun events(): Flow<Unit> = callbackFlow {
        // OkHttp maps https to wss; the socket lives at /v1 without the trailing slash.
        val socket = http.newWebSocket(
            authed(baseUrl.resolve("../v1")!!).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    trySend(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close(IOException("Hub closed the connection"))
                }
            },
        )
        awaitClose { socket.cancel() }
    }

    // ---- Plumbing ------------------------------------------------------

    private fun url(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()

    private fun authed(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun patchBody(attributes: JsonObject) = buildJsonArray {
        add(buildJsonObject { put("attributes", attributes) })
    }.toString().toRequestBody(JSON)

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HubException(response.code, "Hub answered ${response.code}: ${body.take(200)}")
            }
            body
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun baseUrlFor(host: String): HttpUrl =
            HttpUrl.Builder().scheme("https").host(host).port(8443).addPathSegments("v1/").build()
    }
}
