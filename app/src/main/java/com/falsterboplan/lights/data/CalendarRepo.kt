package com.falsterboplan.lights.data

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A calendar that an account on this phone syncs, for example a Google calendar. */
data class DeviceCalendar(
    val id: Long,
    val name: String,
    val account: String,
    val google: Boolean,
    val color: Int,
    /** The calendar's own address, e.g. "…@group.calendar.google.com" for a shared Google calendar. */
    val owner: String = "",
) {
    val isHome get() = owner.equals(HOME_CALENDAR, ignoreCase = true)
}

/** The household's shared Google calendar, which the tab shows unless someone picks otherwise. */
const val HOME_CALENDAR = "129323e325169e6f7aef42a3bba80b555e60962bdc203ed21466a34cbd19d3c9@group.calendar.google.com"

/** Opening this in a browser offers to add the household calendar to the signed-in Google account. */
const val HOME_CALENDAR_LINK =
    "https://calendar.google.com/calendar/u/0?cid=MTI5MzIzZTMyNTE2OWU2ZjdhZWY0MmEzYmJhODBiNTU1ZTYwOTYyYmRjMjAzZWQyMTQ2NmEzNGNiZDE5ZDNjOUBncm91cC5jYWxlbmRhci5nb29nbGUuY29t"

data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    /** Milliseconds since the epoch. All-day events run from midnight to midnight in UTC. */
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String,
    val color: Int,
)

/**
 * Sorts events onto the days they touch, [days] days from [from]. A timed event counts for every
 * local day it overlaps; an all-day event for its own dates, whatever the time zone. All-day
 * events come first, then by start time.
 */
fun eventsByDay(events: List<CalendarEvent>, from: LocalDate, days: Int, zone: ZoneId): Map<LocalDate, List<CalendarEvent>> =
    (0 until days).map { from.plusDays(it.toLong()) }.associateWith { day ->
        events.filter { e ->
            val first: LocalDate
            val last: LocalDate
            if (e.allDay) {
                first = Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate()
                // The end is exclusive: a one-day event ends at the next midnight.
                last = Instant.ofEpochMilli(maxOf(e.end - 1, e.begin)).atZone(ZoneOffset.UTC).toLocalDate()
            } else {
                first = Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate()
                last = Instant.ofEpochMilli(maxOf(e.end - 1, e.begin)).atZone(zone).toLocalDate()
            }
            !day.isBefore(first) && !day.isAfter(last)
        }.sortedWith(compareBy({ !it.allDay }, { it.begin }, { it.title }))
    }

fun defaultSelection(all: List<DeviceCalendar>): Set<Long> =
    (all.filter { it.isHome }.ifEmpty { all.filter { it.google } }.ifEmpty { all }).map { it.id }.toSet()

/**
 * Reads the calendars Android already keeps in sync on this phone. A Google account added to the
 * phone brings its calendars (including ones shared with it) along, so no separate Google login or
 * API key is needed; the READ_CALENDAR permission is.
 */
class CalendarRepo(private val context: Context) {
    private val prefs = context.getSharedPreferences("calendar", Context.MODE_PRIVATE)

    fun calendars(): List<DeviceCalendar> {
        val columns = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.OWNER_ACCOUNT,
        )
        val found = mutableListOf<DeviceCalendar>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                found += DeviceCalendar(
                    id = c.getLong(0),
                    name = c.getString(1).orEmpty(),
                    account = c.getString(2).orEmpty(),
                    google = c.getString(3) == "com.google",
                    color = c.getInt(4),
                    owner = c.getString(5).orEmpty(),
                )
            }
        }
        return found.sortedWith(compareBy({ !it.isHome }, { !it.google }, { it.account }, { it.name.lowercase() }))
    }

    /**
     * The calendars to show. Until a choice is made: the household calendar alone if the phone has
     * it, otherwise every Google calendar, or all calendars if there are none of those.
     */
    fun selected(all: List<DeviceCalendar>): Set<Long> {
        val saved = prefs.getStringSet("selected", null)?.mapNotNull { it.toLongOrNull() }?.toSet()
        return if (saved != null) saved intersect all.map { it.id }.toSet() else defaultSelection(all)
    }

    fun select(ids: Set<Long>) = prefs.edit().putStringSet("selected", ids.map { it.toString() }.toSet()).apply()

    /** Events (repeats expanded) that touch [from] up to, not including, [until]. */
    fun events(from: LocalDate, until: LocalDate, calendarIds: Set<Long>, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        if (calendarIds.isEmpty()) return emptyList()
        // A day wider on both sides, so all-day events (stored in UTC) are never missed.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            ContentUris.appendId(it, until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DISPLAY_COLOR,
        )
        val found = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val calendarId = c.getLong(1)
                if (calendarId !in calendarIds) continue
                found += CalendarEvent(
                    eventId = c.getLong(0),
                    calendarId = calendarId,
                    title = c.getString(2).orEmpty(),
                    begin = c.getLong(3),
                    end = c.getLong(4),
                    allDay = c.getInt(5) == 1,
                    location = c.getString(6).orEmpty(),
                    color = c.getInt(7),
                )
            }
        }
        return found
    }
}
