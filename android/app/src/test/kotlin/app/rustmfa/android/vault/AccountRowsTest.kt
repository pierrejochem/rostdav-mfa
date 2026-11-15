package app.rustmfa.android.vault

import app.rustmfa.vault.Otp
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class AccountRowsTest {
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)
    private val totp = OtpAccount("t", OtpType.TOTP, "Example", "alice", rfcSecret, digits = 8, period = 30)
    private val hotp = OtpAccount("h", OtpType.HOTP, "Counter", "bob", rfcSecret, digits = 6)

    private fun row(account: OtpAccount, epoch: Long, hotpCodes: Map<String, String> = emptyMap()) =
        buildRows(listOf(account), epoch, hotpCodes) { Otp.totp(it, epoch) }.single()

    @Test
    fun `one second left at 59 is urgent`() {
        val r = row(totp, 59)
        assertEquals(1, r.secondsLeft)
        assertTrue(r.urgent)
    }

    @Test
    fun `a fresh period at 60 has 30 seconds and is not urgent`() {
        val r = row(totp, 60)
        assertEquals(30, r.secondsLeft)
        assertFalse(r.urgent)
    }

    @Test
    fun `five seconds left is urgent, six is not`() {
        val five = row(totp, 55)
        assertEquals(5, five.secondsLeft)
        assertTrue(five.urgent)
        val six = row(totp, 54)
        assertEquals(6, six.secondsLeft)
        assertFalse(six.urgent)
    }

    @Test
    fun `the code text is the grouped RFC 6238 code`() {
        val r = row(totp, 59)
        assertEquals("9428 7082", r.code)
        assertEquals(30, r.period)
        assertEquals("Example", r.issuer)
        assertEquals("alice", r.label)
        assertEquals(OtpType.TOTP, r.type)
        assertEquals("t", r.id)
    }

    @Test
    fun `a HOTP row has no ring data and no code until generated`() {
        val hidden = row(hotp, 59)
        assertEquals(0, hidden.secondsLeft)
        assertFalse(hidden.urgent)
        assertNull(hidden.code)
        val shown = row(hotp, 59, mapOf("h" to "755224"))
        assertEquals("755 224", shown.code)
        assertEquals(0, shown.secondsLeft)
        assertFalse(shown.urgent)
    }

    @Test
    fun `buildRows never asks for a HOTP code`() {
        var asked = 0
        buildRows(listOf(hotp), 59, emptyMap()) { asked++; "000000" }
        assertEquals(0, asked)
    }

    @Test
    fun `secondTicker emits now then waits for the next second boundary`() = runTest {
        val base = 1_234_567_890_123L
        val clock = { base + currentTime }
        val seen = mutableListOf<Pair<Long, Long>>()
        val job = launch { secondTicker(clock).take(3).collect { seen += it to currentTime } }
        runCurrent()
        assertEquals(listOf(1_234_567_890L to 0L), seen)
        advanceTimeBy(876)
        runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1_234_567_891L to 877L, seen[1])
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1_234_567_892L to 1877L, seen[2])
        job.join()
    }

    @Test
    fun `secondTicker first value is the current epoch second`() = runTest {
        assertEquals(42L, secondTicker { 42_999L }.first())
        assertEquals(3, secondTicker { 0L }.take(3).toList().size)
    }
}
