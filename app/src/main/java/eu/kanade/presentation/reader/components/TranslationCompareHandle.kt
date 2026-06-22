package eu.kanade.presentation.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

// TachiyomiAT: idle auto-hide delay. After this many ms with the menu collapsed
// and no interaction, the handle dims to 0.4f and slides -20.dp off the left
// edge. Any tap resets it.
private const val IDLE_DELAY_MS = 2500L

/**
 * TachiyomiAT: a side-mounted handle that lets the user quickly compare the
 * original and translated image for the CURRENT page only, open the full
 * translation settings sheet, or delete the current chapter's translation.
 *
 * Layout: a small circular handle docked to the left edge, vertically centered.
 * Tapping it expands a short vertical menu (Show original / Show translated /
 * Translation settings / Delete translation); selecting a row collapses it
 * again and, for the two toggle rows, flips [showingTranslated] for the current
 * page via [onSelectOriginal] / [onSelectTranslated]. The delete row calls
 * [onDeleteTranslation] (tinted with the theme's error color).
 *
 * Visibility: the handle is present whenever [visible] is true. When the user
 * has not interacted with it for ~2.5s and the menu is collapsed, it dims to
 * 0.4f alpha and slides halfway off the left edge (-20.dp) to stay
 * non-intrusive during idle reading; any tap on the screen or handle resets it
 * to full opacity and flush position. An expanded menu is always full-opacity.
 *
 * The Original/Translated rows are disabled (greyed) when [hasTranslation] is
 * false (a page with no translation yet); the Delete row is hidden entirely
 * when [hasTranslation] is false (nothing to delete). Only Translation settings
 * stays always tappable. The whole handle is hidden when translation is
 * disabled entirely.
 *
 * Active-state highlighting reflects [showingTranslated]: whichever side is
 * currently shown is tinted with the primary color.
 *
 * @param visible           whether to show the handle at all (translation enabled).
 * @param hasTranslation    whether the current page has a translated image to compare/delete.
 * @param showingTranslated whether the current page is currently showing its translation.
 * @param onSelectOriginal  flip the current page to its original image.
 * @param onSelectTranslated flip the current page to its translated image.
 * @param onOpenSettings    open the reader translation settings sheet.
 * @param onDeleteTranslation delete the current chapter's translation (destructive).
 */
@Composable
fun TranslationCompareHandle(
    visible: Boolean,
    hasTranslation: Boolean,
    showingTranslated: Boolean,
    onSelectOriginal: () -> Unit,
    onSelectTranslated: () -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteTranslation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    var expanded by remember { mutableStateOf(false) }
    // TachiyomiAT: idle auto-hide. After ~2.5s of no interaction with the menu
    // collapsed, dim + slide the handle off-edge so it stays non-intrusive
    // during idle reading. Any tap resets it.
    var isIdle by remember { mutableStateOf(false) }
    // Restart the idle timer whenever the menu closes or the handle is tapped.
    // While expanded we never go idle (the menu is always full-opacity).
    LaunchedEffect(expanded) {
        if (!expanded) {
            delay(IDLE_DELAY_MS)
            isIdle = true
        } else {
            isIdle = false
        }
    }

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
                        isIdle = false
                    },
                    onSelectTranslated = {
                        onSelectTranslated()
                        expanded = false
                        isIdle = false
                    },
                    onOpenSettings = {
                        onOpenSettings()
                        expanded = false
                        isIdle = false
                    },
                    onDeleteTranslation = {
                        onDeleteTranslation()
                        expanded = false
                        isIdle = false
                    },
                )
            }

            HandleButton(
                expanded = expanded,
                isIdle = isIdle && !expanded,
                onClick = {
                    expanded = !expanded
                    isIdle = false
                },
            )
        }

        // Tap anywhere on the handle area resets idle; the LaunchedEffect below
        // handles the timeout. No click-away scrim: the menu auto-collapses on
        // row select (the chosen "collapse on select" behaviour), and a
        // full-screen scrim would defeat the "non-intrusive when idle" goal.
        // Tapping the handle again also collapses it.
    }
}

@Composable
private fun HandleButton(
    expanded: Boolean,
    isIdle: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "compare-handle-rotation",
    )
    // TachiyomiAT: idle auto-hide — dim to 0.4f and slide -20.dp off the left
    // edge when idle and the menu is collapsed. Recovered on any tap.
    val handleAlpha by animateFloatAsState(
        targetValue = if (isIdle) 0.4f else 1f,
        animationSpec = tween(180),
        label = "compare-handle-alpha",
    )
    val handleOffsetX by animateDpAsState(
        targetValue = if (isIdle) (-20).dp else 0.dp,
        animationSpec = tween(180),
        label = "compare-handle-offset-x",
    )
    Box(
        modifier = Modifier
            .offset(x = handleOffsetX)
            .alpha(handleAlpha)
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
    onDeleteTranslation: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
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
        // TachiyomiAT: delete is a destructive action — tint with the theme's
        // error color and only show it when there is a translation to delete.
        if (hasTranslation) {
            MenuRow(
                icon = Icons.Outlined.Delete,
                label = stringResource(ATMR.strings.reader_compare_delete_translation),
                tint = errorColor,
                enabled = true,
                onClick = onDeleteTranslation,
            )
        }
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
