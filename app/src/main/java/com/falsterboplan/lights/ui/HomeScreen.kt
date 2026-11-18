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
import androidx.compose.material3.PrimaryTabRow
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

/** The app frame: house banner, the Lights and To buy tabs, and whichever tab is showing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    vm: MainViewModel,
    shopping: ShoppingViewModel,
    requestedTab: Int? = null,
    onTabShown: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab.coerceIn(0, 1)
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

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(88.dp)) {
                    Image(
                        painterResource(R.drawable.house),
                        contentDescription = "Home app",
                        modifier = Modifier.align(Alignment.Center).height(76.dp),
                    )
                    if (tab == 0 && state.paired) {
                        Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                            var menu by remember { mutableStateOf(false) }
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Add a lamp") },
                                    onClick = {
                                        menu = false
                                        addLamp = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Add a room") },
                                    onClick = {
                                        menu = false
                                        addRoom = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Forget hub") },
                                    onClick = {
                                        menu = false
                                        vm.forgetHub()
                                    },
                                )
                            }
                        }
                    }
                }
                PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    listOf("Lights", if (toBuyCount > 0) "To buy ($toBuyCount)" else "To buy").forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            selectedContentColor = MaterialTheme.colorScheme.onSurface,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(title, style = MaterialTheme.typography.titleMedium) },
                        )
                    }
                    // Placeholder for vacuum control; nothing behind it yet.
                    Tab(
                        selected = false,
                        onClick = {},
                        enabled = false,
                        text = {
                            Text(
                                "Vacuum",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                tab == 1 -> ShoppingTab(shopping)
                state.paired -> LightsTab(state, vm)
                else -> SetupContent(state, vm)
            }
        }
    }
}
