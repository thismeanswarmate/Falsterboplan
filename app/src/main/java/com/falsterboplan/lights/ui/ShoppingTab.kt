package com.falsterboplan.lights.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.falsterboplan.lights.data.BuyItem
import com.falsterboplan.lights.data.Priority
import com.falsterboplan.lights.data.Shopping
import com.falsterboplan.lights.widget.LightsWidget
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.falsterboplan.lights.data.forDisplay
import kotlinx.coroutines.flow.StateFlow

class ShoppingViewModel(app: Application) : AndroidViewModel(app) {
    val items: StateFlow<List<BuyItem>> = Shopping.items(app)

    private fun change(transform: (List<BuyItem>) -> List<BuyItem>) {
        Shopping.change(getApplication(), transform)
        viewModelScope.launch { LightsWidget().updateAll(getApplication()) }
    }

    fun add(name: String, priority: Priority) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        change { it + BuyItem(System.currentTimeMillis(), trimmed, priority) }
    }

    fun setBought(id: Long, bought: Boolean) = change { it.map { i -> if (i.id == id) i.copy(bought = bought) else i } }
    fun cyclePriority(id: Long) = change { it.map { i -> if (i.id == id) i.copy(priority = i.priority.next()) else i } }
    fun remove(id: Long) = change { it.filterNot { i -> i.id == id } }
    fun clearBought() = change { it.filterNot { i -> i.bought } }
}

private fun Priority.dot(): Color = when (this) {
    Priority.HIGH -> Brand.Pink
    Priority.MEDIUM -> Brand.Clay
    Priority.LOW -> Brand.Mint
}

@Composable
fun ShoppingTab(vm: ShoppingViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val sorted = items.forDisplay()
    val toBuy = sorted.filter { !it.bought }
    val bought = sorted.filter { it.bought }

    var name by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf(Priority.MEDIUM) }
    val add = {
        vm.add(name, priority)
        name = ""
    }

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
                        label = { Text("Something to buy") },
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Priority.entries.forEach { p ->
                            // Share the width by word length so the longest label ("Medium") fits on one line.
                            PriorityChip(
                                p,
                                selected = p == priority,
                                onClick = { priority = p },
                                modifier = Modifier.weight(p.label.length + 3f),
                            )
                        }
                    }
                    Button(
                        onClick = add,
                        enabled = name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) { Text("Add to list", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }

        if (items.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing on the list yet. Add the first thing above.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp),
                )
            }
        }

        if (toBuy.isNotEmpty()) {
            item(key = "h-buy") { SectionTitle("To buy") }
            items(toBuy, key = { it.id }) { ItemRow(it, vm, Modifier.animateItem()) }
        }
        if (bought.isNotEmpty()) {
            item(key = "h-bought") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Bought", Modifier.weight(1f))
                    TextButton(onClick = vm::clearBought) { Text("Clear bought") }
                }
            }
            items(bought, key = { it.id }) { ItemRow(it, vm, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(top = 12.dp, start = 4.dp))
}

@Composable
private fun PriorityChip(priority: Priority, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) Brand.Sand else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${priority.label} priority" + if (selected) ", selected" else "" }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(12.dp).background(priority.dot(), CircleShape))
        Text(
            priority.label,
            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun ItemRow(item: BuyItem, vm: ShoppingViewModel, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = item.bought,
                onCheckedChange = { vm.setBought(item.id, it) },
                colors = CheckboxDefaults.colors(checkedColor = Brand.Pink, uncheckedColor = Brand.InkSoft),
                modifier = Modifier.semantics { contentDescription = "Bought ${item.name}" },
            )
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (item.bought) TextDecoration.LineThrough else null,
                color = if (item.bought) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (!item.bought) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { vm.cyclePriority(item.id) }
                        .semantics { contentDescription = "${item.priority.label} priority, tap to change" }
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
                Icon(Icons.Filled.Close, contentDescription = "Remove ${item.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
