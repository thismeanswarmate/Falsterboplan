package com.falsterboplan.lights.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {
    private val settings = PresenceSettings(arrive = true, roomIds = listOf("r1"), eveningFrom = 17 * 60, leaveReminder = true)
    private val minute = 60_000L
    private val evening = 19 * 60

    @Test
    fun leavingWithLightsOnRemindsOnce() {
        val home = PresenceState(home = true, lightsOn = 3)
        val left = decide(home, settings, hubReachable = false, onWifi = false, lightsOn = 0, now = 100 * minute, minuteOfDay = evening)
        assertFalse(left.state.home)
        assertEquals(3, left.remindAbout)
        // Still away on the next check: nothing more to say.
        val still = decide(left.state, settings, hubReachable = false, onWifi = false, lightsOn = 0, now = 115 * minute, minuteOfDay = evening)
        assertEquals(0, still.remindAbout)
    }

    @Test
    fun oneMissedAnswerOnWifiIsNotLeaving() {
        val home = PresenceState(home = true, lightsOn = 2)
        val hiccup = decide(home, settings, hubReachable = false, onWifi = true, lightsOn = 0, now = minute, minuteOfDay = evening)
        assertTrue(hiccup.state.home)
        assertEquals(0, hiccup.remindAbout)
        val gone = decide(hiccup.state, settings, hubReachable = false, onWifi = true, lightsOn = 0, now = 16 * minute, minuteOfDay = evening)
        assertFalse(gone.state.home)
        assertEquals(2, gone.remindAbout)
        // And an answer in between resets the count.
        val back = decide(hiccup.state, settings, hubReachable = true, onWifi = true, lightsOn = 2, now = 16 * minute, minuteOfDay = evening)
        assertEquals(0, back.state.misses)
    }

    @Test
    fun comingHomeTurnsOnOnlyInTheEveningAfterARealAbsenceIntoADarkHome() {
        val away = PresenceState(home = false, since = 0)
        fun arrive(now: Long, minuteOfDay: Int, lightsOn: Int = 0, s: PresenceSettings = settings) =
            decide(away, s, hubReachable = true, onWifi = true, lightsOn = lightsOn, now = now, minuteOfDay = minuteOfDay)

        assertTrue(arrive(30 * minute, evening).turnOn)
        assertTrue(arrive(30 * minute, 60).turnOn) // 01:00 still counts as evening
        assertFalse(arrive(30 * minute, 12 * 60).turnOn) // midday
        assertFalse(arrive(5 * minute, evening).turnOn) // Wi-Fi blip, not an absence
        assertFalse(arrive(30 * minute, evening, lightsOn = 1).turnOn) // someone is already home
        assertFalse(arrive(30 * minute, evening, s = settings.copy(arrive = false)).turnOn)
        assertTrue(arrive(30 * minute, evening).state.home)
    }
}
