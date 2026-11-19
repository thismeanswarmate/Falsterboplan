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
    private fun light(id: String, name: String, room: Room?, on: Boolean, level: Int?, caps: List<String>, min: Int? = null, max: Int? = null, reach: Boolean = true) =
        Device(id, "light", "light", reach, Attributes(customName = name, isOn = on, lightLevel = level, colorTemperatureMin = min, colorTemperatureMax = max), Capabilities(caps), room)

    private val living = Room("r1", "Vardagsrum")
    private val bedroom = Room("r2", "Sovrum")
    private val dim = listOf("isOn", "lightLevel")

    @Test
    fun listsEveryLampGroupedByRoomThenName() {
        val snapshot = WidgetSnapshot.of(
            listOf(
                light("plug", "Lös lampa", null, false, null, listOf("isOn")),
                light("b", "Soffa", living, true, 40, dim + "colorTemperature" + "colorHue", min = 4000, max = 2202),
                light("a", "Fönster", living, false, 10, dim),
                light("gone", "Säng", bedroom, true, 100, dim, reach = false),
            ),
        )
        assertEquals(listOf("Säng", "Fönster", "Soffa", "Lös lampa"), snapshot.lights.map { it.name })

        val sofa = snapshot.lights[2]
        assertEquals("Vardagsrum", sofa.room)
        assertTrue(sofa.on)
        assertEquals(40, sofa.level)
        assertEquals(2202, sofa.warmK)
        assertEquals(4000, sofa.coolK)
        assertTrue(sofa.hasColor)

        val window = snapshot.lights[1]
        assertFalse(window.on)
        assertNull(window.warmK)
        assertFalse(window.hasColor)

        // A lamp the hub can't reach is listed, but never as on.
        val bed = snapshot.lights[0]
        assertFalse(bed.reachable)
        assertFalse(bed.on)

        val loose = snapshot.lights[3]
        assertEquals("", loose.room)
        assertNull(loose.level)
    }

    @Test
    fun readsSnapshotsSavedByOlderVersions() {
        // Before the widget listed lamps it stored rooms; that must not crash, just show nothing until refreshed.
        val old = """{"paired":true,"rooms":[{"id":"r1","name":"Living room","lightIds":["a"],"anyOn":true,"onCount":1}]}"""
        val snapshot = HubJson.decodeFromString<WidgetSnapshot>(old)
        assertTrue(snapshot.paired)
        assertTrue(snapshot.lights.isEmpty())
    }
}
