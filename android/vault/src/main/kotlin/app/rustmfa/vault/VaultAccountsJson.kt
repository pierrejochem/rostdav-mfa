package app.rustmfa.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The account list as UTF-8 JSON `{"v":1,"accounts":[...]}` with base32 secrets (vault blob and backup plaintext). */
object VaultAccountsJson {
    private const val VERSION = 1

    @Serializable
    private data class Doc(val v: Int, val accounts: List<Entry>)

    @Serializable
    private data class Entry(
        val id: String,
        val type: OtpType,
        val issuer: String,
        val label: String,
        val secret: String,
        val algorithm: OtpAlgorithm,
        val digits: Int,
        val period: Int,
        val counter: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(accounts: List<OtpAccount>): ByteArray {
        val doc = Doc(
            VERSION,
            accounts.map {
                Entry(it.id, it.type, it.issuer, it.label, Base32.encode(it.secret), it.algorithm, it.digits, it.period, it.counter)
            },
        )
        return json.encodeToString(Doc.serializer(), doc).toByteArray(Charsets.UTF_8)
    }

    /** Throws [IllegalArgumentException] on bad JSON, an unknown `v`, or an invalid account. Messages carry no secrets. */
    fun decode(bytes: ByteArray): List<OtpAccount> {
        val doc = try {
            json.decodeFromString(Doc.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid account JSON")
        }
        require(doc.v == VERSION) { "unknown account JSON version" }
        return doc.accounts.map { e ->
            try {
                OtpAccount(e.id, e.type, e.issuer, e.label, Base32.decode(e.secret), e.algorithm, e.digits, e.period, e.counter)
            } catch (x: Exception) {
                throw IllegalArgumentException("invalid account")
            }
        }
    }
}
