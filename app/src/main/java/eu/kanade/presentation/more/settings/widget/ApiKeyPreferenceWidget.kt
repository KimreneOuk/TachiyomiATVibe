package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A settings row for entering a secret API key.
 *
 * The key is never rendered in the subtitle: a fixed "set / not set" string
 * is shown instead, which (combined with the `__PRIVATE_` preference prefix
 * used by callers) prevents key leakage into backups or shoulder-surfing.
 * Editing happens in a dialog that masks input by default with an optional
 * reveal toggle, mirroring the tracker login pattern.
 *
 * @param title      Row title, e.g. "Gemini API key".
 * @param apiKey     Current key value (read from the preference).
 * @param keySetLabel  Subtitle shown when the key is non-blank.
 * @param keyNotSetLabel Subtitle shown when the key is blank.
 * @param onApiKeyChange Called with the committed new key (may be blank).
 */
@Composable
fun ApiKeyPreferenceWidget(
    title: String,
    apiKey: String,
    keySetLabel: String,
    keyNotSetLabel: String,
    onApiKeyChange: (String) -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = if (apiKey.isNotBlank()) keySetLabel else keyNotSetLabel,
        icon = null,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        ApiKeyEditDialog(
            title = title,
            initialKey = apiKey,
            onDismissRequest = { isDialogShown = false },
            onConfirm = { newKey ->
                onApiKeyChange(newKey)
                isDialogShown = false
            },
        )
    }
}

@Composable
private fun ApiKeyEditDialog(
    title: String,
    initialKey: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(initialKey) }
    var hide by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(text = title) },
                    trailingIcon = {
                        IconButton(onClick = { hide = !hide }) {
                            Icon(
                                imageVector = if (hide) {
                                    Icons.Filled.Visibility
                                } else {
                                    Icons.Filled.VisibilityOff
                                },
                                contentDescription = null,
                            )
                        }
                    },
                    visualTransformation = if (hide) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    singleLine = true,
                )
                Text(
                    text = stringResource(ATMR.strings.pref_sub_engine_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onConfirm(draft.trim()) },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
