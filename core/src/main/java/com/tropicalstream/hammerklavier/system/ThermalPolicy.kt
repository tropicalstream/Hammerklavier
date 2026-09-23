package com.tropicalstream.hammerklavier.system

/**
 * Battery temperature (tenths °C) + PowerManager thermal status → quality level 0..3 with
 * hysteresis (PLAN §5.11). Entering jumps straight to the highest level whose threshold is met;
 * relaxing goes down one level at a time, only below that level's relax temperature and only
 * when the status no longer demands it. Pure; the governor (app) owns the lifetime and the
 * `--ei quality` / `--ei faketemp` overrides.
 *
 * | Level | Enter: battery ≥ or status ≥ | Relax below |
 * |---|---|---|
 * | Q1 | 39.0 °C or MODERATE (2) | 37.5 °C |
 * | Q2 | 42.0 °C or SEVERE (3) | 40.5 °C |
 * | Q3 | 44.0 °C or CRITICAL (4) | 42.5 °C |
 */
class ThermalPolicy(initialLevel: Int = 0) {
    var level: Int = initialLevel.coerceIn(0, 3)
        private set

    /** Feeds one reading; returns the (possibly changed) level. */
    fun update(batteryTenths: Int, thermalStatus: Int = 0): Int {
        val demanded = demanded(batteryTenths, thermalStatus)
        if (demanded > level) level = demanded
        else if (level > 0 && demanded < level && batteryTenths < RELAX_BELOW[level]) level -= 1
        return level
    }

    companion object {
        /** PowerManager.THERMAL_STATUS_MODERATE / SEVERE / CRITICAL. */
        const val STATUS_MODERATE = 2; const val STATUS_SEVERE = 3; const val STATUS_CRITICAL = 4
        private val ENTER_AT = intArrayOf(0, 390, 420, 440)          // tenths °C
        private val RELAX_BELOW = intArrayOf(0, 375, 405, 425)

        fun demanded(batteryTenths: Int, thermalStatus: Int): Int = when {
            batteryTenths >= ENTER_AT[3] || thermalStatus >= STATUS_CRITICAL -> 3
            batteryTenths >= ENTER_AT[2] || thermalStatus >= STATUS_SEVERE -> 2
            batteryTenths >= ENTER_AT[1] || thermalStatus >= STATUS_MODERATE -> 1
            else -> 0
        }
    }
}
