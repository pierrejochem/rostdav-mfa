package app.rustmfa.android.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class AttestationChainTest {
    /** KeyDescription: SEQUENCE { INTEGER version, ENUMERATED level, INTEGER kmVersion, ENUMERATED kmLevel, OCTET STRING challenge, ... }. */
    private fun keyDescription(challenge: ByteArray): ByteArray {
        val body = byteArrayOf(0x02, 0x01, 0x04) + byteArrayOf(0x0a, 0x01, 0x01) +
            byteArrayOf(0x02, 0x01, 0x04) + byteArrayOf(0x0a, 0x01, 0x01) +
            byteArrayOf(0x04, challenge.size.toByte()) + challenge +
            byteArrayOf(0x04, 0x00) // uniqueId
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    @Test
    fun theChallengeIsTheFifthElement() {
        val challenge = ByteArray(32) { it.toByte() }
        assertContentEquals(challenge, AttestationChain.challengeFromKeyDescription(keyDescription(challenge)))
    }

    @Test
    fun aLongChallengeUsesTheLongLengthForm() {
        val challenge = ByteArray(200) { (it * 7).toByte() }
        val body = byteArrayOf(0x02, 0x01, 0x04, 0x0a, 0x01, 0x01, 0x02, 0x01, 0x04, 0x0a, 0x01, 0x01) +
            byteArrayOf(0x04, 0x81.toByte(), 200.toByte()) + challenge
        val der = byteArrayOf(0x30, 0x81.toByte(), body.size.toByte()) + body
        assertContentEquals(challenge, AttestationChain.challengeFromKeyDescription(der))
    }

    @Test
    fun garbageGivesNull() {
        assertNull(AttestationChain.challengeFromKeyDescription(byteArrayOf(1, 2, 3)))
        assertNull(AttestationChain.challengeFromKeyDescription(byteArrayOf()))
        // truncated: length says 32, only 3 bytes follow
        assertNull(AttestationChain.challengeFromKeyDescription(byteArrayOf(0x30, 0x05, 0x04, 0x20, 1, 2, 3)))
    }
}
