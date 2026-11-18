package com.falsterboplan.lights.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val HubJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class Room(
    val id: String,
    val name: String = "",
    /** The hub's own icon and colour names; kept so that renaming a room doesn't change them. */
    val icon: String = "",
    val color: String = "",
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

@Serializable
data class SceneInfo(val name: String = "", val icon: String = "")

@Serializable
data class TriggerDetails(
    /** "Mon".."Sun"; absent means every day. */
    val days: List<String>? = null,
    /** "HH:MM", hub local time; set on time triggers. */
    val time: String? = null,
    /** "sunrise" or "sunset"; set on sun triggers. */
    val type: String? = null,
    /** Seconds after (or, negative, before) sunrise/sunset. */
    val offset: Int? = null,
)

@Serializable
data class SceneTrigger(
    val type: String = "",
    val disabled: Boolean = false,
    val trigger: TriggerDetails? = null,
)

@Serializable
data class SceneAction(
    val id: String? = null,
    val deviceId: String? = null,
    val type: String = "",
    val attributes: JsonObject? = null,
)

@Serializable
data class Scene(
    val id: String,
    val type: String = "",
    val info: SceneInfo = SceneInfo(),
    val triggers: List<SceneTrigger> = emptyList(),
    val actions: List<SceneAction> = emptyList(),
)

@Serializable
data class CreatedId(val id: String)

val WEEK = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
