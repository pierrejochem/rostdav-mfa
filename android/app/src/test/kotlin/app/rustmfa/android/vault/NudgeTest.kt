package app.rustmfa.android.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NudgeTest {
    @Test
    fun `no nudge without accounts`() = assertFalse(showBackupNudge(0, null, 0, 1_000))

    @Test
    fun `no nudge once a backup is recorded`() = assertFalse(showBackupNudge(3, 500, 0, 1_000))

    @Test
    fun `no nudge before hiddenUntil`() = assertFalse(showBackupNudge(3, null, 1_000, 999))

    @Test
    fun `nudge at exactly hiddenUntil`() = assertTrue(showBackupNudge(3, null, 1_000, 1_000))

    @Test
    fun `the nudge hides for seven days`() = assertEquals(604_800_000L, NUDGE_HIDE_MILLIS)
}
