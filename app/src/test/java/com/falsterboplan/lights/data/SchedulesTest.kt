package com.falsterboplan.lights.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulesTest {
    @Test
    fun shiftMovesWeekdaysAcrossMidnight() {
        assertEquals(Shifted("06:40", listOf("Mon", "Fri")), shift(7, 0, -20, listOf("Mon", "Fri")))
        assertEquals(Shifted("23:50", listOf("Mon", "Sun")), shift(0, 10, -20, listOf("Mon", "Tue")))
        assertEquals(Shifted("00:15", listOf("Mon", "Sat")), shift(23, 45, 30, listOf("Fri", "Sun")))
    }

    @Test
    fun wakeUpEndsAtFullBrightnessOnTheChosenTime() {
        val steps = wakeUpSteps(7, 0, 20, listOf("Mon"))
        assertEquals(
            listOf("06:40", "06:45", "06:50", "06:55", "07:00"),
            steps.map { it.trigger.toString().substringAfter("\"time\":\"").take(5) },
        )
        assertEquals("""{"isOn":true,"lightLevel":1}""", steps.first().attributes.toString())
        assertEquals("""{"isOn":true,"lightLevel":100}""", steps.last().attributes.toString())
    }

    @Test
    fun windDownEndsWithLightsOff() {
        val steps = windDownSteps(22, 0, 60, WEEK)
        assertEquals("""{"isOn":true,"lightLevel":60}""", steps.first().attributes.toString())
        assertTrue(steps.first().trigger.toString().contains("\"time\":\"22:00\""))
        assertEquals("""{"isOn":false}""", steps.last().attributes.toString())
        assertTrue(steps.last().trigger.toString().contains("\"time\":\"23:00\""))
    }

    @Test
    fun sunTriggerUsesSecondsAndReadsBack() {
        val json = Moment.Sun(sunrise = false, offsetMinutes = -30).trigger(listOf("Sat", "Sun")).toString()
        assertEquals(
            """{"type":"sunriseSunset","disabled":false,"trigger":{"days":["Sat","Sun"],"type":"sunset","offset":-1800}}""",
            json,
        )
        val scene = HubJson.decodeFromString<Scene>(
            """{"id":"s","type":"userScene","triggers":[{"type":"app"},$json],
               "actions":[{"id":"light-1","type":"device","attributes":{"isOn":false}}]}""",
        )
        assertEquals(Moment.Sun(false, -30), scene.moment)
        assertEquals(listOf("Sat", "Sun"), scene.days)
        assertEquals(listOf("light-1"), scene.lightIds)
        assertFalse(scene.turnsOn)
        assertFalse(scene.isManual)
        assertTrue(HubJson.decodeFromString<Scene>("""{"id":"m","triggers":[{"type":"app"}]}""").isManual)
    }
}
