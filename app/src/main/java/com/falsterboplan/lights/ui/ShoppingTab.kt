package com.falsterboplan.lights.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.falsterboplan.lights.data.BuyItem
import com.falsterboplan.lights.data.CATEGORIES
import com.falsterboplan.lights.data.Priority
import com.falsterboplan.lights.data.Shopping
import com.falsterboplan.lights.data.forDisplay
import com.falsterboplan.lights.data.remembering
import com.falsterboplan.lights.widget.LightsWidget
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ShoppingViewModel(app: Application) : AndroidViewModel(app) {
    val items: StateFlow<List<BuyItem>> = Shopping.items(app)
    val history: StateFlow<List<String>> = Shopping.history(app)

    private fun change(transform: (List<BuyItem>) -> List<BuyItem>) {
        Shopping.change(getApplication(), transform)
        viewModelScope.launch { LightsWidget().updateAll(getApplication()) }
    }

    fun add(name: String, priority: Priority, quantity: Int = 1, category: String = "", note: String = "") {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        change { it + BuyItem(System.currentTimeMillis(), trimmed, priority, quantity = quantity.coerceIn(1, 99), note = note.trim(), category = category) }
    }

    fun update(item: BuyItem) = change { it.map { i -> if (i.id == item.id) item else i } }
    fun setBought(id: Long, bought: Boolean) = change { it.map { i -> if (i.id == id) i.copy(bought = bought) else i } }
    fun cyclePriority(id: Long) = change { it.map { i -> if (i.id == id) i.copy(priority = i.priority.next()) else i } }

    fun remove(id: Long) {
        items.value.firstOrNull { it.id == id && it.bought }?.let { remember(listOf(it.name)) }
        change { it.filterNot { i -> i.id == id } }
    }

    /** Bought things leave the list but are remembered, so they can be added again with one tap. */
    fun clearBought() {
        remember(items.value.filter { it.bought }.map { it.name })
        change { it.filterNot { i -> i.bought } }
    }

    private fun remember(names: List<String>) = Shopping.changeHistory(getApplication()) { it.remembering(names) }
    fun forget(name: String) = Shopping.changeHistory(getApplication()) { it.filterNot { n -> n.equals(name, ignoreCase = true) } }
}

private fun Priority.dot(): Color = when (this) {
    Priority.HIGH -> Brand.Pink
    Priority.MEDIUM -> Brand.Clay
    Priority.LOW -> Brand.Mint
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShoppingTab(vm: ShoppingViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val sorted = items.forDisplay()
    val toBuy = sorted.filter { !it.bought }
    val bought = sorted.filter { it.bought }
    val again = history.filter { name -> items.none { it.name.equals(name, ignoreCase = true) } }

    var name by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf(Priority.MEDIUM) }
    var quantity by rememberSaveable { mutableIntStateOf(1) }
    var category by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    val add = {
        vm.add(name, priority, quantity, category, note)
        name = ""
        note = ""
        quantity = 1
    }

    var editing by remember { mutableStateOf<BuyItem?>(null) }
    editing?.let { EditItemDialog(it, vm, onDismiss = { editing = null }) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "add") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Något att köpa") },
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                    )
                    PriorityPicker(priority) { priority = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuantityStepper(quantity) { quantity = it }
                        Box(Modifier.weight(1f))
                        TextButton(onClick = { details = !details }) { Text(if (details) "Färre detaljer" else "Fler detaljer", maxLines = 1) }
                    }
                    if (details) {
                        CategoryPicker(category) { category = it }
                        NoteField(note) { note = it }
                    }
                    Button(
                        onClick = add,
                        enabled = name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) { Text("Lägg till i listan", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }

        if (again.isNotEmpty()) {
            item(key = "again") {
                Column {
                    Text(
                        "KÖP IGEN",
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        again.forEach { past ->
                            OutlinedButton(
                                onClick = { vm.add(past, Priority.MEDIUM) },
                                shape = RoundedCornerShape(22.dp),
                                modifier = Modifier.heightIn(min = 44.dp),
                            ) { Text(past, color = Brand.Ink, maxLines = 1) }
                        }
                    }
                }
            }
        }

        if (items.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Listan är tom. Lägg till det första här ovanför.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp),
                )
            }
        }

        if (toBuy.isNotEmpty()) {
            item(key = "h-buy") { SectionTitle("Att köpa") }
            val grouped = toBuy.any { it.category.isNotEmpty() }
            toBuy.groupBy { it.category }.forEach { (cat, members) ->
                if (grouped) {
                    item(key = "c-$cat") {
                        Text(
                            (cat.ifEmpty { "Utan kategori" }).uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                        )
                    }
                }
                items(members, key = { it.id }) { ItemRow(it, vm, onEdit = { editing = it }, modifier = Modifier.animateItem()) }
            }
        }
        if (bought.isNotEmpty()) {
            item(key = "h-bought") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Köpt", Modifier.weight(1f))
                    TextButton(onClick = vm::clearBought) { Text("Rensa köpta") }
                }
            }
            items(bought, key = { it.id }) { ItemRow(it, vm, onEdit = { editing = it }, modifier = Modifier.animateItem()) }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(top = 12.dp, start = 4.dp))
}

