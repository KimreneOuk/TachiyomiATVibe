package eu.kanade.presentation.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * TachiyomiAT: a side-mounted handle that lets the user quickly compare the
 * original and translated image for the CURRENT page only, plus open the full
 * translation settings sheet.
 *
 * Layout: a small circular handle docked to the left edge, vertically centered.
 * Tapping it expands a short vertical menu (Show original / Show translated /
 * Translation settings); selecting a row collapses it again and, for the two
 * toggle rows, flips [showingTranslated] for the current page via [onSelectOriginal]
 * / [onSelectTranslated].
 *
 * Visibility: the handle is always present (independent of the reader chrome),
 * but the Original/Translated rows are disabled (greyed) when [hasTranslation] is
 * false (a page with no translation yet) — only Translation settings stays
 * tappable. The whole handle is hidden when translation is disabled entirely.
 *
 * Active-state highlighting reflects [showingTranslated]: whichever side is
 * currently shown is tinted with the primary color.
 *
 * @param visible           whether to show the handle at all (translation enabled).
 * @param hasTranslation    whether the current page has a translated image to compare.
 * @param showingTranslated whether the current page is currently showing its translation.
 * @param onSelectOriginal  flip the current page to its original image.
 * @param onSelectTranslated flip the current page to its translated image.
 * @param onOpenSettings    open the reader translation settings sheet.
 */
@Composable
fun TranslationCompareHandle(
    visible: Boolean,
    hasTranslation: Boolean,
    showingTranslated: Boolean,
    onSelectOriginal: () -> Unit,
    onSelectTranslated: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    var expanded by remember { mutableStateOf(false) }

    // Cap the width so the expanded menu stays on screen on small devices /
    // landscape; vertical centring is applied by the caller's Box alignment.
    Box(
        modifier = modifier.widthIn(max = 180.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The expanded menu appears ABOVE the handle (expanded downward from
            // the centred handle would push it off the bottom edge on phones).
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(120)) + expandVertically(tween(120)),
                exit = fadeOut(tween(80)) + shrinkVertically(tween(80)),
            ) {
                MenuColumn(
                    hasTranslation = hasTranslation,
                    showingTranslated = showingTranslated,
                    onSelectOriginal = {
                        onSelectOriginal()
                        expanded = false
                    },
                    onSelectTranslated = {
                        onSelectTranslated()
                        expanded = false
                    },
                    onOpenSettings = {
                        onOpenSettings()
                        expanded = false
                    },
                )
            }

            HandleButton(
                expanded = expanded,
                onClick = { expanded = !expanded },
            )
        }

        // No click-away scrim: the menu auto-collapses on row select (the chosen
        // "collapse on select" behaviour), and a full-screen scrim would defeat
        // the "always visible, non-intrusive" goal. Tapping the handle again also
        // collapses it.
    }
}

@Composable
private fun HandleButton(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "compare-handle-rotation",
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                shape = RoundedCornerShape(percent = 50),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CompareArrows,
            contentDescription = stringResource(ATMR.strings.reader_compare_handle),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alpha(if (expanded) 0.6f else 1f),
        )
    }
}

@Composable
private fun MenuColumn(
    hasTranslation: Boolean,
    showingTranslated: Boolean,
    onSelectOriginal: () -> Unit,
    onSelectTranslated: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val disabledAlpha = if (hasTranslation) 1f else 0.38f

    Column(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(vertical = 4.dp),
    ) {
        MenuRow(
            icon = Icons.Outlined.Image,
            label = stringResource(ATMR.strings.reader_compare_show_original),
            tint = if (hasTranslation && !showingTranslated) activeColor else inactiveColor,
            enabled = hasTranslation,
            onClick = onSelectOriginal,
        )
        MenuRow(
            icon = Icons.Outlined.Translate,
            label = stringResource(ATMR.strings.reader_compare_show_translated),
            tint = if (hasTranslation && showingTranslated) activeColor else inactiveColor,
            enabled = hasTranslation,
            onClick = onSelectTranslated,
        )
        MenuRow(
            icon = Icons.Outlined.Settings,
            label = stringResource(ATMR.strings.reader_compare_open_settings),
            tint = inactiveColor,
            enabled = true,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}
