package app.rustmfa.android.net

import kotlin.test.Test
import kotlin.test.assertEquals

class ClockSkewTest {
    private var system = 1_793_200_000_000L
    private val skew = ClockSkew { system }

    @Test
    fun withoutAnAnswerTheClockIsTheSystemClock() {
        assertEquals(system, skew.nowMillis())
        assertEquals(0L, skew.offsetMillis)
    }

    @Test
    fun aDateHeaderFiveMinutesAheadMovesTheClock() {
        skew.update(httpDate(system + 300_000))
        assertEquals(300_000L, skew.offsetMillis)
        assertEquals(system + 300_000, skew.nowMillis())
        system += 5_000
        assertEquals(system + 300_000, skew.nowMillis())
    }

    @Test
    fun aBadDateHeaderChangesNothing() {
        skew.update(httpDate(system + 300_000))
        skew.update("yesterday-ish")
        skew.update(null)
        assertEquals(300_000L, skew.offsetMillis)
    }

    @Test
    fun theDateHeaderHasSecondPrecisionSoTheOffsetIsRoundedToThatError() {
        // 1_793_200_000_500 is half a second past the second; the header drops the fraction
        system = 1_793_200_000_500L
        skew.update(httpDate(system))
        assertEquals(-500L, skew.offsetMillis)
    }

    companion object {
        fun httpDate(millis: Long): String =
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC),
            )
    }
}
