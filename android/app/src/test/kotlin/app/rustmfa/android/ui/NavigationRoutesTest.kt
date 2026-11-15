package app.rustmfa.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationRoutesTest {
    @Test fun theTabsAreOverviewRequestsAndSettings() {
        assertEquals(listOf("home", "requests", "settings"), TabRoutes)
    }

    @Test fun devicesIsReachedFromSettingsAndIsNotATab() {
        assertFalse("devices" in TabRoutes)
        assertTrue(Dest.DEVICES == "devices")
    }
}
