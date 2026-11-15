package app.rustmfa.android

import android.content.ComponentName
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class LauncherTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun launcherIconIsAdaptiveWithMonochromeLayer() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        assertTrue(icon is AdaptiveIconDrawable, "icon is ${icon::class.java.simpleName}")
        assertNotNull((icon as AdaptiveIconDrawable).monochrome)
    }

    @Test fun appDeclaresItsOwnAdaptiveLauncherIcon() {
        assertNotEquals(0, ctx.applicationInfo.icon, "the manifest declares no android:icon")
        assertTrue(ctx.resources.getResourceEntryName(ctx.applicationInfo.icon) in setOf("ic_launcher", "ic_launcher_round"))
        assertTrue(ctx.packageManager.getApplicationIcon(ctx.packageName) is AdaptiveIconDrawable)
    }

    @Test fun splashThemeIsApplied() {
        val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, "app.rustmfa.android.MainActivity"), 0)
        assertEquals("Theme.RostdavMfa.Splash", ctx.resources.getResourceEntryName(info.theme).replace('_', '.'))
    }
}
