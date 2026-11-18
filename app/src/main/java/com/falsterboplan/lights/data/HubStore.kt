package com.falsterboplan.lights.data

import android.content.Context

data class PairedHub(val host: String, val token: String, val pin: String)

/** Persists the paired hub in app-private storage. */
class HubStore(context: Context) {
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)

    fun load(): PairedHub? {
        val host = prefs.getString("host", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val pin = prefs.getString("pin", null) ?: return null
        return PairedHub(host, token, pin)
    }

    fun save(hub: PairedHub) {
        prefs.edit()
            .putString("host", hub.host)
            .putString("token", hub.token)
            .putString("pin", hub.pin)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

/** Remembers which hub scenes this app created, so it only lists and deletes its own. */
class ScheduleStore(context: Context) {
    private val prefs = context.getSharedPreferences("schedules", Context.MODE_PRIVATE)

    fun ids(): Set<String> = prefs.getStringSet("ids", emptySet()).orEmpty().toSet()
    fun add(id: String) = prefs.edit().putStringSet("ids", ids() + id).apply()
    fun remove(id: String) = prefs.edit().putStringSet("ids", ids() - id).apply()
    fun clear() = prefs.edit().clear().apply()
}
