package app.rustmfa.android.ui.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.vault.OtpAccount
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class ManualAccountScreenTest {
    @get:Rule val rule = createComposeRule()

    private val added = mutableListOf<OtpAccount>()
    private val form = ManualAccountForm()
    private val secret = "JBSWY3DPEHPK3PXP"

    private fun show(fontScale: Float = 1f, narrow: Boolean = false, message: String? = null) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale)) {
                RustMfaTheme {
                    Box(if (narrow) Modifier.width(360.dp) else Modifier) {
                        ManualAccountScreen(form, onAdd = { added += it }, onBack = {}, message = message)
                    }
                }
            }
        }
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    @Test fun addIsDisabledUntilTheSecretIsValidAndANameIsSet() {
        show()
        rule.onNodeWithText("Add").performScrollTo().assertIsNotEnabled()
        field("Secret key").performTextInput(secret)
        rule.onNodeWithText("Add").performScrollTo().assertIsNotEnabled()
        field("Service").performTextInput("Example")
        rule.onNodeWithText("Add").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, added.size)
        assertEquals("Example", added[0].issuer)
    }

    /** What the field draws (the input itself stays in InputText). */
    private fun drawn(text: String) = SemanticsMatcher("draws '$text'") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == text }

    @Test fun theSecretIsHiddenByDefaultAndTheToggleShowsIt() {
        show()
        field("Secret key").performTextInput(secret)
        field("Secret key").assert(drawn("\u2022".repeat(secret.length)))
        rule.onNodeWithContentDescription("Show secret key").performClick()
        field("Secret key").assert(drawn(secret))
        rule.onNodeWithContentDescription("Hide secret key").performClick()
        field("Secret key").assert(drawn("\u2022".repeat(secret.length)))
    }

    @Test fun theFormIsLeftOutOfAutofill() {
        show()
        for (label in listOf("Secret key", "Service", "Account name")) {
            field(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDataType, ContentDataType.None))
        }
    }

    @Test fun theAdvancedSectionIsCollapsedByDefaultAndExpands() {
        show()
        rule.onNodeWithText("Algorithm").assertDoesNotExist()
        rule.onNodeWithText("Advanced").performScrollTo().performClick()
        rule.onNodeWithText("Algorithm").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Digits").performScrollTo().assertIsDisplayed()
        field("Period (seconds)").performScrollTo().assertIsDisplayed()
    }

    @Test fun aBadSecretShowsTheInlineError() {
        show()
        rule.onNodeWithText("The secret key is not valid.").assertDoesNotExist()
        field("Secret key").performTextInput("!!")
        rule.onNodeWithText("The secret key is not valid.").assertIsDisplayed()
    }

    @Test fun aHotpFormAsksForAStartingCounter() {
        show()
        rule.onNodeWithText("HOTP").performClick()
        rule.onNodeWithText("Advanced").performScrollTo().performClick()
        field("Starting counter").performScrollTo().assertIsDisplayed()
        field("Period (seconds)").assertDoesNotExist()
    }

    @Test fun addIsReachableAtDoubleFontScaleAt360dp() {
        show(fontScale = 2f, narrow = true)
        field("Secret key").performScrollTo().performTextInput(secret)
        field("Account name").performScrollTo().performTextInput("alice")
        rule.onNodeWithText("Add").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, added.size)
    }

    @Test fun aDuplicateMessageShows() {
        show(message = "This account is already in the vault")
        rule.onNodeWithText("This account is already in the vault").assertIsDisplayed()
    }
}
