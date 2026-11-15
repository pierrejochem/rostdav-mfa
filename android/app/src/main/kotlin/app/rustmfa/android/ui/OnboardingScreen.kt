package app.rustmfa.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.BrandMark
import app.rustmfa.android.ui.design.BrandWordmark
import app.rustmfa.android.ui.design.ButtonKind
import app.rustmfa.android.ui.design.LightDarkPreview
import app.rustmfa.android.ui.design.StatusBanner
import app.rustmfa.android.ui.design.brandWash

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
                OnboardingStart(onScan = vm::startScan, onManual = { manual = true })
            }
        OnboardingState.Scanning ->
            ScanScreen(onText = vm::onLink, onManual = { manual = true; vm.reset() }, onBack = vm::reset)
        is OnboardingState.NeedsName -> {
            var name by rememberSaveable { mutableStateOf(defaultName) }
            Column(Modifier.fillMaxSize().brandWash().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark(size = 56.dp)
                Text(stringResource(R.string.name_title), style = MaterialTheme.typography.headlineMedium)
                Text(s.link.server.host)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                BrandButton(stringResource(R.string.name_connect), onClick = { vm.enroll(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth())
                BrandButton(stringResource(R.string.back), onClick = vm::reset, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
            }
        }
        OnboardingState.Enrolling ->
            Column(Modifier.fillMaxSize().brandWash().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.enrolling))
            }
        is OnboardingState.Failed ->
            OnboardingFailed(stringResource(failureMessage(s)), onRetry = vm::reset)
    }
}

/** What the user sees when connecting failed: the reason and a way to try again. Scrolls at any font size. */
@Composable
fun OnboardingFailed(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().brandWash().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusBanner(BannerKind.Error, message)
        BrandButton(stringResource(R.string.try_again), onClick = onRetry, modifier = Modifier.fillMaxWidth())
    }
}

/** The first screen: mark, wordmark, one sentence, and the two ways to connect. */
@Composable
fun OnboardingStart(onScan: () -> Unit, onManual: () -> Unit) {
    Column(
        Modifier.fillMaxSize().brandWash().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 96.dp)
        BrandWordmark(size = 32.sp)
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.onboarding_text), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BrandButton(stringResource(R.string.scan_button), onClick = onScan, modifier = Modifier.fillMaxWidth())
        BrandButton(stringResource(R.string.manual_button), onClick = onManual, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
    }
}

@LightDarkPreview
@Composable
private fun OnboardingStartPreview() = RustMfaTheme { OnboardingStart({}, {}) }
