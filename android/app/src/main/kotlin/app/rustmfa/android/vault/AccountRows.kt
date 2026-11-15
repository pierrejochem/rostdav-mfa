package app.rustmfa.android.vault

import app.rustmfa.android.ui.design.groupCode
import app.rustmfa.android.ui.design.isUrgent
import app.rustmfa.vault.Otp
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** [code] is the grouped code text, or null for a HOTP row whose code is not shown. HOTP rows have no ring. */
data class AccountRow(
    val id: String,
    val issuer: String,
    val label: String,
    val type: OtpType,
    val code: String?,
    val secondsLeft: Int,
    val period: Int,
    val urgent: Boolean,
)

/** [code] gives the TOTP code of an account at [epochSeconds]; it is never asked for a HOTP account. */
fun buildRows(
    accounts: List<OtpAccount>,
    epochSeconds: Long,
    hotpCodes: Map<String, String>,
    code: (OtpAccount) -> String,
): List<AccountRow> = accounts.map { a ->
    when (a.type) {
        OtpType.TOTP -> {
            val left = Otp.secondsLeft(a.period, epochSeconds)
            AccountRow(a.id, a.issuer, a.label, a.type, groupCode(code(a)), left, a.period, isUrgent(left.toLong()))
        }
        OtpType.HOTP -> AccountRow(a.id, a.issuer, a.label, a.type, hotpCodes[a.id]?.let(::groupCode), 0, a.period, false)
    }
}

/** The epoch second now, then one value at every following second boundary of [clock] (milliseconds). */
fun secondTicker(clock: () -> Long): Flow<Long> = flow {
    while (true) {
        emit(clock() / 1000)
        delay(1000 - clock() % 1000)
    }
}
