package com.falsterboplan.lights.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.falsterboplan.lights.data.CalendarEvent
import com.falsterboplan.lights.data.CalendarRepo
import com.falsterboplan.lights.data.DeviceCalendar
import com.falsterboplan.lights.data.HOME_CALENDAR_LINK
import com.falsterboplan.lights.data.eventsByDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

private val SV = Locale("sv", "SE")
private val DAY_LONG = DateTimeFormatter.ofPattern("EEEE d MMMM", SV)
private val DAY_SHORT = DateTimeFormatter.ofPattern("d MMM", SV)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", SV)

private fun String.capitalised() = replaceFirstChar { it.titlecase(SV) }

/** The Calendar tab: the phone's Google calendars, one day or one week at a time. */
@Composable
fun CalendarTab() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Koppla din kalender", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Appen visar de Google-kalendrar som redan finns på mobilen, även sådana som delas med dig. " +
                    "Den behöver din tillåtelse för att läsa kalendern. Ingenting skickas vidare.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { ask.launch(Manifest.permission.READ_CALENDAR) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Ge åtkomst till kalendern", style = MaterialTheme.typography.titleMedium) }
        }
        return
    }

    val repo = remember { CalendarRepo(context) }
    val zone = remember { ZoneId.systemDefault() }
    var week by rememberSaveable { mutableStateOf(false) }
    var epochDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    val from = if (week) date.with(DayOfWeek.MONDAY) else date
    val days = if (week) 7 else 1

    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var byDay by remember { mutableStateOf<Map<LocalDate, List<CalendarEvent>>?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf(false) }

    // Reload when the phone's calendar data changes, for example after a sync.
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                version++
            }
        }
        context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(from, days, version) {
        val (all, picked, events) = withContext(Dispatchers.IO) {
            val all = runCatching { repo.calendars() }.getOrDefault(emptyList())
            val picked = repo.selected(all)
            Triple(all, picked, runCatching { repo.events(from, from.plusDays(days.toLong()), picked, zone) }.getOrDefault(emptyList()))
        }
        calendars = all
        chosen = picked
        byDay = eventsByDay(events, from, days, zone)
    }

    if (choosing) {
        CalendarChooser(calendars, chosen, onDismiss = { choosing = false }) {
            repo.select(it)
            choosing = false
            version++
        }
    }

    CalendarContent(
        week = week,
        from = from,
        today = LocalDate.now(),
        byDay = byDay,
        hasCalendars = calendars.isNotEmpty(),
        missingHome = byDay != null && calendars.none { it.isHome },
        onAddHome = { openLink(context, HOME_CALENDAR_LINK) },
        zone = zone,
        onWeek = { week = it },
        onMove = { epochDay = date.plusDays(it * days.toLong()).toEpochDay() },
        onToday = { epochDay = LocalDate.now().toEpochDay() },
        onChoose = { choosing = true },
        onOpen = { openEvent(context, it) },
        onNew = { newEvent(context, date, zone) },
    )
}

