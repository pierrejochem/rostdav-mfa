package app.rustmfa.android.vault

import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultKeyLostException
import java.security.KeyStore
import javax.crypto.SecretKeyFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Runs on a device or emulator. A key that needs user authentication can only be created when the device has a
 * secure lock screen, so [setUp] sets PIN 1234 (`adb shell locksettings set-pin 1234`) when there is none.
 * If key generation still throws `IllegalStateException`, the test fails with that message; it never skips.
 *
 * Warning: on an unsecured physical device this test permanently sets the PIN to 1234 and never removes it.
 * Setting or entering the credential opens the 10-second window, so after setting the PIN [setUp] waits 12 s;
 * the "outside the window" tests also give a false failure if the device was unlocked in the last 10 seconds.
 *
 * Nothing here authenticates the user: the credential prompt cannot be driven from a test. So these tests cover
 * only the paths outside the authentication window. That [KeystoreKeyWrapper.create] and
 * [KeystoreKeyWrapper.unwrap] succeed inside the window is not tested here.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreKeyWrapperTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val blob = DataStoreBlobStore(context, "test_vault_key")
    private val wrapper = KeystoreKeyWrapper(context, blob, ALIAS)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun deleteAlias() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    }

    @BeforeTest
    fun setUp() = runBlocking {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            // the command runs asynchronously: read its output to EOF so the PIN is committed before key generation
            val pfd = instrumentation.uiAutomation.executeShellCommand("locksettings set-pin 1234")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
            assertTrue(keyguard.isDeviceSecure, "no secure lock screen after `locksettings set-pin 1234`")
            // setting the credential counts as an authentication and opens the 10 s window; wait until it closes
            Thread.sleep(AUTH_WINDOW_MILLIS)
        }
        deleteAlias()
        blob.clear()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        deleteAlias()
        blob.clear()
    }

    @Test
    fun generatedKeyNeedsUserAuthenticationWithATenSecondWindow() {
        val key = wrapper.generateKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(KeyProperties.KEY_ALGORITHM_AES, key.algorithm)
        assertEquals(256, info.keySize)
        assertTrue(info.isUserAuthenticationRequired)
        assertFalse(info.isInvalidatedByBiometricEnrollment)
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(10, info.userAuthenticationValidityDurationSeconds)
            assertEquals(
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                info.userAuthenticationType,
            )
        }
    }

    @Test
    fun createWithoutAuthenticationThrowsAuthRequiredAndLeavesNoKey() = runBlocking {
        assertFailsWith<VaultAuthRequiredException> { wrapper.create(ByteArray(32)) }
        assertFalse(wrapper.hasKey())
        assertFalse(keyStore().containsAlias(ALIAS))
        assertNull(blob.read())
    }

    @Test
    fun unwrapWithNothingStoredThrowsKeyLost() = runBlocking {
        assertFailsWith<VaultKeyLostException> { wrapper.unwrap() }
        Unit
    }

    @Test
    fun unwrapWithStoredBytesButNoKeystoreKeyThrowsKeyLost() = runBlocking {
        blob.write(ByteArray(12 + 48) { 7 })
        assertFailsWith<VaultKeyLostException> { wrapper.unwrap() }
        Unit
    }

    @Test
    fun unwrapOutsideTheWindowThrowsAuthRequired() = runBlocking {
        wrapper.generateKey()
        blob.write(ByteArray(12 + 48) { 7 })
        assertFailsWith<VaultAuthRequiredException> { wrapper.unwrap() }
        Unit
    }

    @Test
    fun hasKeyNeedsBothTheKeystoreEntryAndTheStoredBytes() = runBlocking {
        assertFalse(wrapper.hasKey())
        blob.write(ByteArray(60) { 1 })
        assertFalse(wrapper.hasKey())
        blob.clear()
        wrapper.generateKey()
        assertFalse(wrapper.hasKey())
        blob.write(ByteArray(60) { 1 })
        assertTrue(wrapper.hasKey())
    }

    @Test
    fun destroyRemovesTheKeyAndTheStoredBytes() = runBlocking {
        wrapper.generateKey()
        blob.write(ByteArray(60) { 1 })
        wrapper.destroy()
        assertFalse(keyStore().containsAlias(ALIAS))
        assertNull(blob.read())
        assertFalse(wrapper.hasKey())
    }

    private companion object {
        const val ALIAS = "test_rostdav_mfa_vault_key"
        const val AUTH_WINDOW_MILLIS = 12_000L
    }
}
