package app.rustmfa.android.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.rustmfa.android.R

val InterFamily: FontFamily =
    FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )

/** One-time codes and the number-match digits: tabular figures so the digits do not jump. */
val MonoFamily: FontFamily = FontFamily(Font(R.font.jetbrains_mono_medium, FontWeight.Medium))

val CodeStyle: TextStyle =
    TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = Tokens.sizeHeadline, fontFeatureSettings = "tnum")

/** Inter everywhere; the scale is 28, 20, 16, 14, 12 from the tokens. */
fun rostdavTypography(): Typography {
    val d = Typography()
    fun TextStyle.inter(weight: FontWeight = FontWeight.Normal) = copy(fontFamily = InterFamily, fontWeight = weight)
    return Typography(
        displayLarge = d.displayLarge.inter(),
        displayMedium = d.displayMedium.inter(),
        displaySmall = d.displaySmall.inter(),
        headlineLarge = d.headlineLarge.inter(FontWeight.SemiBold).copy(fontSize = Tokens.sizeHeadline),
        headlineMedium = d.headlineMedium.inter(FontWeight.SemiBold).copy(fontSize = Tokens.sizeHeadline),
        headlineSmall = d.headlineSmall.inter(FontWeight.SemiBold).copy(fontSize = Tokens.sizeTitle),
        titleLarge = d.titleLarge.inter(FontWeight.SemiBold).copy(fontSize = Tokens.sizeTitle),
        titleMedium = d.titleMedium.inter(FontWeight.SemiBold).copy(fontSize = Tokens.sizeTitle),
        titleSmall = d.titleSmall.inter(FontWeight.Medium).copy(fontSize = Tokens.sizeBody),
        bodyLarge = d.bodyLarge.inter().copy(fontSize = Tokens.sizeBody),
        bodyMedium = d.bodyMedium.inter().copy(fontSize = Tokens.sizeLabel),
        bodySmall = d.bodySmall.inter().copy(fontSize = Tokens.sizeCaption),
        labelLarge = d.labelLarge.inter(FontWeight.Medium).copy(fontSize = Tokens.sizeLabel),
        labelMedium = d.labelMedium.inter(FontWeight.Medium).copy(fontSize = Tokens.sizeCaption),
        labelSmall = d.labelSmall.inter(FontWeight.Medium).copy(fontSize = Tokens.sizeCaption),
    )
}
