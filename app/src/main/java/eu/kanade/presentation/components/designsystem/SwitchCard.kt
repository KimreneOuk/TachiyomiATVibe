package eu.kanade.presentation.components.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
 * Clickable card row with an icon badge, labels and a trailing switch.
 */
@Composable
fun SwitchCard(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(DesignTokens.ShapeBadge)
                .background(if (checked) DesignTokens.AccentPrimary.copy(alpha = 0.15f) else DesignTokens.SheetBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) DesignTokens.AccentPrimary else DesignTokens.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DesignTokens.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = DesignTokens.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DesignTokens.TextOnAccent,
                checkedTrackColor = DesignTokens.AccentPrimary,
                uncheckedThumbColor = DesignTokens.TextMuted,
                uncheckedTrackColor = DesignTokens.SheetBackground,
                uncheckedBorderColor = DesignTokens.CardBorder,
            ),
        )
    }
}
