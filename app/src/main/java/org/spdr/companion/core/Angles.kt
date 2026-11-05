package org.spdr.companion.core

import kotlin.math.PI

const val TWO_PI = 2 * PI
const val DEG = PI / 180.0

/** Wraps an angle to (-π, π]. */
fun wrap(a: Double): Double {
    var r = a % TWO_PI
    if (r <= -PI) r += TWO_PI
    if (r > PI) r -= TWO_PI
    return r
}

/**
 * Compass bearing in degrees clockwise from north, from a math-convention angle (CCW from east).
 * Headings are held in the math convention everywhere inside, because that is what the map's
 * local ENU frame uses; this is only for showing them to a human.
 */
fun bearingDeg(a: Double): Double = ((90 - a / DEG) % 360 + 360) % 360

private val COMPASS_POINTS = arrayOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
)

/** Sixteen-point cardinal name of a math-convention angle. */
fun cardinal(a: Double): String = COMPASS_POINTS[(((bearingDeg(a) + 11.25) % 360) / 22.5).toInt()]
