package app.rustmfa.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rustmfa.android.crypto.ApprovalSigner
import app.rustmfa.android.domain.ChallengeFlow
import app.rustmfa.android.domain.ChallengeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

typealias Authenticator = suspend (ApprovalSigner, ByteArray) -> ByteArray

class ApproveViewModel(private val flow: ChallengeFlow, private val id: String) : ViewModel() {
    val state: StateFlow<ChallengeState> = flow.state

    init {
        viewModelScope.launch {
            flow.load(id)
            flow.countdown()
        }
    }

    fun approve(selected: Int, auth: Authenticator) {
        viewModelScope.launch {
            flow.approve(selected, auth)
            // an answer that was cancelled leaves the request open: keep counting down
            flow.countdown()
        }
    }

    fun deny(auth: Authenticator) {
        viewModelScope.launch {
            flow.deny(auth)
            flow.countdown()
        }
    }
}
