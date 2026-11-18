package com.falsterboplan.lights.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val IKEA_APP = "com.ikea.inter.homesmart.system2"

@Composable
fun AddLampDialog(state: AddLamp, vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a lamp") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Lamp without a code", style = MaterialTheme.typography.titleMedium)
                Text(
                    "For TRÅDFRI and other lamps that have no QR code. Hold the lamp close to the hub, " +
                        "then reset it by switching it off and on six times. It dims and brightens when it joins.",
                )
                when (state) {
                    AddLamp.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  The hub is listening for a new lamp…")
                    }
                    is AddLamp.Found -> Text(
                        "Added: ${state.names.joinToString()}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is AddLamp.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    AddLamp.Idle -> Unit
                }
                if (state == AddLamp.Searching) {
                    OutlinedButton(onClick = vm::stopAddLamp) { Text("Stop") }
                } else {
                    Button(onClick = vm::startAddLamp) {
                        Text(if (state == AddLamp.Idle) "Start pairing" else "Try again")
                    }
                }

                HorizontalDivider()

                Text("Lamp with a QR or number code", style = MaterialTheme.typography.titleMedium)
                Text(
                    "KAJPLATS and other newer lamps are added with the code on the lamp or its leaflet. " +
                        "Only IKEA's own app can pass that code to the hub, so that step happens there. " +
                        "The lamp shows up here as soon as it's added.",
                )
                OutlinedButton(onClick = { openIkeaApp(context) }) { Text("Open IKEA Home smart") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun openIkeaApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(IKEA_APP)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$IKEA_APP"))
    try {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$IKEA_APP"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
