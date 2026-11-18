package com.falsterboplan.lights.ui

import com.falsterboplan.lights.data.Moment
import com.falsterboplan.lights.data.WEEK
import kotlin.math.abs

/** Swedish wording shared by the screens and by the names given to schedules on the hub. */
val DAY_SV = mapOf("Mon" to "Mån", "Tue" to "Tis", "Wed" to "Ons", "Thu" to "Tors", "Fri" to "Fre", "Sat" to "Lör", "Sun" to "Sön")

fun dayText(days: List<String>): String = when {
    days.size == 7 -> "Varje dag"
    days == WEEK.take(5) -> "Vardagar"
    days == WEEK.drop(5) -> "Helger"
    else -> days.joinToString(", ") { DAY_SV[it] ?: it }
}

fun onOffText(on: Boolean) = if (on) "på" else "av"

fun duration(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"

fun Moment.text(): String = when (this) {
    is Moment.Clock -> text
    is Moment.Sun -> {
        val sun = if (sunrise) "soluppgången" else "solnedgången"
        when {
            offsetMinutes == 0 -> "vid $sun"
            offsetMinutes < 0 -> "${duration(abs(offsetMinutes))} före $sun"
            else -> "${duration(offsetMinutes)} efter $sun"
        }
    }
}
