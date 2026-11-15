package app.rustmfa.android.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PassphraseTest {
    private fun problem(a: String, b: String) = passphraseProblem(a.toCharArray(), b.toCharArray())

    @Test
    fun `seven characters are too short`() = assertEquals(PassphraseProblem.TooShort, problem("1234567", "1234567"))

    @Test
    fun `eight characters are enough`() = assertNull(problem("12345678", "12345678"))

    @Test
    fun `a different repeat is a mismatch`() = assertEquals(PassphraseProblem.Mismatch, problem("12345678", "12345679"))

    @Test
    fun `composed and decomposed forms match`() = assertNull(problem("café-pass", "café-pass"))

    @Test
    fun `a repeat of different length is a mismatch`() = assertEquals(PassphraseProblem.Mismatch, problem("12345678", "123456789"))

    @Test
    fun `length counts code points after normalisation`() {
        // seven characters once the combining accent is composed
        assertEquals(PassphraseProblem.TooShort, problem("caféabc", "caféabc"))
        // four emoji are eight UTF-16 units but four code points
        val emoji = "😀".repeat(4)
        assertEquals(PassphraseProblem.TooShort, problem(emoji, emoji))
    }
}
