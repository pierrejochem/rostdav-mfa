package app.rustmfa.vault

import app.rustmfa.vault.OtpAuthException.Reason
import java.io.ByteArrayOutputStream

object OtpAuthUri {
    private const val SCHEME = "otpauth://"

    fun parse(uri: String, newId: () -> String = { newAccountId() }): OtpAccount {
        if (!uri.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
            throw OtpAuthException(Reason.NotOtpAuth)
        }
        var rest = uri.substring(SCHEME.length)
        rest.indexOf('#').let { if (it >= 0) rest = rest.substring(0, it) }
        val queryStart = rest.indexOf('?')
        val pathPart = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val queryPart = if (queryStart >= 0) rest.substring(queryStart + 1) else ""

        val slash = pathPart.indexOf('/')
        val typeText = if (slash >= 0) pathPart.substring(0, slash) else pathPart
        val labelText = if (slash >= 0) pathPart.substring(slash + 1) else ""
        val type = when (typeText.lowercase()) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> throw OtpAuthException(Reason.UnknownType)
        }

        val params = HashMap<String, String>()
        for (pair in queryPart.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = percentDecode(if (eq >= 0) pair.substring(0, eq) else pair)
            val value = percentDecode(if (eq >= 0) pair.substring(eq + 1) else "")
            params.putIfAbsent(key, value)
        }

        val secretText = params["secret"] ?: throw OtpAuthException(Reason.MissingSecret)
        val secret = try {
            Base32.decode(secretText)
        } catch (e: Exception) {
            throw OtpAuthException(Reason.BadSecret)
        }
        if (secret.isEmpty()) throw OtpAuthException(Reason.BadSecret)

        val algorithm = params["algorithm"]?.let { name ->
            OtpAlgorithm.entries.firstOrNull { it.uriName.equals(name, ignoreCase = true) }
                ?: throw OtpAuthException(Reason.BadAlgorithm)
        } ?: OtpAlgorithm.SHA1
        val digits = params["digits"]?.let {
            it.toIntOrNull()?.takeIf { d -> d == 6 || d == 8 } ?: throw OtpAuthException(Reason.BadDigits)
        } ?: 6
        val period = params["period"]?.let {
            it.toIntOrNull()?.takeIf { p -> p in 1..3600 } ?: throw OtpAuthException(Reason.BadPeriod)
        } ?: 30
        val counter = if (type == OtpType.HOTP) {
            val text = params["counter"] ?: throw OtpAuthException(Reason.MissingCounter)
            text.toLongOrNull()?.takeIf { it >= 0 } ?: throw OtpAuthException(Reason.BadCounter)
        } else {
            0L
        }

        var issuer = ""
        val name: String
        val literal = labelText.indexOf(':')
        val encoded = if (literal >= 0) -1 else labelText.indexOf("%3A", ignoreCase = true)
        if (literal >= 0) {
            issuer = percentDecode(labelText.substring(0, literal))
            name = percentDecode(labelText.substring(literal + 1))
        } else if (encoded >= 0) {
            issuer = percentDecode(labelText.substring(0, encoded))
            name = percentDecode(labelText.substring(encoded + 3))
        } else {
            name = percentDecode(labelText)
        }
        params["issuer"]?.let { issuer = it }

        return OtpAccount(newId(), type, issuer, name, secret, algorithm, digits, period, counter)
    }

    fun build(account: OtpAccount): String {
        val sb = StringBuilder(SCHEME)
        sb.append(if (account.type == OtpType.TOTP) "totp" else "hotp").append('/')
        if (account.issuer.isNotEmpty()) sb.append(percentEncode(account.issuer)).append(':')
        else if (account.label.contains(':')) sb.append(':')
        sb.append(percentEncode(account.label))
        sb.append("?secret=").append(Base32.encode(account.secret).trimEnd('='))
        if (account.issuer.isNotEmpty()) sb.append("&issuer=").append(percentEncode(account.issuer))
        if (account.algorithm != OtpAlgorithm.SHA1) sb.append("&algorithm=").append(account.algorithm.uriName)
        if (account.digits != 6) sb.append("&digits=").append(account.digits)
        if (account.type == OtpType.TOTP) {
            if (account.period != 30) sb.append("&period=").append(account.period)
        } else {
            sb.append("&counter=").append(account.counter)
        }
        return sb.toString()
    }

    private fun percentDecode(text: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length && hex(text[i + 1]) >= 0 && hex(text[i + 2]) >= 0) {
                out.write(hex(text[i + 1]) * 16 + hex(text[i + 2]))
                i += 3
            } else {
                val cp = text.codePointAt(i)
                out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                i += Character.charCount(cp)
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun hex(c: Char): Int = Character.digit(c, 16)

    private fun percentEncode(text: String): String {
        val sb = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 0x80 && (c.isLetterOrDigit() || c in "-._~")) {
                sb.append(c)
            } else {
                sb.append('%').append("0123456789ABCDEF"[v shr 4]).append("0123456789ABCDEF"[v and 15])
            }
        }
        return sb.toString()
    }
}
