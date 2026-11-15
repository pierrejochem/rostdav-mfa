package app.rustmfa.contract

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec

/** ES256 signature handling: DER to raw R||S, and verification of a raw signature. */
object Signature {
    private val p256: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    private val order: BigInteger = p256.order

    private fun bad(): Nothing = throw ContractException(ContractErrorKind.BadSignatureEncoding)

    private fun scalarOk(v: BigInteger) = v.signum() > 0 && v < order

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return ByteArray(32 - b.size) + b
    }

    /** Strict DER reader for `SEQUENCE { INTEGER r, INTEGER s }`; anything else is `BadSignatureEncoding`. */
    fun derToRaw(der: ByteArray): ByteArray {
        var pos = 0
        fun byte(): Int {
            if (pos >= der.size) bad()
            return der[pos++].toInt() and 0xff
        }
        fun length(): Int {
            val first = byte()
            if (first < 0x80) return first
            if (first != 0x81) bad() // long form only up to 255, as in minimal DER
            val n = byte()
            if (n < 0x80) bad() // would have fitted the short form
            return n
        }
        fun integer(): BigInteger {
            if (byte() != 0x02) bad()
            val len = length()
            if (len == 0 || pos + len > der.size) bad()
            val bytes = der.copyOfRange(pos, pos + len)
            pos += len
            if (bytes[0] < 0) bad() // negative
            if (bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1] >= 0) bad() // not minimal
            return BigInteger(bytes)
        }
        if (byte() != 0x30) bad()
        val seqLen = length()
        if (pos + seqLen != der.size) bad()
        val r = integer()
        val s = integer()
        if (pos != der.size) bad()
        if (!scalarOk(r) || !scalarOk(s)) bad()
        return fixed32(r) + fixed32(s)
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            val v = BigInteger(1, b).toByteArray()
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun parsePublicKey(spkiDer: ByteArray): ECPublicKey {
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiDer)) as? ECPublicKey
        } catch (e: Exception) {
            null
        } ?: throw ContractException(ContractErrorKind.BadPublicKey)
        val p = (p256.curve.field as java.security.spec.ECFieldFp).p
        val w = key.w
        val onCurve = w.affineX.signum() >= 0 && w.affineX < p && w.affineY.signum() >= 0 && w.affineY < p &&
            w.affineY.pow(2).mod(p) == (w.affineX.pow(3) + p256.curve.a * w.affineX + p256.curve.b).mod(p)
        if (key.params.curve != p256.curve || key.params.order != order || key.params.generator != p256.generator || !onCurve) {
            throw ContractException(ContractErrorKind.BadPublicKey)
        }
        return key
    }

    /** `raw` is the 64-byte R||S form. Throws `ContractException` unless the signature verifies. */
    fun verifyEs256Raw(spkiDer: ByteArray, message: ByteArray, raw: ByteArray) {
        val key = parsePublicKey(spkiDer)
        if (raw.size != 64) bad()
        if (!scalarOk(BigInteger(1, raw.copyOfRange(0, 32))) || !scalarOk(BigInteger(1, raw.copyOfRange(32, 64)))) bad()
        val ok = java.security.Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message)
            verify(rawToDer(raw))
        }
        if (!ok) throw ContractException(ContractErrorKind.VerifyFailed)
    }
}
