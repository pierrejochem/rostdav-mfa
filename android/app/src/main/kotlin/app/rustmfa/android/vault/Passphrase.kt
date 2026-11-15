package app.rustmfa.android.vault

import app.rustmfa.vault.VaultBackup
import java.nio.CharBuffer
import java.text.Normalizer

enum class PassphraseProblem { TooShort, Mismatch }

/**
 * The backup form's check, matching what [VaultBackup] does: length in code points after NFKC, and the two
 * entries compared in that normalised form.
 */
fun passphraseProblem(passphrase: CharArray, repeat: CharArray): PassphraseProblem? {
    val first = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFKC)
    if (first.codePointCount(0, first.length) < VaultBackup.MIN_PASSPHRASE) return PassphraseProblem.TooShort
    val second = Normalizer.normalize(CharBuffer.wrap(repeat), Normalizer.Form.NFKC)
    return if (first == second) null else PassphraseProblem.Mismatch
}
