package com.falsterboplan.lights.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.falsterboplan.lights.R

@Composable
fun SetupContent(state: UiState, vm: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(
            painterResource(R.drawable.icon_tile),
            contentDescription = null,
            modifier = Modifier.size(104.dp).clip(RoundedCornerShape(24.dp)).align(Alignment.CenterHorizontally),
        )
        Text(
            "Välkommen hem",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val pairing = state.pairing) {
            is Pairing.WaitingForButton -> WaitingCard(pairing.host, onCancel = vm::cancelPairing)
            else -> {
                Text(
                    "Se till att mobilen är på samma wifi som din DIRIGERA-hubb.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pairing is Pairing.Failed) {
                    Text(pairing.message, color = MaterialTheme.colorScheme.error)
                }
                HubPicker(state, onPair = vm::pair)
            }
        }
    }
}

@Composable
private fun HubPicker(state: UiState, onPair: (String) -> Unit) {
    Text("Hubbar i det här nätverket", style = MaterialTheme.typography.titleMedium)
    if (state.discovered.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("  Söker…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.discovered.forEach { hub ->
        Card(Modifier.fillMaxWidth().clickable { onPair(hub.host) }) {
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Router, contentDescription = null) },
                headlineContent = { Text(hub.name) },
                supportingContent = { Text(hub.host) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Eller ange hubbens IP-adress", style = MaterialTheme.typography.titleMedium)
    var host by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("192.168.1.50") },
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onPair(host) }),
    )
    Button(
        onClick = { onPair(host) },
        enabled = host.isNotBlank(),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text("Anslut", style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun WaitingCard(host: String, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("Tryck på knappen på hubben", style = MaterialTheme.typography.titleLarge)
            Text(
                "Det är den runda knappen på undersidan av hubben på $host. " +
                    "Du har ungefär en minut på dig.",
            )
            OutlinedButton(onClick = onCancel) { Text("Avbryt") }
        }
    }
}
