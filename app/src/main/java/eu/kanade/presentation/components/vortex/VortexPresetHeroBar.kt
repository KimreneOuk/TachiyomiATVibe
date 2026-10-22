package eu.kanade.presentation.components.vortex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel

enum class VortexPreset(val label: String, val subtitle: String) {
    SPEED("⚡ Speed", "Paddle + AOT"),
    BALANCED("⚖ Balanced", "Paddle + LaMa"),
    QUALITY("💎 Quality", "MangaOCR + LaMa"),
    CUSTOM("⚙ Custom", "Custom Tuning"),
}

/**
 * Helper to determine active preset from current OCR and inpainting settings.
 */
fun resolveActivePreset(
    ocrModel: OcrModel,
    inpaintModel: NeuralInpaintModel,
): VortexPreset {
    return when {
        ocrModel == OcrModel.PADDLEOCR_V6_SMALL && inpaintModel == NeuralInpaintModel.AOT_GAN -> VortexPreset.SPEED
        ocrModel == OcrModel.PADDLEOCR_V6_SMALL && inpaintModel == NeuralInpaintModel.LAMA_LITERT_GPU -> VortexPreset.BALANCED
        ocrModel == OcrModel.MANGAOCR && inpaintModel == NeuralInpaintModel.LAMA_LITERT_GPU -> VortexPreset.QUALITY
        else -> VortexPreset.CUSTOM
    }
}

/**
 * 3-tier master preset hero bar with dynamic custom profile state.
 */
@Composable
fun VortexPresetHeroBar(
    currentPreset: VortexPreset,
    onApplyPreset: (VortexPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(VortexTokens.ShapeCard)
            .background(VortexTokens.CardFill)
            .border(1.dp, VortexTokens.CardBorder, VortexTokens.ShapeCard)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(VortexPreset.SPEED, VortexPreset.BALANCED, VortexPreset.QUALITY).forEach { preset ->
            val isSelected = currentPreset == preset
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(VortexTokens.ShapeSquircle)
                    .background(if (isSelected) VortexTokens.AccentPrimary else VortexTokens.SheetBackground)
                    .border(
                        1.dp,
                        if (isSelected) VortexTokens.AccentPrimary else VortexTokens.CardBorder,
                        VortexTokens.ShapeSquircle,
                    )
                    .clickable { onApplyPreset(preset) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = preset.label,
                        color = if (isSelected) VortexTokens.TextOnAccent else VortexTokens.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                    Text(
                        text = preset.subtitle,
                        color = if (isSelected) VortexTokens.TextOnAccent.copy(alpha = 0.8f) else VortexTokens.TextMuted,
                        fontSize = 9.sp,
                    )
                }
            }
        }

        if (currentPreset == VortexPreset.CUSTOM) {
            Box(
                modifier = Modifier
                    .clip(VortexTokens.ShapeSquircle)
                    .background(VortexTokens.AccentPrimary.copy(alpha = 0.15f))
                    .border(1.dp, VortexTokens.AccentPrimary, VortexTokens.ShapeSquircle)
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = VortexPreset.CUSTOM.label,
                        color = VortexTokens.AccentPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Active",
                        color = VortexTokens.AccentPrimary,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}
