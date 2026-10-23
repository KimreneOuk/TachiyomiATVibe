package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SegmentedPillBar
import eu.kanade.presentation.more.settings.widget.AiModelPickerDialog
import eu.kanade.presentation.translation.aiProviderOptions
import eu.kanade.presentation.translation.standardEngineOptions
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Translator engine picker of the reader translation sheet: Standard/AI
 * category pill bar, the standard engine selector (with DeepL key entry) or
 * the AI provider credentials and model picker.
 */
@Composable
internal fun TranslationEngineSection(
    state: TranslationSettingsState,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.translation_section_translator))

    SegmentedPillBar(
        tabs = listOf(
            stringResource(ATMR.strings.reader_translation_engine_standard),
            stringResource(ATMR.strings.reader_translation_engine_ai),
        ),
        selectedIndex = if (state.engineCategory == TranslationEngineCategory.STANDARD) 0 else 1,
        onTabSelected = { index ->
            onTranslationEngineCategoryChange(
                if (index == 0) TranslationEngineCategory.STANDARD else TranslationEngineCategory.AI_MODEL,
            )
        },
    )

    if (state.engineCategory == TranslationEngineCategory.STANDARD) {
        StandardEngineCard(
            state = state,
            onStandardEngineChange = onTranslationStandardEngineChange,
            onDeeplApiKeyChange = onTranslationDeeplApiKeyChange,
        )
    } else {
        AiEngineCard(
            state = state,
            onAiEngineChange = onTranslationAiEngineChange,
            onAiApiKeyChange = onTranslationAiApiKeyChange,
            onAiBaseUrlChange = onTranslationAiBaseUrlChange,
            onAiModelChange = onTranslationAiModelChange,
            onFetchAiModels = onFetchAiModels,
        )
    }
}

@Composable
private fun StandardEngineCard(
    state: TranslationSettingsState,
    onStandardEngineChange: (StandardEngine) -> Unit,
    onDeeplApiKeyChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchableConfigSelectorBox(
            selectedLabel = standardEngineOptions.firstOrNull { it.value == state.standardEngine }?.label
                ?: state.standardEngine.name,
            options = standardEngineOptions,
            onSelect = onStandardEngineChange,
            placeholder = stringResource(ATMR.strings.translation_section_translator),
        )

        if (state.standardEngine == StandardEngine.DEEPL) {
            SecretKeyField(
                label = stringResource(ATMR.strings.reader_translation_deepl_api_key),
                value = state.deeplApiKey,
                onValueChange = onDeeplApiKeyChange,
            )
        }
    }
}

@Composable
private fun AiEngineCard(
    state: TranslationSettingsState,
    onAiEngineChange: (AiEngine) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiBaseUrlChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
) {
    var showModelPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchableConfigSelectorBox(
            selectedLabel = aiProviderOptions.firstOrNull { it.value == state.aiEngine }?.label
                ?: state.aiEngine.name,
            options = aiProviderOptions,
            onSelect = onAiEngineChange,
            placeholder = stringResource(ATMR.strings.reader_translation_engine_ai),
        )

        if (state.aiEngine == AiEngine.LMSTUDIO) {
            OutlinedTextField(
                value = state.aiBaseUrl,
                onValueChange = onAiBaseUrlChange,
                label = { Text("Base URL", fontSize = 11.sp) },
                placeholder = { Text("http://localhost:1234/v1", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                singleLine = true,
            )
        } else {
            SecretKeyField(
                label = "API Key",
                value = state.aiApiKey,
                onValueChange = onAiApiKeyChange,
            )
        }

        Text(
            text = "AI MODEL",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = DesignTokens.TextMuted,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DesignTokens.SheetBackground)
                .border(1.dp, DesignTokens.CardBorder, RoundedCornerShape(12.dp))
                .clickable { showModelPicker = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.aiModel.ifBlank { "Select or enter custom model…" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DesignTokens.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Tap to search, reload online catalog, or enter custom ID",
                    fontSize = 10.sp,
                    color = DesignTokens.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = "Choose",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = DesignTokens.AccentPrimary,
            )
        }
    }

    if (showModelPicker) {
        AiModelPickerDialog(
            title = "Select Model (${state.aiEngine.name})",
            currentModel = state.aiModel,
            recentModels = state.aiRecentModels,
            listState = state.aiModelFetchState,
            hasApiKey = state.aiApiKey.isNotBlank() || (state.aiEngine == AiEngine.LMSTUDIO && state.aiBaseUrl.isNotBlank()),
            missingConnectionMessage = if (state.aiEngine == AiEngine.LMSTUDIO) {
                "Enter a Base URL to fetch models"
            } else {
                "Enter an API key to fetch models"
            },
            onDismissRequest = { showModelPicker = false },
            onFetchModels = onFetchAiModels,
            onSelectModel = onAiModelChange,
            onManualModel = onAiModelChange,
        )
    }
}

/** Password-style text field with a visibility toggle (API keys). */
@Composable
private fun SecretKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                    imageVector = if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        singleLine = true,
    )
}
