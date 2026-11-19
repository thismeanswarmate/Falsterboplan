package com.falsterboplan.lights.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.falsterboplan.lights.R
import com.falsterboplan.lights.update.UpdateState
import com.falsterboplan.lights.update.Updater
import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** The app frame: house banner, the Lights and To buy tabs, and whichever tab is showing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    vm: MainViewModel,
    shopping: ShoppingViewModel,
    requestedTab: Int? = null,
    onTabShown: () -> Unit = {},
    /** Who is signed in; null in a build without sign-in. */
    user: String? = null,
    onSignOut: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab.coerceIn(0, 2)
            onTabShown()
        }
    }
    val toBuyCount = shopping.items.collectAsStateWithLifecycle().value.count { !it.bought }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    var addLamp by remember { mutableStateOf(false) }
    if (addLamp) {
        AddLampDialog(state.addLamp, vm, onDismiss = {
            addLamp = false
            vm.stopAddLamp()
        })
    }

    var addRoom by remember { mutableStateOf(false) }
    if (addRoom) NewRoomDialog(vm, onDismiss = { addRoom = false })

    var away by remember { mutableStateOf(false) }
    if (away) AwayDialog(state, vm, onDismiss = { away = false })

    var presence by remember { mutableStateOf(false) }
    if (presence) PresenceDialog(state, onDismiss = { presence = false })

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(88.dp)) {
                    Image(
                        painterResource(R.drawable.house),
                        contentDescription = "Falsterboplan",
                        modifier = Modifier.align(Alignment.Center).height(76.dp),
                    )
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Mer")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            @Composable
                            fun Entry(label: String, action: () -> Unit) = DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    menu = false
                                    action()
                                },
                            )
                            if (state.paired) {
                                Entry("Lägg till lampa") { addLamp = true }
                                Entry("Lägg till rum") { addRoom = true }
                                Entry("Bortaläge") { away = true }
                                Entry("Hemma och borta") { presence = true }
                            }
                            if (user != null) Entry("Logga ut ($user)") { onSignOut() }
                            if (state.paired) Entry("Glöm hubben") { vm.forgetHub() }
                        }
                    }
                }
                // Tabs are as wide as their words, so the longest ("Dammsugare") is never cut off on a narrow phone.
                PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, edgePadding = 8.dp) {
                    listOf("Lampor", if (toBuyCount > 0) "Att köpa ($toBuyCount)" else "Att köpa", "Kalender").forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            selectedContentColor = MaterialTheme.colorScheme.onSurface,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false) },
                        )
                    }
                    // Placeholder for vacuum control; nothing behind it yet.
                    Tab(
                        selected = false,
                        onClick = {},
                        enabled = false,
                        text = {
                            Text(
                                "Dammsugare",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                softWrap = false,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            UpdateBanner()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    tab == 1 -> ShoppingTab(shopping)
                    tab == 2 -> CalendarTab()
                    state.paired -> LightsTab(state, vm)
                    else -> SetupContent(state, vm)
                }
            }
        }
    }
}

/** Appears above every tab when a newer build is waiting, or when updates haven't been switched on yet. */
@Composable
private fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by Updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Updater.check(context) }

    // "New version" notices need permission to notify; ask once.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val current = update
    if (current is UpdateState.None) return
    Card(
        colors = CardDefaults.cardColors(containerColor = Brand.Mint),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when (current) {
                    UpdateState.NotEnabled -> {
                        Text("Slå på uppdateringar", style = MaterialTheme.typography.titleMedium)
                        Text("Logga in en gång med Google-kontot du blev inbjuden med, så hämtar appen nya versioner själv.", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Available -> {
                        Text("Ny version finns", style = MaterialTheme.typography.titleMedium)
                        val detail = listOf(current.release.versionName, current.release.notes).filter { it.isNotBlank() }.joinToString(" · ")
                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    }
                    is UpdateState.Downloading -> Text("Hämtar uppdateringen… ${current.percent} %", style = MaterialTheme.typography.titleMedium)
                    UpdateState.Installing -> Text("Installerar… Android ber dig bekräfta.", style = MaterialTheme.typography.titleMedium)
                    is UpdateState.Failed -> {
                        Text("Uppdateringen gick inte igenom", style = MaterialTheme.typography.titleMedium)
                        Text(current.reason, style = MaterialTheme.typography.bodySmall)
                    }
                    UpdateState.None -> Unit
                }
            }
            when (current) {
                UpdateState.NotEnabled -> TextButton(onClick = { scope.launch { Updater.enable(context) } }) { Text("Slå på", color = Brand.Ink) }
                is UpdateState.Available -> TextButton(onClick = { Updater.install(context) }) { Text("Uppdatera", color = Brand.Ink) }
                is UpdateState.Failed -> TextButton(onClick = { scope.launch { Updater.check(context) } }) { Text("Försök igen", color = Brand.Ink) }
                else -> Unit
            }
        }
    }
}
