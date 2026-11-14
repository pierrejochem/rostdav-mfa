package app.rustmfa.android.crypto

import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Base64

object AttestationChain {
    private const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"

    fun encode(chain: Array<out Certificate>): List<String> =
        chain.map { Base64.getEncoder().encodeToString(it.encoded) }

    /** The attestation challenge in the leaf's key description extension, or null. */
    fun challenge(leaf: X509Certificate): ByteArray? {
        val wrapped = leaf.getExtensionValue(KEY_DESCRIPTION_OID) ?: return null
        // the extension value is an OCTET STRING that holds the KeyDescription
        val outer = header(wrapped, 0) ?: return null
        if (outer.tag != 0x04) return null
        return challengeFromKeyDescription(wrapped.copyOfRange(outer.start, outer.end))
    }

    /** `KeyDescription ::= SEQUENCE { version, securityLevel, kmVersion, kmSecurityLevel, attestationChallenge OCTET STRING, ... }` */
    fun challengeFromKeyDescription(der: ByteArray): ByteArray? {
        val seq = header(der, 0) ?: return null
        if (seq.tag != 0x30) return null
        var pos = seq.start
        repeat(4) {
            val h = header(der, pos) ?: return null
            if (h.end > seq.end) return null
            pos = h.end
        }
        val challenge = header(der, pos) ?: return null
        if (challenge.tag != 0x04 || challenge.end > seq.end) return null
        return der.copyOfRange(challenge.start, challenge.end)
    }

    private class Header(val tag: Int, val start: Int, val end: Int)

    /** Reads one DER header at [pos]; null when it does not fit in [b]. */
    private fun header(b: ByteArray, pos: Int): Header? {
        if (pos + 2 > b.size) return null
        val tag = b[pos].toInt() and 0xff
        val first = b[pos + 1].toInt() and 0xff
        var start = pos + 2
        val len: Int
        if (first < 0x80) {
            len = first
        } else {
            val n = first and 0x7f
            if (n == 0 || n > 3 || start + n > b.size) return null
            var v = 0
            for (i in 0 until n) v = (v shl 8) or (b[start + i].toInt() and 0xff)
            start += n
            len = v
        }
        val end = start + len
        if (len < 0 || end > b.size) return null
        return Header(tag, start, end)
    }
}
