package net.jfloren.raceday.nmea

import java.util.Locale

class NmeaParser(
    // Monotonic milliseconds, for telling when a compass has stopped sending
    private val clock: () -> Long = ::monotonicMillis
) {

    /**
     * Parses an NMEA line and updates [currentData] with any new values found.
     * Returns updated [NmeaData].
     */
    fun parse(line: String, currentData: NmeaData = NmeaData()): NmeaData {
        val cleanLine = line.trim()
        if (!cleanLine.startsWith("$")) return currentData

        // Checksum validation if present
        val asteriskIndex = cleanLine.indexOf('*')
        val sentence = if (asteriskIndex != -1) {
            val checksumStr = cleanLine.substring(asteriskIndex + 1)
            val content = cleanLine.substring(1, asteriskIndex)
            if (!validateChecksum(content, checksumStr)) {
                return currentData
            }
            content
        } else {
            cleanLine.substring(1)
        }

        val tokens = sentence.split(",")
        if (tokens.isEmpty()) return currentData

        fun number(index: Int) = tokens.getOrNull(index)?.trim()?.toDoubleOrNull()
        // An angle with an E/W hemisphere in the next field, east positive
        fun eastPositive(index: Int) = number(index)?.let { if (tokens.getOrNull(index + 1).equals("W", ignoreCase = true)) -it else it }

        val type = tokens[0].uppercase(Locale.US)

        return when {
            // VTG: Track made good and ground speed
            // e.g., $GPVTG,054.7,T,034.4,M,005.5,N,010.2,K,A*25
            type.endsWith("VTG") -> {
                // Mode indicator N means the data isn't valid
                if (tokens.getOrNull(9).equals("N", ignoreCase = true)) return currentData
                currentData.copy(
                    sogKnots = number(5) ?: currentData.sogKnots,
                    cogTrue = number(1) ?: currentData.cogTrue,
                    // Not kept from an earlier sentence, so it can't go out of step with cogTrue
                    cogMagnetic = number(3)
                )
            }

            // RMC: Recommended Minimum Specific GPS/TRANSIT Data
            // e.g., $GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A
            type.endsWith("RMC") -> {
                // Status V means no valid fix
                if (!tokens.getOrNull(2).equals("A", ignoreCase = true)) return currentData
                currentData.copy(
                    sogKnots = number(7) ?: currentData.sogKnots,
                    cogTrue = number(8) ?: currentData.cogTrue,
                    variation = eastPositive(10) ?: currentData.variation
                )
            }

            // HDT: Heading, True
            // e.g., $HEHDT,274.1,T*2F
            type.endsWith("HDT") -> number(1)?.let {
                currentData.copy(headingTrue = it, headingTrueAt = clock())
            } ?: currentData

            // HDM: Heading, Magnetic
            // e.g., $HCHDM,238.5,M*29
            type.endsWith("HDM") -> number(1)?.let {
                currentData.copy(headingMagnetic = it, headingMagneticAt = clock())
            } ?: currentData

            // HDG: sensor heading, deviation and variation; magnetic heading = sensor + deviation
            // e.g., $HCHDG,101.1,,,7.1,W*3C
            type.endsWith("HDG") -> number(1)?.let { sensor ->
                currentData.copy(
                    headingMagnetic = sensor + (eastPositive(2) ?: 0.0),
                    headingMagneticAt = clock(),
                    variation = eastPositive(4) ?: currentData.variation
                )
            } ?: currentData

            else -> currentData
        }
    }

    companion object {
        /** The parser's default clock, for comparing against the times it records. */
        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
    }

    private fun validateChecksum(content: String, expectedChecksumHex: String): Boolean {
        if (expectedChecksumHex.length < 2) return false
        var checksum = 0
        for (ch in content) {
            checksum = checksum xor ch.code
        }
        val calculatedHex = String.format(Locale.US, "%02X", checksum)
        return calculatedHex.equals(expectedChecksumHex.take(2), ignoreCase = true)
    }
}
