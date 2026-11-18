package com.falsterboplan.lights.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val HubJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class Room(
    val id: String,
    val name: String = "",
)

@Serializable
data class Attributes(
    val customName: String = "",
    val model: String = "",
    val isOn: Boolean = false,
    /** 1..100 */
    val lightLevel: Int? = null,
    /** Kelvin */
    val colorTemperature: Int? = null,
    val colorTemperatureMin: Int? = null,
    val colorTemperatureMax: Int? = null,
    /** 0..360 */
    val colorHue: Double? = null,
    /** 0..1 */
    val colorSaturation: Double? = null,
    /** "temperature" or "color" */
    val colorMode: String? = null,
)

@Serializable
data class Capabilities(
    val canReceive: List<String> = emptyList(),
)

@Serializable
data class Device(
    val id: String,
    val type: String = "",
    val deviceType: String = "",
    val isReachable: Boolean = true,
    val attributes: Attributes = Attributes(),
    val capabilities: Capabilities = Capabilities(),
    val room: Room? = null,
) {
    val isLight get() = type == "light" || deviceType == "light"
    val name get() = attributes.customName.ifBlank { attributes.model.ifBlank { "Light" } }
    val canDim get() = "lightLevel" in capabilities.canReceive
    val canSetTemperature get() = "colorTemperature" in capabilities.canReceive
    val canSetColor get() = "colorHue" in capabilities.canReceive

    /** Supported Kelvin range, warmest first. The hub's "min"/"max" naming is inverted, so sort. */
    val temperatureRange: IntRange
        get() {
            val a = attributes.colorTemperatureMin ?: 2200
            val b = attributes.colorTemperatureMax ?: 4000
            return minOf(a, b)..maxOf(a, b)
        }
}

@Serializable
data class AuthCode(val code: String)

@Serializable
data class AuthToken(val access_token: String)
