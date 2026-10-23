package eu.kanade.presentation.components.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.SelectorOption

/**
 * Card with searchable from/to language boxes and an animated swap button.
 */
@Composable
fun LanguagePairSelector(
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
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
    ) {
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
                    .clip(DesignTokens.ShapeSquircle)
                    .background(DesignTokens.SheetBackground)
                    .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeSquircle),
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
                        tint = DesignTokens.AccentPrimary,
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
