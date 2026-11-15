package app.rustmfa.android.domain

/**
 * Decides when the app restarts after the server removed this phone. While the Approve screen is
 * open the restart waits, so the user reads "This phone was removed on the server." and taps Done
 * instead of seeing the screen vanish. Used from the main thread only.
 */
class SessionEndGate {
    enum class Action { RestartNow, Hold }

    private var approveOpen = false
    private var ended = false

    fun approveOpened() { approveOpen = true }

    /** Returns `true` when the session ended meanwhile and the app must restart now. */
    fun approveClosed(): Boolean {
        approveOpen = false
        val restart = ended
        ended = false
        return restart
    }

    fun onSessionEnded(): Action {
        if (!approveOpen) return Action.RestartNow
        ended = true
        return Action.Hold
    }
}