@Composable
private fun PriorityPicker(selected: Priority, onSelect: (Priority) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Priority.entries.forEach { p ->
            // Share the width by word length so every label fits on one line.
            Row(
                modifier = Modifier
                    .weight(p.label.length + 3f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (p == selected) Brand.Sand else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(p) }
                    .semantics { contentDescription = "Prioritet ${p.label}" + if (p == selected) ", vald" else "" }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(12.dp).background(p.dot(), CircleShape))
                Text(
                    p.label,
                    style = if (p == selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(quantity - 1) }, enabled = quantity > 1) {
            Icon(Icons.Filled.Remove, contentDescription = "Färre")
        }
        Text(
            "$quantity st",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 44.dp).semantics { contentDescription = "Antal $quantity" },
        )
        IconButton(onClick = { onChange(quantity + 1) }, enabled = quantity < 99) {
            Icon(Icons.Filled.Add, contentDescription = "Fler")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CATEGORIES.forEach { c ->
            FilterChip(selected = selected == c, onClick = { onSelect(if (selected == c) "" else c) }, label = { Text(c) })
        }
    }
}

@Composable
private fun NoteField(note: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = note,
        onValueChange = { onChange(it.take(120)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Anteckning") },
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        maxLines = 3,
    )
}

@Composable
private fun ItemRow(item: BuyItem, vm: ShoppingViewModel, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.bought,
                onCheckedChange = { vm.setBought(item.id, it) },
                colors = CheckboxDefaults.colors(checkedColor = Brand.Pink, uncheckedColor = Brand.InkSoft),
                modifier = Modifier.semantics { contentDescription = "Köpt ${item.name}" },
            )
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clickable(onClickLabel = "Ändra", onClick = onEdit)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (item.bought) TextDecoration.LineThrough else null,
                    color = if (item.bought) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (item.note.isNotEmpty()) {
                    Text(item.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!item.bought) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { vm.cyclePriority(item.id) }
                        .semantics { contentDescription = "Prioritet ${item.priority.label}, tryck för att ändra" }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).background(item.priority.dot(), CircleShape))
                    Text(
                        item.priority.label,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            IconButton(onClick = { vm.remove(item.id) }) {
                Icon(Icons.Filled.Close, contentDescription = "Ta bort ${item.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EditItemDialog(item: BuyItem, vm: ShoppingViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var priority by remember { mutableStateOf(item.priority) }
    var quantity by remember { mutableIntStateOf(item.quantity) }
    var category by remember { mutableStateOf(item.category) }
    var note by remember { mutableStateOf(item.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ändra") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Namn") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                QuantityStepper(quantity) { quantity = it }
                PriorityPicker(priority) { priority = it }
                CategoryPicker(category) { category = it }
                NoteField(note) { note = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    vm.update(item.copy(name = name.trim(), priority = priority, quantity = quantity, category = category, note = note.trim()))
                    onDismiss()
                },
            ) { Text("Spara") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}
