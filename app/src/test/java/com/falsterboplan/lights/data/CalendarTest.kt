package com.falsterboplan.lights.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(stockholm).toInstant().toEpochMilli()
    private fun utcMidnight(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun timed(title: String, begin: Long, end: Long) = CalendarEvent(title.hashCode().toLong(), 1, title, begin, end, false, "", 0)
    private fun allDay(title: String, begin: Long, end: Long) = CalendarEvent(title.hashCode().toLong(), 1, title, begin, end, true, "", 0)

    @Test
    fun eventsLandOnTheRightLocalDays() {
        val monday = LocalDate.of(2026, 11, 16)
        val events = listOf(
            timed("Lunch", at(2026, 11, 16, 12), at(2026, 11, 16, 13)),
            timed("Frukost", at(2026, 11, 16, 8), at(2026, 11, 16, 9)),
            // Late dinner ending exactly at midnight belongs to Monday only.
            timed("Middag", at(2026, 11, 16, 21), at(2026, 11, 17, 0)),
            timed("Nattåg", at(2026, 11, 17, 22), at(2026, 11, 18, 7)),
            // All-day Monday: stored as UTC midnight to UTC midnight, which is 01:00 local.
            allDay("Födelsedag", utcMidnight(2026, 11, 16), utcMidnight(2026, 11, 17)),
            allDay("Semester", utcMidnight(2026, 11, 18), utcMidnight(2026, 11, 20)),
            // Sunday's all-day event must not leak into Monday.
            allDay("Söndag", utcMidnight(2026, 11, 15), utcMidnight(2026, 11, 16)),
        )
        val week = eventsByDay(events, monday, 7, stockholm).mapValues { (_, v) -> v.map { it.title } }

        assertEquals(listOf("Födelsedag", "Frukost", "Lunch", "Middag"), week[monday])
        assertEquals(listOf("Nattåg"), week[monday.plusDays(1)])
        assertEquals(listOf("Semester", "Nattåg"), week[monday.plusDays(2)])
        assertEquals(listOf("Semester"), week[monday.plusDays(3)])
        assertEquals(emptyList<String>(), week[monday.plusDays(4)])
        assertEquals(7, week.size)
    }
}
