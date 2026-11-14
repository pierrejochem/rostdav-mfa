package app.rustmfa.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R

/** The whole onboarding flow, driven by [OnboardingViewModel]. [defaultName] is the phone's model. */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, defaultName: String, onDone: () -> Unit) {
    val state by vm.state.collectAsState()
    var manual by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) { if (state is OnboardingState.Done) onDone() }
    when (val s = state) {
        OnboardingState.Idle, OnboardingState.Done ->
            if (manual) {
                ManualEnrollScreen(onContinue = vm::onManual, onBack = { manual = false })
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.onboarding_text))
                    Button(onClick = vm::startScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_button)) }
                    OutlinedButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.manual_button))
                    }
                }
            }
        OnboardingState.Scanning ->
            ScanScreen(onText = vm::onLink, onManual = { manual = true; vm.reset() }, onBack = vm::reset)
        is OnboardingState.NeedsName -> {
            var name by rememberSaveable { mutableStateOf(defaultName) }
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.name_title), style = MaterialTheme.typography.headlineMedium)
                Text(s.link.server.host)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.enroll(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.name_connect))
                }
                OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
            }
        }
        OnboardingState.Enrolling ->
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.enrolling))
            }
        is OnboardingState.Failed ->
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(failureMessage(s)), color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::reset) { Text(stringResource(R.string.try_again)) }
            }
    }
}
