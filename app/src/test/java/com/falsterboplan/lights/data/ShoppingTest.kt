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

    @Test
    fun categoriesGroupItemsAndHistoryKeepsNewestWithoutRepeats() {
        val items = listOf(
            BuyItem(1, "Skruvar", Priority.HIGH),
            BuyItem(2, "Mjölk", Priority.LOW, category = "Mat"),
            BuyItem(3, "Glödlampor", Priority.HIGH, quantity = 2, category = "Belysning"),
            BuyItem(4, "Bröd", Priority.HIGH, category = "Mat"),
        )
        assertEquals(listOf("Glödlampor", "Bröd", "Mjölk", "Skruvar"), items.forDisplay().map { it.name })
        assertEquals("2 × Glödlampor", items[2].title)
        assertEquals("Mjölk", items[1].title)

        assertEquals(listOf("Bröd", "Mjölk", "Ägg"), listOf("mjölk", "Ägg").remembering(listOf("Bröd", "Mjölk")))
    }
}
