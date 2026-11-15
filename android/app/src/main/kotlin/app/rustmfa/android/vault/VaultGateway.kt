package app.rustmfa.android.vault

import app.rustmfa.vault.ImportResult
import app.rustmfa.vault.OtpAccount

/** What the vault UI needs from the vault; [VaultRepository] implements it, tests use a fake. */
interface VaultGateway {
    val isUnlocked: Boolean

    suspend fun hasVault(): Boolean

    suspend fun unlock()

    fun lock()

    suspend fun reset()

    fun appLeft()

    fun appReturned()

    fun accounts(): List<OtpAccount>

    fun code(id: String, epochSeconds: Long): String

    suspend fun nextHotp(id: String): String

    suspend fun import(accounts: List<OtpAccount>): ImportResult

    suspend fun update(account: OtpAccount)

    suspend fun remove(id: String)
}
