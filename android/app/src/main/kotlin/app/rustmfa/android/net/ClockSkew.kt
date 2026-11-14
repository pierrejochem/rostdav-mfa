package app.rustmfa.android.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

fun interface Clock {
    fun nowMillis(): Long
}

/**
 * The phone's clock corrected by the `Date` header of the server's answers. The server rejects a
 * request more than 60 seconds off, so the signed `ts` comes from here, not from the system clock.
 * The header has second precision; that error is fine against a 60 second window.
 */
class ClockSkew(private val system: Clock = Clock { System.currentTimeMillis() }) : Clock {
    @Volatile
    var offsetMillis: Long = 0L
        private set

    fun update(dateHeader: String?) {
        if (dateHeader == null) return
        val server = try {
            ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            return
        }
        offsetMillis = server - system.nowMillis()
    }

    override fun nowMillis(): Long = system.nowMillis() + offsetMillis
}
