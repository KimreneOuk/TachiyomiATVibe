package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory

/**
 * Dedicated bottom drawer for Live AI Translation settings.
 * Accessed via the dedicated translate affordance on the reader dock.
 */
@Composable
fun ReaderTranslationSheet(
    state: TranslationSettingsState,
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit = {},
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit = {},
    onResetTranslationData: (Boolean) -> Unit,
    onResetInpaintData: () -> Unit,
    onResetOcrData: () -> Unit,
    onResetEverything: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdaptiveSheet(
        forceSheetMode = true,
        onDismissRequest = {
            onDismissRequest()
            onShowMenus()
        },
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.68f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
                Spacer(modifier = Modifier.height(4.dp))

                ReaderSheetHeader(
                    icon = Icons.Outlined.Translate,
                    title = "Translation Settings",
                    subtitle = "Live AI Translation & Vision Pipeline",
                    onClose = onDismissRequest,
                )

                Spacer(modifier = Modifier.height(8.dp))

                ReaderTranslationTab(
                    state = state,
                    onTranslationEnabledChange = onTranslationEnabledChange,
                    onAutoTranslateChange = onAutoTranslateChange,
                    onTranslateFromLanguageChange = onTranslateFromLanguageChange,
                    onTranslateToLanguageChange = onTranslateToLanguageChange,
                    onOcrModelChange = onOcrModelChange,
                    onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                    onTranslationInpaintingNeuralModelChange = onTranslationInpaintingNeuralModelChange,
                    onTranslationEngineCategoryChange = onTranslationEngineCategoryChange,
                    onTranslationStandardEngineChange = onTranslationStandardEngineChange,
                    onTranslationAiEngineChange = onTranslationAiEngineChange,
                    onTranslationAiApiKeyChange = onTranslationAiApiKeyChange,
                    onTranslationAiBaseUrlChange = onTranslationAiBaseUrlChange,
                    onTranslationAiModelChange = onTranslationAiModelChange,
                    onFetchAiModels = onFetchAiModels,
                    onResetTranslationData = onResetTranslationData,
                    onResetInpaintData = onResetInpaintData,
                    onResetOcrData = onResetOcrData,
                    onResetEverything = onResetEverything,
                )
            }
        }
    }
}
