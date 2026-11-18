package com.falsterboplan.lights.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingTest {
    @Test
    fun unboughtComeFirstByPriorityThenAge() {
        val items = listOf(
            BuyItem(1, "Candles", Priority.LOW),
            BuyItem(2, "Milk", Priority.HIGH, bought = true),
            BuyItem(3, "Bulbs", Priority.HIGH),
            BuyItem(4, "Rug", Priority.MEDIUM),
            BuyItem(5, "Batteries", Priority.HIGH),
        )
        assertEquals(listOf("Bulbs", "Batteries", "Rug", "Candles", "Milk"), items.forDisplay().map { it.name })
    }

    @Test
    fun priorityCyclesAndSurvivesJson() {
        assertEquals(Priority.HIGH, Priority.LOW.next())
        val items = listOf(BuyItem(7, "Lamp \"shade\"", Priority.LOW, bought = true))
        assertEquals(items, HubJson.decodeFromString<List<BuyItem>>(HubJson.encodeToString(items)))
    }
}