/** The calendar screen itself, given its data; kept separate so it can be drawn without a phone's calendar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarContent(
    week: Boolean,
    from: LocalDate,
    today: LocalDate,
    byDay: Map<LocalDate, List<CalendarEvent>>?,
    hasCalendars: Boolean,
    zone: ZoneId,
    /** The household's shared calendar isn't on this phone (yet). */
    missingHome: Boolean = false,
    onAddHome: () -> Unit = {},
    onWeek: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onToday: () -> Unit,
    onChoose: () -> Unit,
    onOpen: (CalendarEvent) -> Unit,
    onNew: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    listOf("Dag", "Vecka").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = week == (index == 1),
                            onClick = { onWeek(index == 1) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            icon = {},
                        ) { Text(label) }
                    }
                }
                IconButton(onClick = onChoose) { Icon(Icons.Outlined.Tune, contentDescription = "Välj kalendrar") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMove(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = if (week) "Föregående vecka" else "Föregående dag")
                }
                Text(
                    if (week) {
                        val last = from.plusDays(6)
                        // "16–22 nov." within a month, "30 nov.–6 dec." across two.
                        val first = if (from.month == last.month) from.dayOfMonth.toString() else from.format(DAY_SHORT)
                        "Vecka ${from.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · $first–${last.format(DAY_SHORT)}"
                    } else {
                        from.format(DAY_LONG).capitalised()
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToday) { Text("Idag") }
                IconButton(onClick = { onMove(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = if (week) "Nästa vecka" else "Nästa dag")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (missingHome) {
                item(key = "home") {
                    Card(colors = CardDefaults.cardColors(containerColor = Brand.Mint), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Hushållets kalender finns inte på mobilen", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Lägg till den i ditt Google-konto. När mobilen har synkat visas den här av sig själv.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onAddHome) { Text("Lägg till kalendern", color = Brand.Ink) }
                        }
                    }
                }
            }
            when {
                byDay == null -> item { Quiet("Hämtar kalendern…") }
                !hasCalendars -> item {
                    Quiet("Hittar inga kalendrar på mobilen. Lägg till ditt Google-konto i Android-inställningarna och slå på synkning av kalender.")
                }
                else -> byDay.forEach { (day, events) ->
                    if (week) {
                        item(key = "d$day") {
                            Text(
                                day.format(DAY_LONG).capitalised() + if (day == today) " · idag" else "",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (day == today) Brand.Pink else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                    }
                    if (events.isEmpty()) {
                        item(key = "e$day") { Quiet(if (week) "Inget inbokat" else "Inget inbokat den här dagen.") }
                    }
                    items(events, key = { "$day-${it.eventId}-${it.begin}" }) { EventRow(it, day, zone, onClick = { onOpen(it) }) }
                }
            }
        }

        Button(
            onClick = onNew,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Ny händelse", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
}

@Composable
private fun EventRow(event: CalendarEvent, day: LocalDate, zone: ZoneId, onClick: () -> Unit) {
    val time = if (event.allDay) {
        "Heldag"
    } else {
        // An event that started on an earlier day, or ends on a later one, shows only the part on this day.
        val begin = Instant.ofEpochMilli(event.begin).atZone(zone)
        val end = Instant.ofEpochMilli(event.end).atZone(zone)
        val startsToday = begin.toLocalDate() == day
        val endsToday = !end.toLocalDate().isAfter(day) || (end.toLocalDate() == day.plusDays(1) && end.toLocalTime().toSecondOfDay() == 0)
        when {
            startsToday && endsToday -> "${begin.format(CLOCK)}–${end.format(CLOCK)}"
            startsToday -> "Från ${begin.format(CLOCK)}"
            endsToday -> "Till ${end.format(CLOCK)}"
            else -> "Hela dagen"
        }
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The calendar's own colour, as in Google Calendar.
            Box(Modifier.width(6.dp).height(40.dp).background(Color(event.color).copy(alpha = 1f), RoundedCornerShape(3.dp)))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(event.title.ifBlank { "(utan titel)" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (event.location.isBlank()) time else "$time · ${event.location}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CalendarChooser(all: List<DeviceCalendar>, selected: Set<Long>, onDismiss: () -> Unit, onSave: (Set<Long>) -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Välj kalendrar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (all.isEmpty()) Text("Det finns inga kalendrar på mobilen än.")
                all.forEach { calendar ->
                    val checked = calendar.id in chosen
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { chosen = if (it) chosen + calendar.id else chosen - calendar.id },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Box(Modifier.padding(start = 12.dp).size(12.dp).background(Color(calendar.color).copy(alpha = 1f), CircleShape))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(
                                calendar.name + if (calendar.isHome) " (hushållets)" else "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (calendar.account != calendar.name) Text(calendar.account, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text("Spara") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}

private fun openEvent(context: Context, event: CalendarEvent) {
    val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

private fun openLink(context: Context, link: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
    } catch (_: ActivityNotFoundException) {
    }
}

/** Hands over to the phone's calendar app to create an event on [date]. */
private fun newEvent(context: Context, date: LocalDate, zone: ZoneId) {
    val begin = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60_000)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
