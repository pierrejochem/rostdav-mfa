package app.rustmfa.android.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.ButtonKind
import app.rustmfa.android.ui.design.LightDarkPreview
import app.rustmfa.android.ui.design.StatusBanner
import app.rustmfa.android.ui.vault.ManualAccountForm.Field
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpAlgorithm
import app.rustmfa.vault.OtpType

/**
 * "Enter by hand": Service, Account name, the secret (dots with a show toggle; no suggestions, no autofill: password
 * keyboard, autocorrect off, no content type set, and ContentDataType.None), TOTP or HOTP, and a collapsed Advanced section. Add is on only while
 * [form] is valid. An error shows once its field was touched. [message] is an error banner (a duplicate).
 */
@Composable
fun ManualAccountScreen(form: ManualAccountForm, onAdd: (OtpAccount) -> Unit, onBack: () -> Unit, message: String? = null) {
    // the typed secret is on this screen: it holds FLAG_SECURE itself, also on a locked vault (after a lock or a
    // process restart the root holder has let go)
    SecureWhile(true)
    // not saveable on purpose: after a recreation the secret is dots again; leaving the app hides it too
    var showSecret by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { showSecret = false }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var nameTouched by rememberSaveable { mutableStateOf(false) }
    val result = form.validate()
    val errors = (result as? FormResult.Invalid)?.errors.orEmpty()
    val valid = result is FormResult.Valid
    // validate() decodes the secret; only the yes/no is needed here
    (result as? FormResult.Valid)?.account?.secret?.fill(0)

    // KeyboardType.Password makes Compose label the field a password for autofill (a password manager would offer
    // to save the secret); ContentDataType.None takes the fields out of autofill. Outer semantics win on one node.
    val noAutofill = Modifier.fillMaxWidth().semantics { contentDataType = ContentDataType.None }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.manual_button), style = MaterialTheme.typography.headlineMedium)
        if (message != null) StatusBanner(BannerKind.Error, message)
        OutlinedTextField(
            form.issuer,
            {
                form.issuer = it
                nameTouched = true
            },
            noAutofill,
            label = { Text(stringResource(R.string.vault_field_service)) },
            singleLine = true,
        )
        val nameError = errors[Field.Name]?.takeIf { nameTouched || form.secret.isNotEmpty() }
        OutlinedTextField(
            form.label,
            {
                form.label = it
                nameTouched = true
            },
            noAutofill,
            label = { Text(stringResource(R.string.vault_field_account)) },
            singleLine = true,
            isError = nameError != null,
            supportingText = nameError?.let { { Text(stringResource(it)) } },
        )
        val secretError = errors[Field.Secret]?.takeIf { form.secret.isNotEmpty() }
        OutlinedTextField(
            form.secret,
            { form.secret = it },
            noAutofill,
            label = { Text(stringResource(R.string.vault_field_secret)) },
            singleLine = true,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(
                        painterResource(if (showSecret) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = stringResource(if (showSecret) R.string.vault_secret_hide else R.string.vault_secret_show),
                    )
                }
            },
            isError = secretError != null,
            supportingText = secretError?.let { { Text(stringResource(it)) } },
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OtpType.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = form.type == t,
                    onClick = { form.type = t },
                    shape = SegmentedButtonDefaults.itemShape(i, OtpType.entries.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(t.name) }
            }
        }
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.vault_advanced))
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(if (advanced) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(18.dp))
        }
        if (advanced) {
            Advanced(form, errors)
        }
        BrandButton(
            stringResource(R.string.vault_add_button),
            onClick = { (form.validate() as? FormResult.Valid)?.let { onAdd(it.account) } },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        )
        BrandButton(stringResource(R.string.back), onClick = onBack, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Advanced(form: ManualAccountForm, errors: Map<Field, Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.vault_field_algorithm), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in OtpAlgorithm.entries) {
                FilterChip(selected = form.algorithm == a, onClick = { form.algorithm = a }, label = { Text(a.uriName) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Text(stringResource(R.string.vault_field_digits), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in listOf(6, 8)) {
                FilterChip(selected = form.digits == d, onClick = { form.digits = d }, label = { Text(d.toString()) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        val numbers = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Number)
        if (form.type == OtpType.TOTP) {
            val error = errors[Field.Period]
            OutlinedTextField(
                form.period,
                { form.period = it },
                Modifier.fillMaxWidth().padding(top = 4.dp),
                label = { Text(stringResource(R.string.vault_field_period)) },
                singleLine = true,
                keyboardOptions = numbers,
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(it)) } },
            )
        } else {
            val error = errors[Field.Counter]
            OutlinedTextField(
                form.counter,
                { form.counter = it },
                Modifier.fillMaxWidth().padding(top = 4.dp),
                label = { Text(stringResource(R.string.vault_field_counter)) },
                singleLine = true,
                keyboardOptions = numbers,
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(it)) } },
            )
        }
    }
}

@LightDarkPreview
@Composable
private fun ManualPreview() = RustMfaTheme { ManualAccountScreen(ManualAccountForm(), {}, {}) }
