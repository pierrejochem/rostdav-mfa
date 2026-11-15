package app.rustmfa.android.vault

import app.rustmfa.vault.VaultKeyLostException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

/** The cases that need no Android class; the Android exception types are covered in the instrumented tests. */
class UnwrapFailureMappingTest {
    private fun unrecoverable() = UnrecoverableKeyException("key invalidated").apply { initCause(KeyStoreException()) }

    @Test
    fun `an unrecoverable key is lost`() {
        assertIs<VaultKeyLostException>(mapUnwrapFailure(unrecoverable()))
    }

    @Test
    fun `an unrecoverable key wrapped in another exception is lost`() {
        assertIs<VaultKeyLostException>(mapUnwrapFailure(RuntimeException(unrecoverable())))
    }

    @Test
    fun `an unrelated exception is returned unchanged`() {
        val e = IllegalStateException("other")
        assertSame(e, mapUnwrapFailure(e))
    }
}
