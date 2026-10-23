package eu.kanade.presentation.components.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Finger-friendly selection button with a filled accent state.
 */
@Composable
fun SelectionTile(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) DesignTokens.AccentPrimary else DesignTokens.CardFill,
        label = "tileBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) DesignTokens.TextOnAccent else DesignTokens.TextPrimary,
        label = "tileText",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) DesignTokens.TextOnAccent else DesignTokens.AccentPrimary,
        label = "tileIcon",
    )
    val shadowModifier = if (selected) {
        Modifier.shadow(8.dp, DesignTokens.ShapeSquircle, ambientColor = DesignTokens.AccentGlow, spotColor = DesignTokens.AccentGlow)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(shadowModifier)
            .clip(DesignTokens.ShapeSquircle)
            .background(bgColor)
            .border(
                1.dp,
                if (selected) Color.Transparent else DesignTokens.CardBorder,
                DesignTokens.ShapeSquircle,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = if (selected) DesignTokens.TextOnAccent.copy(alpha = 0.75f) else DesignTokens.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
