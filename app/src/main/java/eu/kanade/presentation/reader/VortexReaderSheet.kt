package eu.kanade.presentation.reader

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.vortex.VortexSegmentedPillBar
import eu.kanade.presentation.components.vortex.VortexSheetHeader
import eu.kanade.presentation.components.vortex.VortexTokens
import eu.kanade.presentation.reader.vortex.VortexReaderSetupTab
import eu.kanade.presentation.reader.vortex.VortexTranslationTab
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory

/**
 * Unified floating bottom sheet for Reader Setup and AI Translation.
 * Provides zero-context-switch tabbed access over the live manga canvas.
 */
@Composable
fun VortexReaderSheet(
    screenModel: ReaderSettingsScreenModel,
    translationState: TranslationSettingsState,
    initialTab: Int = 0,
    translateButtonWithControls: Boolean,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
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
    onTranslationAiModelChange: (String) -> Unit,
    onResetTranslationData: (Boolean) -> Unit,
    onResetInpaintData: () -> Unit,
    onResetOcrData: () -> Unit,
    onResetEverything: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    var isInteractingWithVisuals by remember { mutableStateOf(false) }

    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(isInteractingWithVisuals) {
        if (isInteractingWithVisuals) {
            window?.setDimAmount(0f)
            onHideMenus()
        } else {
            window?.setDimAmount(0.65f)
            onShowMenus()
        }
    }

    AdaptiveSheet(
        onDismissRequest = {
            onDismissRequest()
            onShowMenus()
        },
    ) {
        Surface(
            color = VortexTokens.SheetBackground,
            shape = VortexTokens.ShapeSheet,
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                // Header Row
                VortexSheetHeader(
                    icon = Icons.Outlined.Settings,
                    title = "Configuration",
                    subtitle = if (selectedTab == 0) "Display & Reading Mode" else "Live AI Translation",
                    onClose = onDismissRequest,
                )

                // Segmented Pill Bar
                VortexSegmentedPillBar(
                    tabs = listOf("📖 Reader Setup", "文A AI Engine"),
                    selectedIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Smooth Crossfade between Tabs
                Crossfade(
                    targetState = selectedTab,
                    label = "tabCrossfade",
                ) { tab ->
                    when (tab) {
                        0 -> VortexReaderSetupTab(
                            screenModel = screenModel,
                            translateButtonWithControls = translateButtonWithControls,
                            onTranslateButtonWithControlsChange = onTranslateButtonWithControlsChange,
                            onInteractingWithVisuals = { isInteractingWithVisuals = it },
                        )
                        1 -> VortexTranslationTab(
                            state = translationState,
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
                            onTranslationAiModelChange = onTranslationAiModelChange,
                            onResetTranslationData = onResetTranslationData,
                            onResetInpaintData = onResetInpaintData,
                            onResetOcrData = onResetOcrData,
                            onResetEverything = onResetEverything,
                        )
                    }
                }
            }
        }
    }
}
