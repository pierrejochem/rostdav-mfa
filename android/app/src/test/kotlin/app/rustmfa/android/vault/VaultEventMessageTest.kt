package app.rustmfa.android.vault

import app.rustmfa.android.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultEventMessageTest {
    @Test fun unlockToSaveHasAMessage() = assertEquals(R.string.vault_unlock_to_save, VaultEvent.UnlockToSave.toastMessage())

    @Test fun failedUsesItsOwnMessage() = assertEquals(R.string.vault_error_generic, VaultEvent.Failed(R.string.vault_error_generic).toastMessage())

    @Test fun theQuietEventsHaveNone() {
        assertNull(VaultEvent.Added.toastMessage())
        assertNull(VaultEvent.Duplicate.toastMessage())
        assertNull(VaultEvent.Copied.toastMessage())
    }
}
