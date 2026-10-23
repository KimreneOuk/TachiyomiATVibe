package eu.kanade.presentation.components.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Category pill bar for switching between sibling views, e.g. Focus/Details.
 */
@Composable
fun SegmentedPillBar(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(DesignTokens.ShapePill)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapePill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, tabTitle ->
            val isSelected = selectedIndex == index
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) DesignTokens.AccentPrimary else Color.Transparent,
                animationSpec = tween(durationMillis = 200),
                label = "pillBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) DesignTokens.TextOnAccent else DesignTokens.TextMuted,
                animationSpec = tween(durationMillis = 200),
                label = "pillText",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(DesignTokens.ShapePill)
                    .background(bgColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTabSelected(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tabTitle,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
