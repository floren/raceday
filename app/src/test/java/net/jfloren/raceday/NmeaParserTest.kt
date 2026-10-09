package net.jfloren.raceday

import net.jfloren.raceday.nmea.Direction
import net.jfloren.raceday.nmea.DirectionKind
import net.jfloren.raceday.nmea.NmeaData
import net.jfloren.raceday.nmea.NmeaParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class NmeaParserTest {

    private var now = 100_000L
    private val parser = NmeaParser(clock = { now })

    /** Adds a valid checksum to a sentence body, e.g. "GPVTG,..." */
    private fun sentence(body: String): String {
        val checksum = body.fold(0) { acc, ch -> acc xor ch.code }
        return "$" + body + "*" + String.format(Locale.US, "%02X", checksum)
    }

    private fun parseAll(vararg bodies: String): NmeaData =
        bodies.fold(NmeaData()) { data, body -> parser.parse(sentence(body), data) }

    private fun assertDirection(expected: Double, kind: DirectionKind, magnetic: Boolean, actual: Direction?) {
        assertEquals(expected, actual!!.degrees, 0.001)
        assertEquals(kind, actual.kind)
        assertEquals(magnetic, actual.magnetic)
    }

    @Test
    fun testParseVtgSentence() {
        val result = parser.parse("\$GPVTG,054.7,T,034.4,M,005.5,N,010.2,K,A*25")

        assertEquals(5.5, result.sogKnots!!, 0.001)
        assertEquals(54.7, result.cogTrue!!, 0.001)
        // VTG's own magnetic course is preferred
        assertDirection(34.4, DirectionKind.COG, magnetic = true, actual = result.direction(now))
    }

    @Test
    fun testParseRmcSentence() {
        val result = parser.parse("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A")

        assertEquals(22.4, result.sogKnots!!, 0.001)
        assertEquals(84.4, result.cogTrue!!, 0.001)
        assertEquals(-3.1, result.variation!!, 0.001)
        // 84.4 true with 3.1 W variation is 87.5 magnetic
        assertDirection(87.5, DirectionKind.COG, magnetic = true, actual = result.direction(now))
    }

    @Test
    fun testRmcWithoutFixIgnored() {
        val initial = NmeaData(sogKnots = 10.0, cogTrue = 180.0)
        val result = parser.parse(sentence("GPRMC,123519,V,,,,,000.0,000.0,230394,,"), initial)

        assertEquals(initial, result)
    }

    @Test
    fun testTrueCourseShownAsTrueWithoutVariation() {
        val result = parseAll("GPVTG,054.7,T,,M,005.5,N,010.2,K,A")

        assertDirection(54.7, DirectionKind.COG, magnetic = false, actual = result.direction(now))
    }

    @Test
    fun testParseHdtSentence() {
        val result = parser.parse("\$HEHDT,274.1,T*2F")

        assertNull(result.sogKnots)
        assertEquals(274.1, result.headingTrue!!, 0.001)
        assertDirection(274.1, DirectionKind.HEADING, magnetic = false, actual = result.direction(now))
    }

    @Test
    fun testHdgAppliesDeviationAndRecordsVariation() {
        // Sensor 101.1, deviation 2.0 W, variation 7.1 E
        val result = parseAll("HCHDG,101.1,2.0,W,7.1,E")

        assertEquals(99.1, result.headingMagnetic!!, 0.001)
        assertEquals(7.1, result.variation!!, 0.001)
        assertDirection(99.1, DirectionKind.HEADING, magnetic = true, actual = result.direction(now))
    }

    @Test
    fun testHeadingDoesNotFlipBetweenSources() {
        // GPS course (true, 10 W variation), compass heading magnetic, and a true heading, interleaved.
        // All describe the boat heading roughly 090 magnetic, so the display should stay there.
        var data = NmeaData()
        val bodies = listOf(
            "GPRMC,123519,A,4807.038,N,01131.000,E,005.0,075.0,230394,010.0,W",
            "HCHDM,090.0,M",
            "HEHDT,080.0,T",
            "GPRMC,123520,A,4807.038,N,01131.000,E,005.0,075.0,230394,010.0,W",
            "HCHDM,090.0,M"
        )
        for (body in bodies) {
            data = parser.parse(sentence(body), data)
            val direction = data.direction(now)
            if (data.headingMagnetic != null) {
                assertDirection(90.0, DirectionKind.HEADING, magnetic = true, actual = direction)
            }
        }
    }

    @Test
    fun testTrueHeadingConvertedToMagnetic() {
        val result = parseAll(
            "GPRMC,123519,A,4807.038,N,01131.000,E,005.0,075.0,230394,010.0,W",
            "HEHDT,080.0,T"
        )
        assertDirection(90.0, DirectionKind.HEADING, magnetic = true, actual = result.direction(now))
    }

    @Test
    fun testStaleHeadingFallsBackToCourse() {
        val result = parseAll(
            "HCHDM,090.0,M",
            "GPVTG,054.7,T,034.4,M,005.5,N,010.2,K,A"
        )
        assertDirection(90.0, DirectionKind.HEADING, magnetic = true, actual = result.direction(now))

        now += NmeaData.HEADING_STALE_MS + 1
        assertDirection(34.4, DirectionKind.COG, magnetic = true, actual = result.direction(now))
    }

    @Test
    fun testDirectionWrapsTo0To359() {
        // 2 true with 5 E variation is 357 magnetic
        val result = parseAll(
            "GPRMC,123519,A,4807.038,N,01131.000,E,005.0,002.0,230394,005.0,E"
        )
        assertEquals(357, result.direction(now)!!.wholeDegrees)
    }

    @Test
    fun testInvalidChecksumRejected() {
        val initial = NmeaData(sogKnots = 10.0, headingTrue = 180.0)
        val result = parser.parse("\$HEHDT,274.1,T*00", initial)

        assertEquals(initial, result)
    }
}
