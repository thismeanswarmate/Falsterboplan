package com.falsterboplan.lights.widget

import com.falsterboplan.lights.data.Attributes
import com.falsterboplan.lights.data.Capabilities
import com.falsterboplan.lights.data.Device
import com.falsterboplan.lights.data.HubJson
import com.falsterboplan.lights.data.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {
    private fun light(id: String, room: Room?, on: Boolean, level: Int?, caps: List<String>, min: Int? = null, max: Int? = null, reach: Boolean = true) =
        Device(id, "light", "light", reach, Attributes(isOn = on, lightLevel = level, colorTemperatureMin = min, colorTemperatureMax = max), Capabilities(caps), room)

    private val living = Room("r1", "Living room")
    private val dim = listOf("isOn", "lightLevel")

    @Test
    fun summarisesEachRoomForTheWidget() {
        val snapshot = WidgetSnapshot.of(
            listOf(
                light("a", living, true, 80, dim + "colorTemperature" + "colorHue", min = 4000, max = 2202),
                light("b", living, true, 40, dim + "colorTemperature", min = 4000, max = 2700),
                light("c", living, false, 10, dim),
                light("gone", living, true, 100, dim, reach = false),
                light("plug", null, false, null, listOf("isOn")),
            ),
        )
        val (room, other) = snapshot.rooms
        assertEquals("Living room", room.name)
        assertEquals(listOf("a", "b", "c"), room.lightIds)
        assertTrue(room.anyOn)
        assertEquals(60, room.level) // average of the lights that are on
        assertEquals(2700, room.warmK) // a range every tunable light accepts
        assertEquals(4000, room.coolK)
        assertTrue(room.hasColor)

        assertEquals("Other lights", other.name)
        assertEquals(WidgetRoom.OTHER, other.key)
        assertFalse(other.anyOn)
        assertNull(other.level)
        assertNull(other.warmK)
    }

    @Test
    fun readsSnapshotsSavedByOlderVersions() {
        val old = """{"paired":true,"rooms":[{"id":"r1","name":"Living room","lightIds":["a"],"anyOn":true,"onCount":1}]}"""
        val room = HubJson.decodeFromString<WidgetSnapshot>(old).rooms.single()
        assertNull(room.level)
        assertFalse(room.hasColor)
    }
}
