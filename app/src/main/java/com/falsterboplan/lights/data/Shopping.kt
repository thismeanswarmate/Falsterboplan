package com.falsterboplan.lights.data

import android.content.Context
import kotlinx.serialization.Serializable

enum class Priority(val label: String) {
    HIGH("High"), MEDIUM("Medium"), LOW("Low");

    fun next(): Priority = entries[(ordinal + 1) % entries.size]
}

@Serializable
data class BuyItem(
    val id: Long,
    val name: String,
    val priority: Priority = Priority.MEDIUM,
    val bought: Boolean = false,
)

/** Still-to-buy first, most urgent at the top, oldest first within a priority; bought items last. */
fun List<BuyItem>.forDisplay(): List<BuyItem> =
    sortedWith(compareBy<BuyItem> { it.bought }.thenBy { it.priority.ordinal }.thenBy { it.id })

/** Keeps the to-buy list on this phone. */
class ShoppingStore(context: Context) {
    private val prefs = context.getSharedPreferences("shopping", Context.MODE_PRIVATE)

    fun load(): List<BuyItem> =
        prefs.getString("items", null)
            ?.let { runCatching { HubJson.decodeFromString<List<BuyItem>>(it) }.getOrNull() }
            .orEmpty()

    fun save(items: List<BuyItem>) {
        prefs.edit().putString("items", HubJson.encodeToString(items)).apply()
    }
}
