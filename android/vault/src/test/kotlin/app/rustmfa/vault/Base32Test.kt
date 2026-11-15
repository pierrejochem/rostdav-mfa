package app.rustmfa.vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base32Test {
    private val vectors = listOf(
        "" to "",
        "f" to "MY======",
        "fo" to "MZXQ====",
        "foo" to "MZXW6===",
        "foob" to "MZXW6YQ=",
        "fooba" to "MZXW6YTB",
        "foobar" to "MZXW6YTBOI======",
    )

    @Test
    fun encodesRfcVectors() {
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, Base32.encode(plain.toByteArray()))
        }
    }

    @Test
    fun decodesRfcVectors() {
        for ((plain, encoded) in vectors) {
            assertContentEquals(plain.toByteArray(), Base32.decode(encoded))
        }
    }

    @Test
    fun decodeIgnoresSpacesHyphensAndCase() {
        assertContentEquals("foobar".toByteArray(), Base32.decode("mzxw 6ytb-oi"))
    }

    @Test
    fun decodeAcceptsMissingPadding() {
        assertContentEquals("foo".toByteArray(), Base32.decode("MZXW6"))
    }

    @Test
    fun decodeRejectsOtherCharacters() {
        assertFailsWith<IllegalArgumentException> { Base32.decode("MZ1W") }
    }
}
