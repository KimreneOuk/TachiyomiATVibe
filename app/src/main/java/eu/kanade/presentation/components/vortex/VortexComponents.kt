package eu.kanade.presentation.components.vortex

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Header row for the Vortex floating sheet.
 */
@Composable
fun VortexSheetHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(VortexTokens.ShapeBadge)
                .background(VortexTokens.AccentPrimary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = VortexTokens.AccentPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = VortexTokens.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = VortexTokens.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(VortexTokens.ShapeSquircle)
                .background(VortexTokens.CardFill)
                .border(1.dp, VortexTokens.CardBorder, VortexTokens.ShapeSquircle),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close",
                    tint = VortexTokens.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Top category pill bar for dual-focus switching (e.g. Reader Setup vs AI Engine).
 */
@Composable
fun VortexSegmentedPillBar(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(VortexTokens.ShapePill)
            .background(VortexTokens.CardFill)
            .border(1.dp, VortexTokens.CardBorder, VortexTokens.ShapePill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, tabTitle ->
            val isSelected = selectedIndex == index
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) VortexTokens.AccentPrimary else Color.Transparent,
                animationSpec = tween(durationMillis = 200),
                label = "pillBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) VortexTokens.TextOnAccent else VortexTokens.TextMuted,
                animationSpec = tween(durationMillis = 200),
                label = "pillText",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(VortexTokens.ShapePill)
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

/**
 * 11sp uppercase category header with wide letter spacing.
 */
@Composable
fun VortexSectionHeading(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        color = VortexTokens.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/**
 * Finger-friendly squircle visual selection button.
 */
@Composable
fun VortexSelectionTile(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) VortexTokens.AccentPrimary else VortexTokens.CardFill,
        label = "tileBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) VortexTokens.TextOnAccent else VortexTokens.TextPrimary,
        label = "tileText",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) VortexTokens.TextOnAccent else VortexTokens.AccentPrimary,
        label = "tileIcon",
    )
    val shadowModifier = if (selected) {
        Modifier.shadow(8.dp, VortexTokens.ShapeSquircle, ambientColor = VortexTokens.AccentGlow, spotColor = VortexTokens.AccentGlow)
    } else Modifier

    Box(
        modifier = modifier
            .then(shadowModifier)
            .clip(VortexTokens.ShapeSquircle)
            .background(bgColor)
            .border(
                1.dp,
                if (selected) Color.Transparent else VortexTokens.CardBorder,
                VortexTokens.ShapeSquircle,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
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
                        color = if (selected) VortexTokens.TextOnAccent.copy(alpha = 0.75f) else VortexTokens.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Precision slider slate contained in a dark capsule with live numeric value chip.
 */
@Composable
fun VortexSliderSlate(
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
            .clip(VortexTokens.ShapeCard)
            .background(VortexTokens.CardFill)
            .border(1.dp, VortexTokens.CardBorder, VortexTokens.ShapeCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = VortexTokens.AccentPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = VortexTokens.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(VortexTokens.ShapePill)
                    .background(VortexTokens.SheetBackground)
                    .border(1.dp, VortexTokens.CardBorder, VortexTokens.ShapePill)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = valueDisplay,
                    color = VortexTokens.TextPrimary,
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
                thumbColor = VortexTokens.AccentPrimary,
                activeTrackColor = VortexTokens.AccentPrimary,
                inactiveTrackColor = VortexTokens.CardBorder,
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Dark slate switch row for toggles.
 */
@Composable
fun VortexSwitchSlate(
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
            .clip(VortexTokens.ShapeCard)
            .background(VortexTokens.CardFill)
            .border(1.dp, VortexTokens.CardBorder, VortexTokens.ShapeCard)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(VortexTokens.ShapeBadge)
                .background(if (checked) VortexTokens.AccentPrimary.copy(alpha = 0.15f) else VortexTokens.SheetBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) VortexTokens.AccentPrimary else VortexTokens.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = VortexTokens.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = VortexTokens.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VortexTokens.TextOnAccent,
                checkedTrackColor = VortexTokens.AccentPrimary,
                uncheckedThumbColor = VortexTokens.TextMuted,
                uncheckedTrackColor = VortexTokens.SheetBackground,
                uncheckedBorderColor = VortexTokens.CardBorder,
            ),
        )
    }
}
