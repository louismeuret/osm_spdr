package org.spdr.companion

import android.content.Context
import org.spdr.companion.core.pdr.Mounting
import org.spdr.companion.core.pdr.StepDetector

/** Persistent settings and the calibration carried from one session to the next (spec §6.8). */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("spdr", Context.MODE_PRIVATE)

    /**
     * Calibration is per mounting: a phone strapped to the chest and a phone swinging in a hand
     * see very different acceleration amplitudes for the same stride, so they need their own
     * Weinberg constant and their own learned scale. Values saved before mountings were separated
     * are used as the starting point for every mounting.
     */
    private fun key(base: String) = "${base}_${mounting.name}"

    /**
     * Per-user step length scale (walk-a-known-distance calibration). Keyed separately from the
     * old `weinbergK`, which scaled an amplitude-only model and means nothing to the current one:
     * anyone who had calibrated has to walk the known distance again.
     */
    var stepScale: Double
        get() = p.getFloat(key("stepScale"), StepDetector.DEFAULT_SCALE.toFloat()).toDouble()
        set(v) = p.edit().putFloat(key("stepScale"), v.toFloat()).apply()

    /** Step scale prior learned by the filter during previous sessions. */
    var kMean: Double
        get() = p.getFloat(key("kMean"), p.getFloat("kMean", 1f)).toDouble()
        set(v) = p.edit().putFloat(key("kMean"), v.toFloat()).apply()
    var kStd: Double
        get() = p.getFloat(key("kStd"), p.getFloat("kStd", 0.1f)).toDouble()
        set(v) = p.edit().putFloat(key("kStd"), v.toFloat()).apply()

    var mounting: Mounting
        get() = runCatching { Mounting.valueOf(p.getString("mounting", null)!!) }.getOrDefault(Mounting.CHEST_SCREEN_OUT)
        set(v) = p.edit().putString("mounting", v.name).apply()

    var particles: Int
        get() = p.getInt("particles", 1000)
        set(v) = p.edit().putInt("particles", v).apply()

    var useCompass: Boolean
        get() = p.getBoolean("useCompass", true)
        set(v) = p.edit().putBoolean("useCompass", v).apply()

    var useBarometer: Boolean
        get() = p.getBoolean("useBarometer", true)
        set(v) = p.edit().putBoolean("useBarometer", v).apply()

    /**
     * Log GPS fixes next to the predicted position, so the error can be measured afterwards.
     * For validation walks above ground: underground there is no GPS and it only costs battery.
     * The fixes are never fed back into the filter — that would make the comparison circular.
     */
    var logGps: Boolean
        get() = p.getBoolean("logGps", false)
        set(v) = p.edit().putBoolean("logGps", v).apply()

    var logRawSensors: Boolean
        get() = p.getBoolean("logRawSensors", false)
        set(v) = p.edit().putBoolean("logRawSensors", v).apply()

    /** OsmAnd package to talk to; empty = first installed one. */
    var osmandPackage: String
        get() = p.getString("osmandPackage", "") ?: ""
        set(v) = p.edit().putString("osmandPackage", v).apply()

    var overpassRadius: Int
        get() = p.getInt("overpassRadius", 1500)
        set(v) = p.edit().putInt("overpassRadius", v).apply()
}
