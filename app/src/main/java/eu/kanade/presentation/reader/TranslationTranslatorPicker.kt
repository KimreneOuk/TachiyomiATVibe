package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.reader.settings.OptionCardRow
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Focused translator chooser opened from the General screen: which family
 * (standard / AI model) and which concrete translator. Credentials and model
 * selection deliberately stay in Advanced → Translation Engine; the last row
 * hands the user off there when the chosen AI provider still needs setup.
 */
@Composable
internal fun ColumnScope.TranslationTranslatorPicker(
    state: TranslationSettingsState,
    onEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onStandardEngineChange: (StandardEngine) -> Unit,
    onAiEngineChange: (AiEngine) -> Unit,
    onOpenEngineConfig: () -> Unit,
) {
    OptionCardRow(
        options = listOf(
            stringResource(ATMR.strings.pref_translation_type_standard) to TranslationEngineCategory.STANDARD,
            stringResource(ATMR.strings.pref_translation_type_ai) to TranslationEngineCategory.AI_MODEL,
        ),
        selected = state.engineCategory,
        onSelect = onEngineCategoryChange,
        perRow = 2,
    )

    when (state.engineCategory) {
        TranslationEngineCategory.STANDARD -> {
            val engines = StandardTranslatorKind.entries.associate {
                StandardEngine.valueOf(it.name) to it.label
            }
            EngineListRow(
                title = stringResource(ATMR.strings.pref_standard_engine),
                entries = engines,
                value = state.standardEngine,
                onValueChange = onStandardEngineChange,
            )
        }
        TranslationEngineCategory.AI_MODEL -> {
            val providers = AiTranslatorKind.entries.associate { it.engine to it.label }
            EngineListRow(
                title = stringResource(ATMR.strings.pref_ai_provider),
                entries = providers,
                value = state.aiEngine,
                onValueChange = onAiEngineChange,
            )

            val needsSetup = if (state.aiEngine == AiEngine.LMSTUDIO) {
                state.aiBaseUrl.isBlank()
            } else {
                state.aiApiKey.isBlank()
            }
            TextPreferenceWidget(
                title = stringResource(ATMR.strings.translation_engine_configure_advanced),
                subtitle = when {
                    needsSetup -> stringResource(ATMR.strings.translation_engine_needs_configuration)
                    state.aiModel.isNotBlank() -> state.aiModel
                    else -> stringResource(ATMR.strings.pref_engine_model)
                },
                onPreferenceClick = onOpenEngineConfig,
                widget = {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = if (needsSetup) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        }
    }
}
