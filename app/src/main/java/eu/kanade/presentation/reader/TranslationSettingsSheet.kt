package eu.kanade.presentation.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.model.TranslationProgressSnapshot
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Reader translation settings sheet with two-level navigation: General (the
 * five primary controls) opens by default, Advanced is a compact directory
 * into engine/OCR/inpainting/activity/data category screens. Navigation is
 * in-sheet so configuration stays open while moving between levels; system
 * back walks the hierarchy and only dismisses the sheet from General.
 *
 * All callbacks dispatch the same view-model actions as before; only the
 * presentation hierarchy changed.
 */
@Composable
fun TranslationSettingsSheet(
    state: TranslationSettingsState,
    onDismissRequest: () -> Unit,
    onStopAllTranslation: () -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
    onResetTranslationData: (Boolean) -> Unit,
    onResetInpaintData: () -> Unit,
    onResetOcrData: () -> Unit,
    onResetEverything: () -> Unit,
    onDeleteChapterTranslation: () -> Unit,
    queue: ImmutableList<eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueuedPageInfo> = persistentListOf(),
    translationProgress: Pair<Int, Int> = Pair(0, 0),
    translationCurrentPage: Int = 0,
    translationBatchProgress: TranslationProgressSnapshot? = null,
    onRetryBatch: (() -> Unit)? = null,
) {
    var level by rememberSaveable { mutableStateOf(TranslationSettingsLevel.General) }

    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
        // Swipe-down dismiss only from General; deeper levels keep gestures
        // predictable and rely on the back arrow / system back instead.
        enableSwipeDismiss = level == TranslationSettingsLevel.General,
    ) {
        BackHandler(enabled = level != TranslationSettingsLevel.General) {
            level = level.parent ?: TranslationSettingsLevel.General
        }

        AnimatedContent(
            targetState = level,
            transitionSpec = {
                val forward = targetState.depth >= initialState.depth
                val slide = tween<IntOffset>(220)
                val fade = tween<Float>(220)
                (slideInHorizontally(slide) { if (forward) it / 5 else -it / 5 } + fadeIn(fade)) togetherWith
                    (slideOutHorizontally(slide) { if (forward) -it / 5 else it / 5 } + fadeOut(fade))
            },
            label = "TranslationSettingsNav",
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                LevelHeader(
                    level = current,
                    onClose = onDismissRequest,
                    onBack = { level = current.parent ?: TranslationSettingsLevel.General },
                )

                when (current) {
                    TranslationSettingsLevel.General -> TranslationGeneralSection(
                        state = state,
                        onTranslationEnabledChange = onTranslationEnabledChange,
                        onAutoTranslateChange = onAutoTranslateChange,
                        onTranslateFromLanguageChange = onTranslateFromLanguageChange,
                        onTranslateToLanguageChange = onTranslateToLanguageChange,
                        onOpenTranslator = { level = TranslationSettingsLevel.Translator },
                        onOpenAdvanced = { level = TranslationSettingsLevel.Advanced },
                    )
                    TranslationSettingsLevel.Translator -> TranslationTranslatorPicker(
                        state = state,
                        onEngineCategoryChange = onTranslationEngineCategoryChange,
                        onStandardEngineChange = onTranslationStandardEngineChange,
                        onAiEngineChange = onTranslationAiEngineChange,
                        onOpenEngineConfig = { level = TranslationSettingsLevel.Engine },
                    )
                    TranslationSettingsLevel.Advanced -> TranslationAdvancedDirectory(
                        onOpenEngine = { level = TranslationSettingsLevel.Engine },
                        onOpenOcr = { level = TranslationSettingsLevel.Ocr },
                        onOpenInpaint = { level = TranslationSettingsLevel.Inpaint },
                        onOpenActivity = { level = TranslationSettingsLevel.Activity },
                        onOpenData = { level = TranslationSettingsLevel.Data },
                    )
                    TranslationSettingsLevel.Engine -> EngineRows(
                        state = state,
                        onTranslationEngineCategoryChange = onTranslationEngineCategoryChange,
                        onTranslationStandardEngineChange = onTranslationStandardEngineChange,
                        onTranslationDeeplApiKeyChange = onTranslationDeeplApiKeyChange,
                        onTranslationAiEngineChange = onTranslationAiEngineChange,
                        onTranslationAiApiKeyChange = onTranslationAiApiKeyChange,
                        onTranslationAiBaseUrlChange = onTranslationAiBaseUrlChange,
                        onTranslationAiModelChange = onTranslationAiModelChange,
                        onFetchAiModels = onFetchAiModels,
                    )
                    TranslationSettingsLevel.Ocr -> OcrSection(
                        state = state,
                        onOcrModelChange = onOcrModelChange,
                    )
                    TranslationSettingsLevel.Inpaint -> InpaintSection(
                        inpaintingMode = state.inpaintingMode,
                        neuralModel = state.inpaintingNeuralModel,
                        onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                        onTranslationInpaintingNeuralModelChange = onTranslationInpaintingNeuralModelChange,
                    )
                    TranslationSettingsLevel.Activity -> ActivitySection(
                        queue = queue,
                        translationProgress = translationProgress,
                        translationCurrentPage = translationCurrentPage,
                        translationBatchProgress = translationBatchProgress,
                        onRetryBatch = onRetryBatch,
                        onStopAllTranslation = onStopAllTranslation,
                    )
                    TranslationSettingsLevel.Data -> DataManagementSection(
                        onResetTranslationData = onResetTranslationData,
                        onResetInpaintData = onResetInpaintData,
                        onResetOcrData = onResetOcrData,
                        onResetEverything = onResetEverything,
                        onDeleteChapterTranslation = onDeleteChapterTranslation,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.LevelHeader(
    level: TranslationSettingsLevel,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (level.parent != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(MR.strings.action_bar_up_description),
                )
            }
        }
        Text(
            text = levelTitle(level),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (level.parent == null) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_close),
                )
            }
        }
    }
}

@Composable
private fun levelTitle(level: TranslationSettingsLevel): String = when (level) {
    TranslationSettingsLevel.General -> stringResource(ATMR.strings.reader_compare_open_settings)
    TranslationSettingsLevel.Translator -> stringResource(ATMR.strings.translation_section_translator)
    TranslationSettingsLevel.Advanced -> stringResource(ATMR.strings.translation_section_advanced)
    TranslationSettingsLevel.Engine -> stringResource(ATMR.strings.translation_engine_active_title)
    TranslationSettingsLevel.Ocr -> stringResource(ATMR.strings.translation_advanced_ocr)
    TranslationSettingsLevel.Inpaint -> stringResource(ATMR.strings.translation_section_inpainting)
    TranslationSettingsLevel.Activity -> stringResource(ATMR.strings.translation_section_activity)
    TranslationSettingsLevel.Data -> stringResource(ATMR.strings.translation_section_data_management)
}
