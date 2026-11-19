package com.falsterboplan.lights.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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

    /** Whether this phone's own items and history have been added to the shared list yet. */
    var uploaded: Boolean
        get() = prefs.getBoolean("uploaded", false)
        set(value) = prefs.edit().putBoolean("uploaded", value).apply()

    var historyUploaded: Boolean
        get() = prefs.getBoolean("historyUploaded", false)
        set(value) = prefs.edit().putBoolean("historyUploaded", value).apply()
}

/** Where the list currently lives. */
sealed interface SyncState {
    /** Not connected to the shared list (yet); changes stay on this phone. */
    data object Local : SyncState

    /** Everyone who is signed in sees and edits the same list. */
    data object Shared : SyncState

    /** The shared list could not be reached; [reason] is the service's own message. */
    data class Failed(val reason: String) : SyncState
}

/**
 * The household's one to-buy list. It is kept in the Firebase project's Firestore database, in
 * the collection "shopping" (one document per item) plus "shoppingMeta/history", so every
 * signed-in phone sees the same list and changes arrive live. A copy is always kept on the phone
 * too: the list works offline, and keeps working on this phone alone if the database is not set up.
 */
object Shopping {
    private const val ITEMS = "shopping"
    private const val META = "shoppingMeta"
    private const val HISTORY = "history"

    private var flow: MutableStateFlow<List<BuyItem>>? = null
    private var past: MutableStateFlow<List<String>>? = null
    private val state = MutableStateFlow<SyncState>(SyncState.Local)
    val sync: StateFlow<SyncState> = state

    private var registrations: List<ListenerRegistration> = emptyList()
    private var started = false

    /** Called with the app context whenever the list changes from elsewhere, to redraw the widget. */
    var onRemoteChange: ((Context) -> Unit)? = null

    @Synchronized
    fun items(context: Context): StateFlow<List<BuyItem>> {
        val current = flow ?: MutableStateFlow(ShoppingStore(context.applicationContext).load()).also { flow = it }
        start(context)
        return current
    }

    /** Names of things bought before, offered as "buy again". */
    @Synchronized
    fun history(context: Context): StateFlow<List<String>> =
        past ?: MutableStateFlow(ShoppingStore(context.applicationContext).loadHistory()).also { past = it }

    private fun database(context: Context): FirebaseFirestore? = runCatching {
        if (FirebaseApp.getApps(context).isEmpty() || FirebaseAuth.getInstance().currentUser == null) null
        else FirebaseFirestore.getInstance()
    }.getOrNull()

    /** Drops and re-opens the connection; call after signing in or out. */
    @Synchronized
    fun restart(context: Context) {
        registrations.forEach { it.remove() }
        registrations = emptyList()
        started = false
        state.value = SyncState.Local
        start(context)
    }

    @Synchronized
    private fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        val db = database(app) ?: return
        started = true
        val store = ShoppingStore(app)
        val items = items(app) as MutableStateFlow<List<BuyItem>>
        val names = history(app) as MutableStateFlow<List<String>>

        val itemsListener = db.collection(ITEMS).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                state.value = SyncState.Failed(error?.message ?: "okänt fel")
                return@addSnapshotListener
            }
            var remote = snapshot.documents.mapNotNull(::itemOf)
            if (!store.uploaded) {
                // First contact with the shared list: wait for the server's answer (not the empty
                // offline cache), then add what was on this phone so nothing is lost.
                if (snapshot.metadata.isFromCache) return@addSnapshotListener
                val mine = items.value.filter { local -> remote.none { it.id == local.id } }
                if (mine.isNotEmpty()) {
                    val batch = db.batch()
                    mine.forEach { batch.set(db.collection(ITEMS).document(it.id.toString()), fieldsOf(it)) }
                    batch.commit()
                    remote = remote + mine
                }
                store.uploaded = true
            }
            items.value = remote
            store.save(remote)
            state.value = SyncState.Shared
            onRemoteChange?.invoke(app)
        }
        val historyListener = db.collection(META).document(HISTORY).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || snapshot.metadata.isFromCache && !snapshot.exists()) return@addSnapshotListener
            val remote = (snapshot.get("names") as? List<*>).orEmpty().filterIsInstance<String>()
            // Fold this phone's own history in once, the same way as the items.
            val merged = if (store.historyUploaded) remote else remote.remembering(names.value).also {
                store.historyUploaded = true
                if (it != remote) db.collection(META).document(HISTORY).set(mapOf("names" to it))
            }
            names.value = merged
            store.saveHistory(merged)
        }
        registrations = listOf(itemsListener, historyListener)
    }

    /** Applies [transform] to the list here at once and sends the difference to the shared list. */
    @Synchronized
    fun change(context: Context, transform: (List<BuyItem>) -> List<BuyItem>) {
        val app = context.applicationContext
        val items = items(app) as MutableStateFlow<List<BuyItem>>
        val before = items.value
        val after = transform(before)
        items.value = after
        ShoppingStore(app).save(after)

        val db = database(app) ?: return
        val old = before.associateBy { it.id }
        val batch = db.batch()
        after.filter { old[it.id] != it }.forEach { batch.set(db.collection(ITEMS).document(it.id.toString()), fieldsOf(it)) }
        (old.keys - after.map { it.id }.toSet()).forEach { batch.delete(db.collection(ITEMS).document(it.toString())) }
        batch.commit()
    }

    @Synchronized
    fun changeHistory(context: Context, transform: (List<String>) -> List<String>) {
        val app = context.applicationContext
        val names = history(app) as MutableStateFlow<List<String>>
        names.value = transform(names.value)
        ShoppingStore(app).saveHistory(names.value)
        database(app)?.collection(META)?.document(HISTORY)?.set(mapOf("names" to names.value))
    }
}

/** An item as stored in the shared database. */
fun fieldsOf(item: BuyItem): Map<String, Any> = mapOf(
    "name" to item.name,
    "priority" to item.priority.name,
    "bought" to item.bought,
    "quantity" to item.quantity,
    "note" to item.note,
    "category" to item.category,
)

/** Reads an item back; tolerant of missing or odd fields, since other versions of the app write too. */
fun itemOf(id: String, fields: Map<String, Any?>): BuyItem? {
    val name = (fields["name"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    return BuyItem(
        id = id.toLongOrNull() ?: return null,
        name = name,
        priority = Priority.entries.firstOrNull { it.name == fields["priority"] } ?: Priority.MEDIUM,
        bought = fields["bought"] as? Boolean ?: false,
        quantity = ((fields["quantity"] as? Number)?.toInt() ?: 1).coerceIn(1, 99),
        note = fields["note"] as? String ?: "",
        category = fields["category"] as? String ?: "",
    )
}

private fun itemOf(document: DocumentSnapshot): BuyItem? = itemOf(document.id, document.data.orEmpty())
