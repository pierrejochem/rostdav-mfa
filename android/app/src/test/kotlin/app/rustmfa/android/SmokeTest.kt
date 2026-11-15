package app.rustmfa.android

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun theApplicationIdIsTheDocumentedOne() {
        assertEquals("app.rustmfa.android", BuildConfig.APPLICATION_ID)
    }
}
