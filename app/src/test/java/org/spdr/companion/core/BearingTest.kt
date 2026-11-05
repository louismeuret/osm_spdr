package org.spdr.companion.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The math convention used inside, turned into the bearings shown on screen. */
class BearingTest {
    @Test fun bearingsRunClockwiseFromNorth() {
        assertEquals(0.0, bearingDeg(90 * DEG), 1e-9)    // +y is north
        assertEquals(90.0, bearingDeg(0.0), 1e-9)        // +x is east
        assertEquals(180.0, bearingDeg(-90 * DEG), 1e-9)
        assertEquals(270.0, bearingDeg(180 * DEG), 1e-9)
        assertEquals(45.0, bearingDeg(45 * DEG), 1e-9)   // north-east, both conventions
        assertEquals(315.0, bearingDeg(135 * DEG), 1e-9)
    }

    @Test fun cardinalsName16Points() {
        assertEquals("N", cardinal(90 * DEG))
        assertEquals("E", cardinal(0.0))
        assertEquals("S", cardinal(-90 * DEG))
        assertEquals("W", cardinal(180 * DEG))
        assertEquals("NE", cardinal(45 * DEG))
        assertEquals("WSW", cardinal(-157.5 * DEG)) // 247.5°, the centre of the WSW sector
        assertEquals("SSW", cardinal(-112.5 * DEG)) // 202.5°
        assertEquals("N", cardinal(100 * DEG))      // 350° bearing wraps back onto north
        assertEquals("NNW", cardinal(102 * DEG))    // 348°, just outside it
    }
}
