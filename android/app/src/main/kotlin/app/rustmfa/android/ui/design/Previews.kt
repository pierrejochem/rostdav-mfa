package app.rustmfa.android.ui.design

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** Light and dark previews side by side. Used by every component file. */
@Preview(name = "light", showBackground = true)
@Preview(name = "dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class LightDarkPreview
