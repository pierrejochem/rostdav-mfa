package app.rustmfa.android.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FloatingBarLogicTest {
    @Test fun noBadgeWhenNothingWaits() {
        assertNull(badgeText(0))
        assertNull(badgeText(-3))
    }

    @Test fun theBadgeShowsTheCount() {
        assertEquals("1", badgeText(1))
        assertEquals("99", badgeText(99))
    }

    @Test fun theBadgeCapsAtNinetyNine() {
        assertEquals("99+", badgeText(100))
        assertEquals("99+", badgeText(5000))
    }
}
