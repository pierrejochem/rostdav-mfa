package app.rustmfa.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.BuildConfig
import app.rustmfa.android.R
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import app.rustmfa.android.ui.design.groupCode
import app.rustmfa.android.ui.vault.AccountsSection
import app.rustmfa.android.ui.vault.BackupContent
import app.rustmfa.android.ui.vault.ConfirmAccountScreen
import app.rustmfa.android.ui.vault.confirmCode
import app.rustmfa.android.vault.AccountRow
import app.rustmfa.android.vault.BackupState
import app.rustmfa.android.vault.VaultUiState
import app.rustmfa.vault.Base32
import app.rustmfa.vault.Otp
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Renders the vault screens (and Settings, which gained the Backup row) to PNG files for `media/`. The real window has `FLAG_SECURE` while the vault is open and
 * on the add and backup screens, so these pictures are rendered from the real composables with sample data instead
 * of captured from the window. The services are made up and the secrets are public test values (the RFC 4226/6238
 * secret, the common `JBSWY3DPEHPK3PXP`, and base32 of plain ASCII strings).
 *
 * Opt-in: skipped unless the instrumentation argument `screenshots` is `true`, so the normal suite does not write
 * files. Run with `adb shell am instrument -w -e screenshots true -e class app.rustmfa.android.ui.VaultScreenshotsTest
 * app.rustmfa.android.test/androidx.test.runner.AndroidJUnitRunner` (once with `cmd uimode night yes`, once with `no`),
 * then `adb pull /sdcard/Android/data/app.rustmfa.android/files/screens`.
 */
class VaultScreenshotsTest {
    @get:Rule val rule = createComposeRule()

    @Before
    fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
    }

    private val suffix: String
        get() = if (InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.uiMode and 0x30 == 0x20) "dark" else "light"

    private val now = 1_763_208_000L // a fixed time, so the codes are the same in both themes

    private fun totp(id: String, issuer: String, label: String, secret: String, left: Int): AccountRow {
        val code = Otp.totp(OtpAccount(id, OtpType.TOTP, issuer, label, Base32.decode(secret)), now)
        return AccountRow(id, issuer, label, OtpType.TOTP, groupCode(code), left, 30, left <= 5)
    }

    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // "12345678901234567890", RFC 4226 and 6238
    private val commonSecret = "JBSWY3DPEHPK3PXP"

    private val threeRows = listOf(
        totp("a", "Example Mail", "alice@example.com", commonSecret, 22),
        totp("b", "Demo Forge", "alice", rfcSecret, 22),
        AccountRow("c", "Sample Bank", "alice", OtpType.HOTP, null, 0, 30, false),
    )

    // four rows: with the nudge, a fifth would sit under the floating bar
    private val fourRows = threeRows + totp("d", "Test Cloud", "alice@example.org", "MFRGGZDFMZTWQ2LK", 4)

    private val request = ChallengeDetails(
        challengeId = "ch_1", serviceName = "Rostdav", loginDevice = "Firefox on Linux", createdAt = "2026-11-15T12:00:00Z",
        expiresAt = "2026-11-15T12:01:30Z", status = ChallengeStatus.Pending, candidateNumbers = listOf(41, 17, 30),
        nonce = "n", loginId = "l", audience = "rostdav", approximateLocation = "Amsterdam, NL",
    )

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name-$suffix.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The Overview (or [start]) inside the real navigation, so the floating bar is in the picture. */
    private fun overview(name: String, pending: List<ChallengeDetails>, state: VaultUiState, nudge: Boolean = false, start: String? = null) {
        val noBackup = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.settings_backup_none)
        var navController: NavHostController? = null
        rule.setContent {
            val nav = rememberNavController().also { navController = it }
            RustMfaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavigation(
                        enrolled = true,
                        server = "https://rostdav.example",
                        version = BuildConfig.VERSION_NAME,
                        nav = nav,
                        backupSubtitle = { noBackup },
                        home = { _, _ ->
                            HomeScreen("https://rostdav.example", pending, false, {}, {}) {
                                AccountsSection(state, nudge, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                            }
                        },
                        requests = {},
                        onboarding = {},
                        approve = { _, _ -> },
                        pendingCount = pending.size,
                    )
                }
            }
        }
        // the graph exists only after the first composition
        if (start != null) rule.runOnIdle { navController!!.navigate(start) }
        save(name)
    }

    /** A screen without the bar, padded clear of the system bars like the app's own forms. */
    private fun plain(name: String, content: @Composable () -> Unit) {
        rule.setContent { RustMfaTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() } } } }
        save(name)
    }

    @Test fun overviewLocked() = overview("04-overview-empty", emptyList(), VaultUiState.Locked)

    @Test fun overviewWithRequest() = overview("05-overview-request", listOf(request), VaultUiState.Unlocked(threeRows))

    @Test fun accounts() = overview("11-accounts", emptyList(), VaultUiState.Unlocked(fourRows), nudge = true)

    /** Settings gained the Backup row with the vault. */
    @Test fun settings() = overview("07-settings", listOf(request), VaultUiState.Locked, start = Dest.SETTINGS)

    @Test fun confirmAccount() {
        val account = OtpAccount("p", OtpType.TOTP, "Example Mail", "alice@example.com", Base32.decode(commonSecret))
        plain("12-add-account") { ConfirmAccountScreen(account, confirmCode(account, now), { _, _ -> }, {}) }
    }

    @Test fun backup() = plain("13-backup") { BackupContent(BackupState.Idle, false, { _, _ -> }, {}, {}, {}, {}) }
}
