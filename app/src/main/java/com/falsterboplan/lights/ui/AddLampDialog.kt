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
        title = { Text("Lägg till lampa") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Lampa utan kod", style = MaterialTheme.typography.titleMedium)
                Text(
                    "För TRÅDFRI och andra lampor som saknar QR-kod. Håll lampan nära hubben och " +
                        "återställ den genom att släcka och tända den sex gånger. Den blinkar till när den ansluter.",
                )
                when (state) {
                    AddLamp.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Hubben lyssnar efter en ny lampa…")
                    }
                    is AddLamp.Found -> Text(
                        "Tillagd: ${state.names.joinToString()}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is AddLamp.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    AddLamp.Idle -> Unit
                }
                if (state == AddLamp.Searching) {
                    OutlinedButton(onClick = vm::stopAddLamp) { Text("Stoppa") }
                } else {
                    Button(onClick = vm::startAddLamp) {
                        Text(if (state == AddLamp.Idle) "Starta parkoppling" else "Försök igen")
                    }
                }

                HorizontalDivider()

                Text("Lampa med QR-kod eller sifferkod", style = MaterialTheme.typography.titleMedium)
                Text(
                    "KAJPLATS och andra nyare lampor läggs till med koden på lampan eller i foldern. " +
                        "Bara IKEAs egen app kan skicka koden till hubben, så det steget görs där. " +
                        "Lampan dyker upp här så fort den är tillagd.",
                )
                OutlinedButton(onClick = { openIkeaApp(context) }) { Text("Öppna IKEA Home smart") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Klar") } },
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
