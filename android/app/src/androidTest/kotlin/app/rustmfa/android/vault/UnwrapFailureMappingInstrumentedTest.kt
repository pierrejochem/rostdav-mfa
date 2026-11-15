package app.rustmfa.android.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultKeyLostException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.runner.RunWith

/** The unwrap failure mapping with the Android exception types, which a JVM unit test cannot construct. */
@RunWith(AndroidJUnit4::class)
class UnwrapFailureMappingInstrumentedTest {
    @Test
    fun userNotAuthenticatedIsAuthRequired() {
        assertIs<VaultAuthRequiredException>(mapUnwrapFailure(UserNotAuthenticatedException()))
        assertIs<VaultAuthRequiredException>(mapUnwrapFailure(RuntimeException(UserNotAuthenticatedException())))
    }

    @Test
    fun permanentlyInvalidatedIsKeyLost() {
        assertIs<VaultKeyLostException>(mapUnwrapFailure(KeyPermanentlyInvalidatedException()))
    }

    @Test
    fun unrecoverableKeyIsKeyLost() {
        val e = UnrecoverableKeyException("invalidated").apply { initCause(KeyStoreException()) }
        assertIs<VaultKeyLostException>(mapUnwrapFailure(e))
        assertIs<VaultKeyLostException>(mapUnwrapFailure(IllegalStateException(e)))
    }

    @Test
    fun unrelatedExceptionIsReturnedUnchanged() {
        val e = IllegalStateException("other")
        assertSame(e, mapUnwrapFailure(e))
    }
}
