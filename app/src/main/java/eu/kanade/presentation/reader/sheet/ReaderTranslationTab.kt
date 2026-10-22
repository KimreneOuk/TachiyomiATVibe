package eu.kanade.presentation.reader.sheet

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import eu.kanade.presentation.components.SelectorOption
import eu.kanade.presentation.components.TranslatorChoice
import eu.kanade.presentation.more.settings.widget.AiModelPickerDialog
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderTranslationTab(
    state: TranslationSettingsState,
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
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = showAdvanced,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(initialOffsetX = { it }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut())
            } else {
                (slideInHorizontally(initialOffsetX = { -it / 3 }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
            }
        },
        label = "translationSurfaceTransition",
        modifier = modifier.fillMaxWidth(),
    ) { isAdvanced ->
        if (isAdvanced) {
            AdvancedTranslationSurface(
                state = state,
                onBack = { showAdvanced = false },
                onOcrModelChange = onOcrModelChange,
                onInpaintingModeChange = onTranslationInpaintingModeChange,
                onNeuralModelChange = onTranslationInpaintingNeuralModelChange,
                onRequestReset = { showResetDialog = true },
            )
        } else {
            GeneralTranslationSurface(
                state = state,
                onTranslationEnabledChange = onTranslationEnabledChange,
                onAutoTranslateChange = onAutoTranslateChange,
                onTranslateFromLanguageChange = onTranslateFromLanguageChange,
                onTranslateToLanguageChange = onTranslateToLanguageChange,
                onEngineCategoryChange = onTranslationEngineCategoryChange,
                onStandardEngineChange = onTranslationStandardEngineChange,
                onAiEngineChange = onTranslationAiEngineChange,
                onAiApiKeyChange = onTranslationAiApiKeyChange,
                onAiBaseUrlChange = onTranslationAiBaseUrlChange,
                onAiModelChange = onTranslationAiModelChange,
                onFetchAiModels = onFetchAiModels,
                onOpenAdvanced = { showAdvanced = true },
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    text = "Wipe Translation Artifacts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    text = "This will completely clear cached OCR text detections, translated bubbles, and inpainted textures for this chapter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetEverything()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Wipe All", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(tachiyomi.i18n.MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun GeneralTranslationSurface(
    state: TranslationSettingsState,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onStandardEngineChange: (StandardEngine) -> Unit,
    onAiEngineChange: (AiEngine) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiBaseUrlChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val fromOptions = remember {
        TextRecognizerLanguage.entries.map { lang ->
            SelectorOption(
                value = lang.name,
                label = lang.label,
            )
        }
    }
    val currentFromLabel = remember(state.translateFromLanguage) {
        TextRecognizerLanguage.entries.firstOrNull { it.name == state.translateFromLanguage }?.label
            ?: state.translateFromLanguage.ifEmpty { "Auto Detect" }
    }

    val toOptions = remember {
        TextTranslatorLanguage.entries.map { lang ->
            SelectorOption(
                value = lang.name,
                label = lang.label,
            )
        }
    }
    val currentToLabel = remember(state.translateToLanguage) {
        TextTranslatorLanguage.entries.firstOrNull { it.name == state.translateToLanguage }?.label
            ?: state.translateToLanguage.ifEmpty { "English" }
    }

    val standardOptions = remember {
        StandardTranslatorKind.entries.map { kind ->
            SelectorOption(
                value = StandardEngine.valueOf(kind.name),
                label = kind.label,
            )
        }
    }

    val aiProviderOptions = remember {
        AiTranslatorKind.entries.map { kind ->
            SelectorOption(
                value = kind.engine,
                label = kind.label,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ReaderSectionHeading(title = "Translation Activation")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ReaderSwitchSlate(
                icon = Icons.Outlined.Translate,
                title = stringResource(ATMR.strings.pref_translation_enabled),
                subtitle = "Enables page translate button & floating draggable handle",
                checked = state.enabled,
                onCheckedChange = onTranslationEnabledChange,
            )

            ReaderSwitchSlate(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(ATMR.strings.pref_auto_translate),
                subtitle = "Automatically translates incoming pages as you read",
                checked = state.autoTranslate,
                onCheckedChange = { auto ->
                    if (auto && !state.enabled) {
                        onTranslationEnabledChange(true)
                    }
                    onAutoTranslateChange(auto)
                },
            )
        }

        ReaderSectionHeading(title = "Languages")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "SOURCE LANGUAGE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SearchableConfigSelectorBox(
                selectedLabel = currentFromLabel,
                options = fromOptions,
                onSelect = onTranslateFromLanguageChange,
                placeholder = "Source Language",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val prevFrom = state.translateFromLanguage
                        val prevTo = state.translateToLanguage
                        if (prevFrom.isNotBlank() && prevTo.isNotBlank()) {
                            onTranslateFromLanguageChange(prevTo)
                            onTranslateToLanguageChange(prevFrom)
                        }
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapVert,
                        contentDescription = "Swap Languages",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Text(
                text = "TARGET LANGUAGE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SearchableConfigSelectorBox(
                selectedLabel = currentToLabel,
                options = toOptions,
                onSelect = onTranslateToLanguageChange,
                placeholder = "Target Language",
            )
        }

        ReaderSectionHeading(title = "Translator")

        ReaderSegmentedPillBar(
            tabs = listOf("Normal", "AI Powered"),
            selectedIndex = if (state.engineCategory == TranslationEngineCategory.STANDARD) 0 else 1,
            onTabSelected = { index ->
                val newCategory = if (index == 0) {
                    TranslationEngineCategory.STANDARD
                } else {
                    TranslationEngineCategory.AI_MODEL
                }
                onEngineCategoryChange(newCategory)
            },
            modifier = Modifier.padding(horizontal = 0.dp),
        )

        if (state.engineCategory == TranslationEngineCategory.STANDARD) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "TRANSLATOR ENGINE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SearchableConfigSelectorBox(
                    selectedLabel = standardOptions.firstOrNull { it.value == state.standardEngine }?.label
                        ?: state.standardEngine.name,
                    options = standardOptions,
                    onSelect = onStandardEngineChange,
                    placeholder = "Select Translator",
                )
            }
        } else {
            var showModelPicker by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "AI PROVIDER",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SearchableConfigSelectorBox(
                    selectedLabel = aiProviderOptions.firstOrNull { it.value == state.aiEngine }?.label
                        ?: state.aiEngine.name,
                    options = aiProviderOptions,
                    onSelect = onAiEngineChange,
                    placeholder = "Select AI Provider",
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
                    var keyVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = state.aiApiKey,
                        onValueChange = onAiApiKeyChange,
                        label = { Text("API Key", fontSize = 11.sp) },
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

                Text(
                    text = "AI MODEL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
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
                            color = if (state.aiModel.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Tap to search, reload online catalog, or enter custom ID",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "Choose",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .clickable { onOpenAdvanced() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column {
                    Text(
                        text = "Advanced Vision & Wipe",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "OCR, Inpainting & Data Wipe",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
private fun AdvancedTranslationSurface(
    state: TranslationSettingsState,
    onBack: () -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
    onInpaintingModeChange: (String) -> Unit,
    onNeuralModelChange: (NeuralInpaintModel) -> Unit,
    onRequestReset: () -> Unit,
) {
    val ocrOptions = remember {
        listOf(
            SelectorOption(OcrModel.PADDLEOCR_V6_SMALL, "PaddleOCR v6", "Fast On-Device"),
            SelectorOption(OcrModel.MANGAOCR, "Manga-OCR", "High Accuracy Japanese"),
            SelectorOption(OcrModel.MLKIT, "Google MLKit", "Universal Latin/CJK"),
        )
    }
    val currentOcrLabel = remember(state.ocrModel) {
        ocrOptions.firstOrNull { it.value == state.ocrModel }?.label ?: state.ocrModel.name
    }

    val inpaintOptions = remember {
        listOf(
            SelectorOption("FAST", "FAST", "Solid / Blur Fill (0% GPU)"),
            SelectorOption("BALANCE", "BALANCE", "Patch Neural (AOT-GAN)"),
            SelectorOption("QUALITY", "QUALITY", "Full Context Neural (Manga LaMa)"),
        )
    }
    val currentInpaint = state.inpaintingMode.ifEmpty { "QUALITY" }
    val currentInpaintLabel = remember(currentInpaint) {
        inpaintOptions.firstOrNull { it.value.equals(currentInpaint, ignoreCase = true) }?.label ?: currentInpaint
    }

    val neuralModelOptions = remember {
        listOf(
            SelectorOption(NeuralInpaintModel.LAMA_LITERT_GPU, "Manga LaMa", "LiteRT GPU Accelerated"),
            SelectorOption(NeuralInpaintModel.AOT_GAN, "AOT-GAN", "512px High Speed"),
        )
    }
    val currentNeuralLabel = remember(state.inpaintingNeuralModel) {
        neuralModelOptions.firstOrNull { it.value == state.inpaintingNeuralModel }?.label ?: state.inpaintingNeuralModel.name
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to General Settings",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "Advanced Vision & Inpainting",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "OCR DETECTION ENGINE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SearchableConfigSelectorBox(
                selectedLabel = currentOcrLabel,
                options = ocrOptions,
                onSelect = onOcrModelChange,
                placeholder = "Select OCR Model",
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "INPAINTING MODE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SearchableConfigSelectorBox(
                selectedLabel = currentInpaintLabel,
                options = inpaintOptions,
                onSelect = onInpaintingModeChange,
                placeholder = "Select Inpainting Mode",
            )

            val isNeuralActive = !currentInpaint.equals("FAST", ignoreCase = true)
            AnimatedVisibility(
                visible = isNeuralActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "NEURAL INPAINT MODEL",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SearchableConfigSelectorBox(
                        selectedLabel = currentNeuralLabel,
                        options = neuralModelOptions,
                        onSelect = onNeuralModelChange,
                        placeholder = "Select Neural Model",
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "DANGER ZONE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Wiping translation artifacts completely purges cached detection bounding boxes, OCR text, translation strings, and inpainted textures for this chapter.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onRequestReset,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Wipe All Translation Artifacts", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
