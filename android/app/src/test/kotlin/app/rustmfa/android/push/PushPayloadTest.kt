package app.rustmfa.android.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PushPayloadTest {
    @Test fun goodId() = assertEquals("ch_1-A", PushPayload.challengeId(mapOf("challenge_id" to "ch_1-A")))

    @Test fun extraKeysAreIgnored() =
        assertEquals("ch_1", PushPayload.challengeId(mapOf("challenge_id" to "ch_1", "number" to "47", "user" to "alice")))

    @Test fun missingEmptyAndBadIdsGiveNull() {
        assertNull(PushPayload.challengeId(emptyMap()))
        assertNull(PushPayload.challengeId(mapOf("challenge_id" to "")))
        assertNull(PushPayload.challengeId(mapOf("challenge_id" to "ch\n1")))
        assertNull(PushPayload.challengeId(mapOf("challenge_id" to "ch\u0000")))
        assertNull(PushPayload.challengeId(mapOf("challenge_id" to "../approve")))
        assertNull(PushPayload.challengeId(mapOf("challenge_id" to "a".repeat(65))))
        assertEquals("a".repeat(64), PushPayload.challengeId(mapOf("challenge_id" to "a".repeat(64))))
    }

    @Test fun onlyTheIdIsForwarded() {
        // the function returns a bare id: a number or a user name in the payload cannot travel with it
        val id = PushPayload.challengeId(mapOf("challenge_id" to "ch_1", "selected_number" to "47", "user_name" to "alice"))
        assertEquals("ch_1", id)
    }
}
