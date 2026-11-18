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
import androidx.compose.material3.Scaffold
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

@Composable
fun SetupScreen(state: UiState, vm: MainViewModel) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connect your hub", style = MaterialTheme.typography.headlineMedium)

            when (val pairing = state.pairing) {
                is Pairing.WaitingForButton -> WaitingCard(pairing.host, onCancel = vm::cancelPairing)
                else -> {
                    Text(
                        "Make sure your phone is on the same Wi-Fi as your DIRIGERA hub.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (pairing is Pairing.Failed) {
                        Text(pairing.message, color = MaterialTheme.colorScheme.error)
                    }
                    HubPicker(state, onPair = vm::pair)
                }
            }
        }
    }
}

@Composable
private fun HubPicker(state: UiState, onPair: (String) -> Unit) {
    Text("Hubs on this network", style = MaterialTheme.typography.titleMedium)
    if (state.discovered.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("  Searching…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Text("Or enter the hub's IP address", style = MaterialTheme.typography.titleMedium)
    var host by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("192.168.1.50") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onPair(host) }),
    )
    Button(onClick = { onPair(host) }, enabled = host.isNotBlank()) { Text("Connect") }
}

@Composable
private fun WaitingCard(host: String, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("Press the button on the hub", style = MaterialTheme.typography.titleLarge)
            Text(
                "It's the round action button on the underside of the hub at $host. " +
                    "You have about a minute.",
            )
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
