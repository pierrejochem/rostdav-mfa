package app.rustmfa.android.vault

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.rustmfa.android.R
import app.rustmfa.vault.VaultAuthRequiredException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Shows a strong-biometric-or-device-credential prompt without a crypto object: the successful authentication
 * opens the Keystore window of the vault key, which the key wrapper then uses. Cancel, lockout and any other
 * error throw [VaultAuthRequiredException]. Needs a real prompt, so it has no unit test.
 */
class BiometricUnlocker(private val activity: FragmentActivity) {
    suspend fun authenticate() {
        suspendCancellableCoroutine { cont ->
            val prompt =
                BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resumeWithException(VaultAuthRequiredException())
                        }
                    },
                )
            val info =
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.biometric_title))
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            setDeviceCredentialAllowed(true)
                        }
                    }
                    .build()
            try {
                prompt.authenticate(info)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}
