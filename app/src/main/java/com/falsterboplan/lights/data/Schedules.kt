package com.falsterboplan.lights.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** When a schedule fires: at a clock time, or relative to sunrise or sunset. */
sealed interface Moment {
    data class Clock(val hour: Int, val minute: Int) : Moment {
        val text get() = "%02d:%02d".format(hour, minute)
    }

    data class Sun(val sunrise: Boolean, val offsetMinutes: Int) : Moment
}

fun onOff(on: Boolean): JsonObject = buildJsonObject { put("isOn", on) }
fun scheduleIcon(on: Boolean) = if (on) "scenes_wake_up" else "scenes_moon"

fun timeTrigger(time: String, days: List<String>): JsonObject = buildJsonObject {
    put("type", "time")
    put("disabled", false)
    putJsonObject("trigger") {
        putJsonArray("days") { days.forEach { add(it) } }
        put("time", time)
    }
}

fun sunTrigger(sunrise: Boolean, offsetMinutes: Int, days: List<String>): JsonObject = buildJsonObject {
    put("type", "sunriseSunset")
    put("disabled", false)
    putJsonObject("trigger") {
        putJsonArray("days") { days.forEach { add(it) } }
        put("type", if (sunrise) "sunrise" else "sunset")
        put("offset", offsetMinutes * 60)
    }
}

fun Moment.trigger(days: List<String>): JsonObject = when (this) {
    is Moment.Clock -> timeTrigger(text, days)
    is Moment.Sun -> sunTrigger(sunrise, offsetMinutes, days)
}

// ---- Reading a scene back --------------------------------------------

private val Scene.scheduleTrigger get() = triggers.firstOrNull { it.type == "time" || it.type == "sunriseSunset" }

/** True for scenes that only run when someone asks, as opposed to schedules. */
val Scene.isManual get() = scheduleTrigger == null

val Scene.moment: Moment?
    get() {
        val t = scheduleTrigger ?: return null
        val details = t.trigger ?: return null
        return if (t.type == "time") {
            val parts = details.time?.split(":")?.mapNotNull { it.toIntOrNull() } ?: return null
            if (parts.size < 2) null else Moment.Clock(parts[0], parts[1])
        } else {
            Moment.Sun(details.type == "sunrise", (details.offset ?: 0) / 60)
        }
    }

val Scene.days: List<String> get() = scheduleTrigger?.trigger?.days?.let { d -> WEEK.filter { it in d } } ?: WEEK
val Scene.lightIds: List<String> get() = actions.mapNotNull { it.deviceId ?: it.id }
val Scene.turnsOn: Boolean get() = actions.firstOrNull()?.attributes?.get("isOn")?.jsonPrimitive?.booleanOrNull ?: true

// ---- Wake-up and wind-down -------------------------------------------

data class Shifted(val time: String, val days: List<String>)

/** [hour]:[minute] moved by [deltaMinutes]; if that crosses midnight the weekdays move with it. */
fun shift(hour: Int, minute: Int, deltaMinutes: Int, days: List<String>): Shifted {
    val total = hour * 60 + minute + deltaMinutes
    val dayShift = Math.floorDiv(total, 24 * 60)
    val inDay = Math.floorMod(total, 24 * 60)
    val moved = days.map { WEEK[Math.floorMod(WEEK.indexOf(it) + dayShift, 7)] }
    return Shifted("%02d:%02d".format(inDay / 60, inDay % 60), WEEK.filter { it in moved })
}

data class Step(val trigger: JsonObject, val attributes: JsonObject)

private fun level(percent: Int) = buildJsonObject {
    put("isOn", true)
    put("lightLevel", percent)
}

/**
 * The hub has no fade, so a wake-up is five small schedules: on at 1 % [durationMinutes] before
 * the given time, then brighter in equal steps until full brightness at the time itself.
 */
fun wakeUpSteps(hour: Int, minute: Int, durationMinutes: Int, days: List<String>): List<Step> =
    listOf(1, 25, 50, 75, 100).mapIndexed { i, percent ->
        val at = shift(hour, minute, -durationMinutes + durationMinutes * i / 4, days)
        Step(timeTrigger(at.time, at.days), level(percent))
    }

/** The reverse: dimmer in steps from the given time, and off [durationMinutes] later. */
fun windDownSteps(hour: Int, minute: Int, durationMinutes: Int, days: List<String>): List<Step> =
    listOf(60, 40, 20, 5, 0).mapIndexed { i, percent ->
        val at = shift(hour, minute, durationMinutes * i / 4, days)
        Step(timeTrigger(at.time, at.days), if (percent == 0) onOff(false) else level(percent))
    }

/** What a light is doing right now, as scene attributes, so a scene can bring it back. */
fun Device.sceneAttributes(): JsonObject = buildJsonObject {
    put("isOn", attributes.isOn)
    if (!attributes.isOn) return@buildJsonObject
    if (canDim) attributes.lightLevel?.let { put("lightLevel", it) }
    val hue = attributes.colorHue
    val temperature = attributes.colorTemperature
    if (canSetColor && attributes.colorMode == "color" && hue != null) {
        put("colorHue", hue)
        put("colorSaturation", attributes.colorSaturation ?: 1.0)
    } else if (canSetTemperature && temperature != null) {
        put("colorTemperature", temperature)
    }
}
