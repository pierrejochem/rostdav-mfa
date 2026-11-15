package app.rustmfa.android.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionEndGateTest {
    private val gate = SessionEndGate()

    @Test fun restartsAtOnceWhenNoApproveScreenIsOpen() {
        assertEquals(SessionEndGate.Action.RestartNow, gate.onSessionEnded())
    }

    @Test fun holdsWhileTheApproveScreenIsOpen() {
        gate.approveOpened()
        assertEquals(SessionEndGate.Action.Hold, gate.onSessionEnded())
    }

    @Test fun restartsWhenTheApproveScreenClosesAfterTheEnd() {
        gate.approveOpened()
        gate.onSessionEnded()
        assertTrue(gate.approveClosed())
    }

    @Test fun doesNotRestartWhenTheApproveScreenClosesNormally() {
        gate.approveOpened()
        assertFalse(gate.approveClosed())
    }

    @Test fun anEndedSessionKeepsRestartingOnLaterEnds() {
        gate.approveOpened()
        gate.onSessionEnded()
        gate.approveClosed()
        assertEquals(SessionEndGate.Action.RestartNow, gate.onSessionEnded())
    }

    @Test fun reportsTheRestartOnlyOnce() {
        gate.approveOpened()
        gate.onSessionEnded()
        assertTrue(gate.approveClosed())
        gate.approveOpened()
        assertFalse(gate.approveClosed())
    }
}
