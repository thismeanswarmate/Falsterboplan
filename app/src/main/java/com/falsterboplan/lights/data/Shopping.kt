package com.falsterboplan.lights.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

enum class Priority(val label: String) {
    HIGH("Hög"), MEDIUM("Medel"), LOW("Låg");

    fun next(): Priority = entries[(ordinal + 1) % entries.size]
}

/** The categories an item can be filed under; an empty category means none. */
val CATEGORIES = listOf("Mat", "Hem", "Belysning", "Trädgård", "Verktyg", "Övrigt")

@Serializable
data class BuyItem(
    val id: Long,
    val name: String,
    val priority: Priority = Priority.MEDIUM,
    val bought: Boolean = false,
    val quantity: Int = 1,
    val note: String = "",
    val category: String = "",
) {
    /** "2 × Glödlampor", or just the name when there is one. */
    val title get() = if (quantity > 1) "$quantity × $name" else name
}

/**
 * Still-to-buy first; within that grouped by category (items without one last), most urgent at
 * the top of each group, oldest first within a priority. Bought items come last.
 */
fun List<BuyItem>.forDisplay(): List<BuyItem> = sortedWith(
    compareBy<BuyItem> { it.bought }
        .thenBy { it.category.isEmpty() }
        .thenBy { it.category.lowercase() }
        .thenBy { it.priority.ordinal }
        .thenBy { it.id },
)

/** Adds [names] to the front of the "buy again" history: newest first, no repeats, at most 30. */
fun List<String>.remembering(names: List<String>): List<String> =
    (names + this).distinctBy { it.lowercase() }.take(30)

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

    fun loadHistory(): List<String> =
        prefs.getString("history", null)
            ?.let { runCatching { HubJson.decodeFromString<List<String>>(it) }.getOrNull() }
            .orEmpty()

    fun saveHistory(names: List<String>) {
        prefs.edit().putString("history", HubJson.encodeToString(names)).apply()
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

    private var past: MutableStateFlow<List<String>>? = null

    /** Names of things bought before, offered as "buy again". */
    @Synchronized
    fun history(context: Context): StateFlow<List<String>> =
        past ?: MutableStateFlow(ShoppingStore(context.applicationContext).loadHistory()).also { past = it }

    @Synchronized
    fun changeHistory(context: Context, transform: (List<String>) -> List<String>) {
        val state = history(context) as MutableStateFlow<List<String>>
        state.value = transform(state.value)
        ShoppingStore(context.applicationContext).saveHistory(state.value)
    }
}
