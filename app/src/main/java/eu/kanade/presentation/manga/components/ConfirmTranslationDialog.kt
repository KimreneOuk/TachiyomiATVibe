package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.translation.model.TranslationSettingsSummary
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Read-only confirmation popup shown before a manga-screen batch "Translate
 * chapter" runs. Displays the active translation configuration so the user can
 * verify the source/target language, engine/model, OCR model, and output token
 * budget, then either proceed (Translate) or open the Translation settings to
 * change something. The "Don't show this again" checkbox toggles the
 * `translationConfirmPretranslate` preference immediately.
 *
 * Mirrors the [DeleteChaptersDialog] skeleton (AlertDialog + onDismissRequest /
 * onConfirm) and the [TranslationProgressSheet] summary-column layout.
 */
@Composable
fun ConfirmTranslationDialog(
    chapterName: String,
    summary: TranslationSettingsSummary,
    showAgain: Boolean,
    onShowAgainChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(ATMR.strings.manga_translate))
            }
        },
        title = {
            Text(text = stringResource(ATMR.strings.translation_confirm_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Text(
                    text = chapterName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(MaterialTheme.padding.extraSmall))

                Text(
                    text = stringResource(ATMR.strings.translation_confirm_summary),
                    style = MaterialTheme.typography.titleSmall,
                )

                SettingRow(
                    label = stringResource(ATMR.strings.pref_translate_from),
                    value = summary.fromLanguageLabel,
                )
                SettingRow(
                    label = stringResource(ATMR.strings.pref_translate_to),
                    value = summary.toLanguageLabel,
                )
                SettingRow(
                    label = stringResource(ATMR.strings.pref_translator_engine),
                    value = summary.engineLabel,
                )
                summary.modelId?.let { modelId ->
                    SettingRow(
                        label = stringResource(ATMR.strings.pref_engine_model),
                        value = modelId,
                    )
                }
                SettingRow(
                    label = stringResource(ATMR.strings.pref_ocr_model),
                    value = summary.ocrModelLabel,
                )
                summary.maxOutputTokens?.let { tokens ->
                    SettingRow(
                        label = stringResource(ATMR.strings.pref_engine_max_output),
                        value = tokens,
                    )
                }
                SettingRow(
                    label = stringResource(ATMR.strings.pref_inpainting_mode),
                    value = inpaintingModeLabel(summary.inpaintingMode),
                )

                Spacer(Modifier.height(MaterialTheme.padding.extraSmall))

                LabeledCheckbox(
                    label = stringResource(ATMR.strings.translation_confirm_dont_show),
                    checked = !showAgain,
                    onCheckedChange = { checked -> onShowAgainChange(!checked) },
                )

                TextButton(onClick = onOpenSettings) {
                    Text(text = stringResource(ATMR.strings.translation_confirm_open_settings))
                }
            }
        },
    )
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = MaterialTheme.padding.medium),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun inpaintingModeLabel(rawMode: String): String = when (rawMode.uppercase()) {
    "QUALITY" -> stringResource(ATMR.strings.pref_inpainting_mode_quality)
    "FAST" -> stringResource(ATMR.strings.pref_inpainting_mode_fast)
    else -> rawMode
}
