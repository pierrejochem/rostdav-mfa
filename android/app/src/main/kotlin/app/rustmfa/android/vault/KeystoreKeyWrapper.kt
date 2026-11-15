package app.rustmfa.android.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import app.rustmfa.vault.BlobStore
import app.rustmfa.vault.KeyWrapper
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultCorruptException
import app.rustmfa.vault.VaultKeyLostException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [KeyWrapper] on an AES-256-GCM key in the Android Keystore. The key opens for 10 seconds after the user
 * authenticates with a strong biometric or the device credential. The wrapped data key is stored in [wrapped]
 * as `iv (12 bytes) + ciphertext with tag`. Key bytes are never logged or put in exception messages.
 */
class KeystoreKeyWrapper(
    @Suppress("unused") private val context: Context,
    private val wrapped: BlobStore = DataStoreBlobStore(context, "vault_key"),
    private val alias: String = "rostdav_mfa_vault_key",
) : KeyWrapper {
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    /** Generates (or replaces) the auth-bound key, in StrongBox when the device has one. */
    internal fun generateKey(): SecretKey =
        try {
            generate(spec().setIsStrongBoxBacked(true).build())
        } catch (e: StrongBoxUnavailableException) {
            generate(spec().build())
        }

    private fun spec(): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .authWindow()
            .setInvalidatedByBiometricEnrollment(false)

    /** API 30 names the allowed authenticators; before that, a positive duration allows biometric or credential. */
    private fun KeyGenParameterSpec.Builder.authWindow(): KeyGenParameterSpec.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setUserAuthenticationParameters(
                AUTH_WINDOW_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
        }

    private fun generate(spec: KeyGenParameterSpec): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }

    override suspend fun hasKey(): Boolean = withContext(Dispatchers.IO) {
        if (!keyStore().containsAlias(alias)) return@withContext false
        val stored = readWrapped() ?: return@withContext false
        stored.fill(0)
        true
    }

    override suspend fun create(dataKey: ByteArray): Unit = withContext(Dispatchers.IO) {
        val key = generateKey()
        var out: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "unexpected IV length" }
            val ciphertext = cipher.doFinal(dataKey)
            out = iv + ciphertext
            ciphertext.fill(0)
            wrapped.write(out)
        } catch (e: Exception) {
            // never leave a key without its wrapped data key
            deleteKey()
            if (e.has<UserNotAuthenticatedException>() || e.has<KeyPermanentlyInvalidatedException>()) {
                throw VaultAuthRequiredException()
            }
            throw e
        } finally {
            out?.fill(0)
        }
    }

    override suspend fun unwrap(): ByteArray = withContext(Dispatchers.IO) {
        val stored = readWrapped() ?: throw VaultKeyLostException()
        try {
            if (stored.size <= IV_BYTES + TAG_BYTES) throw VaultKeyLostException()
            try {
                // inside the mapped try: on keystore2 an invalidated key can fail here with UnrecoverableKeyException
                val key = keyStore().getKey(alias, null) as? SecretKey ?: throw VaultKeyLostException()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, stored, 0, IV_BYTES))
                cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
            } catch (e: GeneralSecurityException) {
                throw mapUnwrapFailure(e)
            } catch (e: RuntimeException) {
                throw mapUnwrapFailure(e)
            }
        } finally {
            stored.fill(0)
        }
    }

    /** The stored wrapped key; a damaged store cannot give it back, so the data key is lost. */
    private suspend fun readWrapped(): ByteArray? =
        try {
            wrapped.read()
        } catch (e: VaultCorruptException) {
            null
        }

    override suspend fun destroy(): Unit = withContext(Dispatchers.IO) {
        deleteKey()
        wrapped.clear()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val AUTH_WINDOW_SECONDS = 10
    }
}

private const val MAX_CAUSES = 16

private inline fun <reified T : Throwable> Throwable.has(): Boolean =
    generateSequence(this) { it.cause.takeIf { c -> c !== it } }.take(MAX_CAUSES).any { it is T }

/**
 * Maps a failure of `unwrap` to the vault's exceptions; anything unrecognised is returned unchanged, for the caller
 * to rethrow. The Keystore reports a closed window as [UserNotAuthenticatedException], usually thrown directly by
 * `Cipher.init`, but it can arrive as the cause of another exception, so the cause chain is searched.
 * A tag mismatch means the stored bytes were not wrapped by this key: the data key is unrecoverable, and so is a key
 * the Keystore reports as invalidated or unrecoverable.
 */
internal fun mapUnwrapFailure(e: Exception): Exception = when {
    e.has<UserNotAuthenticatedException>() -> VaultAuthRequiredException()
    e.has<KeyPermanentlyInvalidatedException>() -> VaultKeyLostException()
    e.has<javax.crypto.AEADBadTagException>() -> VaultKeyLostException()
    // keystore2 (API 31+) can report a key invalidated by removing the lock screen this way, from getKey
    e.has<UnrecoverableKeyException>() -> VaultKeyLostException()
    else -> e
}
