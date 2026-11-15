package app.rustmfa.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme

private const val MAX_BAR_FONT_SCALE = 1.3f

/** One tab of the floating bar. [badge] is the number of open items, shown as a pill when above zero. */
data class FloatingTab(val route: String, val label: String, val icon: Int, val badge: Int? = null)

/** "2", up to "99", then "99+"; nothing for zero or less. */
fun badgeText(count: Int): String? =
    when {
        count <= 0 -> null
        count > 99 -> "99+"
        else -> count.toString()
    }

/** Space a screen keeps free at its bottom so the last item can scroll above the floating bar. */
val BarClearance: Dp = 112.dp

/** The clearance in effect: [BarClearance] while the bar is shown, zero on screens without it. */
val LocalBarClearance = compositionLocalOf { 0.dp }

/** The standard screen padding, plus room for the floating bar. */
@Composable
fun Modifier.screenPadding(): Modifier = padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 24.dp + LocalBarClearance.current)

/**
 * The floating pill bar from the design: 76 dp high, at most 334 dp wide, translucent glass (solid
 * below API 30), the selected tab in the rust gradient, and a count badge on a tab that has one.
 */
@Composable
fun FloatingTabBar(tabs: List<FloatingTab>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(38.dp)
    val fill: Brush =
        if (rememberGlassStyle() == GlassStyle.Translucent) {
            if (dark) Brush.verticalGradient(listOf(Color(0x9E262830), Color(0xBD15171C))) else Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0x9EFFFFFF)))
        } else {
            SolidColor(scheme.surface)
        }
    val edge = if (dark) Color(0x1FFFFFFF) else Color(0xE6FFFFFF)
    val shadowColor = if (dark) Color(0x80000000) else Color(0x2E7A2A12)
    // the bar has a fixed height: its labels grow with the system font size, but only up to 1.3 times
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = density.fontScale.coerceAtMost(MAX_BAR_FONT_SCALE))) {
    Row(
        modifier
            .testTag("floating-tab-bar")
            .widthIn(max = 334.dp)
            .fillMaxWidth()
            .height(76.dp)
            .shadow(18.dp, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .padding(6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (t in tabs) Tab(t, t.route == selected, dark, Modifier.weight(1f), onClick = { onSelect(t.route) })
    }
    }
}

@Composable
private fun Tab(tab: FloatingTab, selected: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    val content = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val look =
        if (selected) {
            Modifier
                .shadow(8.dp, shape, ambientColor = Color(0x80C2491D), spotColor = Color(0x80C2491D))
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Tokens.tabStart, Tokens.tabEnd)))
                .border(1.dp, Color(0x38FFFFFF), shape)
        } else {
            Modifier.clip(shape)
        }
    Column(
        modifier.fillMaxHeight().testTag("tab-" + tab.route).then(look).selectable(selected, role = Role.Tab, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(painterResource(tab.icon), contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            val text = tab.badge?.let { badgeText(it) }
            if (text != null) Badge(text, tab.badge, if (dark) Color(0xFF1F2128) else Color(0xFFF7F2EC), Modifier.offset(x = 14.dp, y = (-8).dp))
        }
        Text(
            tab.label,
            color = content,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 13.sp, stepSize = 0.5.sp),
        )
    }
}

@Composable
private fun Badge(text: String, count: Int, ring: Color, modifier: Modifier) {
    val description = pluralStringResource(R.plurals.badge_waiting, count, count)
    Box(modifier.background(ring, RoundedCornerShape(11.dp)).padding(2.dp).semantics { contentDescription = description }) {
        Box(
            Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 18.dp).background(Tokens.Dark.rost600, RoundedCornerShape(9.dp)).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = Color(0xFF15171C), style = CodeStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@LightDarkPreview
@Composable
private fun BarPreview() =
    RustMfaTheme {
        Box(Modifier.padding(16.dp)) {
            FloatingTabBar(
                listOf(
                    FloatingTab("home", "Overview", R.drawable.ic_nav_home),
                    FloatingTab("requests", "Requests", R.drawable.ic_nav_list, badge = 2),
                    FloatingTab("settings", "Settings", R.drawable.ic_nav_settings),
                ),
                "home", {},
            )
        }
    }
