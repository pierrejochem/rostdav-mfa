package app.rustmfa.android.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.rustmfa.android.R
import app.rustmfa.android.crypto.ApprovalSigner
import app.rustmfa.android.domain.AuthCancelledException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Shows the biometric prompt with the approval key's crypto object and returns the raw signature
 * of [message]. A cancel throws [AuthCancelledException]; the key is only usable through this prompt.
 */
suspend fun authenticate(activity: FragmentActivity, signer: ApprovalSigner, message: ByteArray): ByteArray =
    suspendCancellableCoroutine { cont ->
        val prompt =
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            cont.resume(signer.finish(checkNotNull(result.cryptoObject), message))
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val cancelled =
                            errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                                errorCode == BiometricPrompt.ERROR_CANCELED
                        cont.resumeWithException(if (cancelled) AuthCancelledException() else IllegalStateException("biometric error $errorCode"))
                    }
                },
            )
        val info =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.biometric_title))
                .setNegativeButtonText(activity.getString(R.string.biometric_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
        try {
            prompt.authenticate(info, signer.cryptoObject())
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
