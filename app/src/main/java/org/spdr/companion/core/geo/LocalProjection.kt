package org.spdr.companion.core.geo

import kotlin.math.PI
import kotlin.math.cos

/**
 * Local ENU frame (meters) around an origin. Equirectangular: error < 1 mm per km at
 * the few-km scale we care about, and trivially invertible.
 */
class LocalProjection(val lat0: Double, val lon0: Double) {
    private val mPerDegLat = 111_132.954
    private val mPerDegLon = 111_319.488 * cos(lat0 * PI / 180.0)

    fun toX(lon: Double) = (lon - lon0) * mPerDegLon
    fun toY(lat: Double) = (lat - lat0) * mPerDegLat
    fun toLon(x: Double) = lon0 + x / mPerDegLon
    fun toLat(y: Double) = lat0 + y / mPerDegLat
}

data class LatLon(val lat: Double, val lon: Double)
