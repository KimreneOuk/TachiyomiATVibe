package eu.kanade.presentation.components.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Slider inside a card with a live numeric value chip.
 */
@Composable
fun SliderCard(
    icon: ImageVector,
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DesignTokens.AccentPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = DesignTokens.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(DesignTokens.ShapePill)
                    .background(DesignTokens.SheetBackground)
                    .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapePill)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = valueDisplay,
                    color = DesignTokens.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = DesignTokens.AccentPrimary,
                activeTrackColor = DesignTokens.AccentPrimary,
                inactiveTrackColor = DesignTokens.CardBorder,
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
