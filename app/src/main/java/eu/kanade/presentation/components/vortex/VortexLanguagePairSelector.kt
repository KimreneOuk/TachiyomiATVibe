package eu.kanade.presentation.components.vortex

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.SelectorOption

data class LanguagePair(val from: String, val to: String, val label: String)

private val FREQUENT_PAIRS = listOf(
    LanguagePair("Japanese", "English", "JA → EN"),
    LanguagePair("Korean", "English", "KO → EN"),
    LanguagePair("Chinese", "English", "ZH → EN"),
    LanguagePair("Auto", "English", "Auto → EN"),
)

/**
 * Fluid language pair selector with frequent quick-chips and animated swap button.
 */
@Composable
fun VortexLanguagePairSelector(
    fromLanguage: String,
    toLanguage: String,
    onFromLanguageChange: (String) -> Unit,
    onToLanguageChange: (String) -> Unit,
    fromOptions: List<SelectorOption<String>>,
    toOptions: List<SelectorOption<String>>,
    modifier: Modifier = Modifier,
) {
    var swapRotation by remember { mutableFloatStateOf(0f) }
    val animatedRotation by animateFloatAsState(
        targetValue = swapRotation,
        animationSpec = tween(durationMillis = 300),
        label = "swapRotation",
    )

    val currentFromLabel = remember(fromLanguage, fromOptions) {
        fromOptions.firstOrNull { it.value.equals(fromLanguage, ignoreCase = true) }?.label ?: fromLanguage
    }
    val currentToLabel = remember(toLanguage, toOptions) {
        toOptions.firstOrNull { it.value.equals(toLanguage, ignoreCase = true) }?.label ?: toLanguage
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(VortexTokens.ShapeCard)
            .background(VortexTokens.CardFill)
            .border(1.dp, VortexTokens.CardBorder, VortexTokens.ShapeCard)
            .padding(14.dp),
    ) {
        // Horizontal row of quick preset language pair pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FREQUENT_PAIRS.forEach { pair ->
                val isSelected = fromLanguage.equals(pair.from, ignoreCase = true) &&
                    toLanguage.equals(pair.to, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .clip(VortexTokens.ShapePill)
                        .background(if (isSelected) VortexTokens.AccentPrimary else VortexTokens.SheetBackground)
                        .border(
                            1.dp,
                            if (isSelected) VortexTokens.AccentPrimary else VortexTokens.CardBorder,
                            VortexTokens.ShapePill,
                        )
                        .clickable {
                            onFromLanguageChange(pair.from)
                            onToLanguageChange(pair.to)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = pair.label,
                        color = if (isSelected) VortexTokens.TextOnAccent else VortexTokens.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Side-by-side selectors with animated swap button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SearchableConfigSelectorBox(
                    selectedLabel = currentFromLabel,
                    options = fromOptions,
                    onSelect = onFromLanguageChange,
                    placeholder = "From",
                    matchAnchorWidth = false,
                )
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(40.dp)
                    .clip(VortexTokens.ShapeSquircle)
                    .background(VortexTokens.SheetBackground)
                    .border(1.dp, VortexTokens.CardBorder, VortexTokens.ShapeSquircle),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = {
                        swapRotation += 180f
                        if (!fromLanguage.equals("Auto", ignoreCase = true)) {
                            val temp = fromLanguage
                            onFromLanguageChange(toLanguage)
                            onToLanguageChange(temp)
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = "Swap Languages",
                        tint = VortexTokens.AccentPrimary,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(animatedRotation),
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                SearchableConfigSelectorBox(
                    selectedLabel = currentToLabel,
                    options = toOptions,
                    onSelect = onToLanguageChange,
                    placeholder = "To",
                    matchAnchorWidth = false,
                )
            }
        }
    }
}
