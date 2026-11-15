package app.rustmfa.android.ui.vault

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.rustmfa.android.R
import app.rustmfa.vault.Base32
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpAlgorithm
import app.rustmfa.vault.OtpType

/** The result of [ManualAccountForm.validate]. */
sealed interface FormResult {
    data class Valid(val account: OtpAccount) : FormResult

    /** One string resource per field that is wrong. */
    data class Invalid(val errors: Map<ManualAccountForm.Field, Int>) : FormResult
}

/**
 * The fields of the "Enter by hand" form. Plain Kotlin over snapshot state, so a screen recomposes on a change and
 * a JVM test can drive it. The secret is text (a text field needs a String), so it cannot be zeroed: [clear] drops it.
 * The account [validate] returns carries a placeholder id; the session assigns the real one on import.
 */
class ManualAccountForm {
    enum class Field { Secret, Name, Period, Counter }

    var issuer by mutableStateOf("")
    var label by mutableStateOf("")
    var secret by mutableStateOf("")
    var type by mutableStateOf(OtpType.TOTP)
    var algorithm by mutableStateOf(OtpAlgorithm.SHA1)
    var digits by mutableIntStateOf(6)
    var period by mutableStateOf("30")
    var counter by mutableStateOf("0")

    fun validate(): FormResult {
        val errors = linkedMapOf<Field, Int>()
        val bytes = try {
            Base32.decode(secret.trim())
        } catch (e: IllegalArgumentException) {
            null
        }
        if (bytes == null || bytes.isEmpty()) errors[Field.Secret] = R.string.vault_error_secret
        if (issuer.isBlank() && label.isBlank()) errors[Field.Name] = R.string.vault_error_name
        val periodValue = period.trim().toIntOrNull()?.takeIf { it in 1..3600 }
        if (type == OtpType.TOTP && periodValue == null) errors[Field.Period] = R.string.vault_error_period
        val counterValue = counter.trim().toLongOrNull()?.takeIf { it >= 0 }
        if (type == OtpType.HOTP && counterValue == null) errors[Field.Counter] = R.string.vault_error_counter
        if (errors.isNotEmpty()) {
            bytes?.fill(0)
            return FormResult.Invalid(errors)
        }
        return FormResult.Valid(
            OtpAccount(
                id = PLACEHOLDER_ID,
                type = type,
                issuer = issuer.trim(),
                label = label.trim(),
                secret = bytes!!,
                algorithm = algorithm,
                digits = digits,
                period = if (type == OtpType.TOTP) periodValue!! else 30,
                counter = if (type == OtpType.HOTP) counterValue!! else 0,
            ),
        )
    }

    /** Back to an empty form; the secret text is dropped. */
    fun clear() {
        issuer = ""
        label = ""
        secret = ""
        type = OtpType.TOTP
        algorithm = OtpAlgorithm.SHA1
        digits = 6
        period = "30"
        counter = "0"
    }

    companion object {
        /** VaultSession.import gives every added account a new id. */
        const val PLACEHOLDER_ID = "new"
    }
}
