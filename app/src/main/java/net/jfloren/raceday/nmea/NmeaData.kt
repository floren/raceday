package net.jfloren.raceday.nmea

/**
 * Latest values from the NMEA stream. Course and heading from different sentences are kept apart,
 * since they differ (true vs magnetic, and heading vs course over ground); [direction] picks one.
 */
data class NmeaData(
    val sogKnots: Double? = null,
    // Course over ground, from the GPS
    val cogTrue: Double? = null,
    val cogMagnetic: Double? = null,
    // Heading, from a compass; with the time it was received (milliseconds, from the parser's clock)
    val headingTrue: Double? = null,
    val headingTrueAt: Long? = null,
    val headingMagnetic: Double? = null,
    val headingMagneticAt: Long? = null,
    // Magnetic variation, east positive (true = magnetic + variation)
    val variation: Double? = null
) {
    val hasData: Boolean
        get() = sogKnots != null || cogTrue != null || cogMagnetic != null ||
                headingTrue != null || headingMagnetic != null

    /**
     * Magnetic heading while a compass is sending, otherwise course over ground. Magnetic where
     * possible; true only if there's no variation to convert with.
     */
    fun direction(now: Long): Direction? {
        fun fresh(at: Long?) = at != null && now - at <= HEADING_STALE_MS
        fun toMagnetic(trueDegrees: Double) = variation?.let { Direction(trueDegrees - it, DirectionKind.HEADING, magnetic = true) }

        if (headingMagnetic != null && fresh(headingMagneticAt)) {
            return Direction(headingMagnetic, DirectionKind.HEADING, magnetic = true)
        }
        if (headingTrue != null && fresh(headingTrueAt)) {
            return toMagnetic(headingTrue) ?: Direction(headingTrue, DirectionKind.HEADING, magnetic = false)
        }
        if (cogMagnetic != null) return Direction(cogMagnetic, DirectionKind.COG, magnetic = true)
        if (cogTrue != null) {
            return toMagnetic(cogTrue)?.copy(kind = DirectionKind.COG)
                ?: Direction(cogTrue, DirectionKind.COG, magnetic = false)
        }
        return null
    }

    companion object {
        // A compass that's stopped sending for this long gives way to the GPS course
        const val HEADING_STALE_MS = 5_000L
    }
}

enum class DirectionKind { HEADING, COG }

data class Direction(val degrees: Double, val kind: DirectionKind, val magnetic: Boolean) {
    /** Whole degrees, 0-359. */
    val wholeDegrees: Int get() = Math.round(degrees).toInt().mod(360)
}
