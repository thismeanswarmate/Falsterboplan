package com.falsterboplan.lights.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** The one to-buy list for the whole process, shared by the app screen and the home screen widget. */
object Shopping {
    private var flow: MutableStateFlow<List<BuyItem>>? = null

    @Synchronized
    fun items(context: Context): StateFlow<List<BuyItem>> =
        flow ?: MutableStateFlow(ShoppingStore(context.applicationContext).load()).also { flow = it }

    @Synchronized
    fun change(context: Context, transform: (List<BuyItem>) -> List<BuyItem>) {
        val state = items(context) as MutableStateFlow<List<BuyItem>>
        state.value = transform(state.value)
        ShoppingStore(context.applicationContext).save(state.value)
    }
}
