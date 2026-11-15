package app.rustmfa.android.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class DesignLogicTest {
    @Test fun glassIsSolidBelowApi30() {
        assertEquals(GlassStyle.Solid, glassStyle(28, animationsReduced = false))
        assertEquals(GlassStyle.Solid, glassStyle(29, animationsReduced = false))
    }

    @Test fun glassIsTranslucentFromApi30() {
        assertEquals(GlassStyle.Translucent, glassStyle(30, animationsReduced = false))
        assertEquals(GlassStyle.Translucent, glassStyle(35, animationsReduced = false))
    }

    @Test fun glassIsSolidWhenAnimationsAreReduced() {
        assertEquals(GlassStyle.Solid, glassStyle(35, animationsReduced = true))
    }

    @Test fun ringIsUrgentInTheLastFiveSeconds() {
        assertEquals(RingState.Urgent, ringColor(5, 30))
        assertEquals(RingState.Urgent, ringColor(0, 30))
        assertEquals(RingState.Normal, ringColor(6, 30))
    }

    @Test fun ringProgressIsTheFractionLeftAndClamped() {
        assertEquals(0.5f, ringProgress(15, 30))
        assertEquals(0f, ringProgress(-3, 30))
        assertEquals(1f, ringProgress(40, 30))
        assertEquals(0f, ringProgress(10, 0))
    }

    @Test fun initialIsTheUppercasedFirstLetterOrAQuestionMark() {
        assertEquals("G", initialOf("github"))
        assertEquals("P", initialOf("  pierre"))
        assertEquals("?", initialOf(""))
        assertEquals("?", initialOf("   "))
    }

    @Test fun codesAreGroupedInHalves() {
        assertEquals("482 731", groupCode("482731"))
        assertEquals("4821 7316", groupCode("48217316"))
        assertEquals("12345", groupCode("12345"))
    }
}
