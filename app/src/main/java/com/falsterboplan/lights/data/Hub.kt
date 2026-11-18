package com.falsterboplan.lights.data

import android.content.Context
import kotlinx.serialization.json.JsonObject

/** Entry point for code that runs outside the UI (widget, timers). */
object Hub {
    private var cached: Pair<PairedHub, DirigeraClient>? = null

    @Synchronized
    fun clientFor(hub: PairedHub): DirigeraClient {
        cached?.let { (key, client) -> if (key == hub) return client }
        val trust = PinningTrustManager(expectedPin = { hub.pin }, onUnpinned = {})
        val client = DirigeraClient(DirigeraClient.baseUrlFor(hub.host), hubHttpClient(trust), hub.token)
        cached = hub to client
        return client
    }

    /** Null when no hub is paired. */
    fun client(context: Context): DirigeraClient? = HubStore(context).load()?.let(::clientFor)
}

/** Applies attributes to a room with one request, or light by light if the hub refuses the room-wide form. */
suspend fun DirigeraClient.setRoomOrEach(roomId: String?, lightIds: List<String>, attributes: JsonObject) {
    if (roomId != null) {
        try {
            setRoomLights(roomId, attributes)
            return
        } catch (_: HubException) {
        }
    }
    // One by one; a light that can't do what's asked (say, colour) is skipped.
    lightIds.forEach { runCatching { setDevice(it, attributes) } }
}
