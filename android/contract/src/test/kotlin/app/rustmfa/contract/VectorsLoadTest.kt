package app.rustmfa.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorsLoadTest {
    @Test
    fun theSharedVectorsLoad() {
        val v = Vectors.load()
        assertEquals(1, v.version)
        assertEquals(11, v.challengeCases.size)
        assertEquals(2, v.requestCases.size)
        assertTrue(v.key.spkiDerB64.isNotEmpty())
    }
}
