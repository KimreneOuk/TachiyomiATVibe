package eu.kanade.presentation.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Compact contextual action menu anchored above the navigation dock.
 * Every entry dispatches an existing reader action; toggles render their
 * current state with a trailing check. Dismisses when an item is picked or
 * when the reader controls hide.
 */
@Composable
fun ReaderQuickActionsMenu(
    visible: Boolean,
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    fullscreen: Boolean,
    onClickFullscreen: () -> Unit,
    onClickSettings: () -> Unit,
    onClickTranslationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + expandVertically(tween(150), expandFrom = Alignment.Bottom),
        exit = fadeOut(tween(90)) + shrinkVertically(tween(120), shrinkTowards = Alignment.Bottom),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme
                        .surfaceColorAtElevation(3.dp)
                        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f),
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MenuActionRow(
                icon = Icons.Outlined.Settings,
                label = stringResource(MR.strings.action_settings),
                onClick = onClickSettings,
            )
            MenuActionRow(
                icon = Icons.Outlined.Translate,
                label = stringResource(ATMR.strings.reader_compare_open_settings),
                onClick = onClickTranslationSettings,
            )
            MenuActionRow(
                iconPainter = painterResource(readingMode.iconRes),
                label = stringResource(MR.strings.viewer),
                onClick = onClickReadingMode,
            )
            MenuActionRow(
                icon = Icons.Outlined.Rotate90DegreesCcw,
                label = stringResource(MR.strings.rotation_type),
                onClick = onClickOrientation,
            )
            MenuActionRow(
                icon = Icons.Outlined.Crop,
                label = stringResource(MR.strings.pref_crop_borders),
                checked = cropEnabled,
                onClick = onClickCropBorder,
            )
            MenuActionRow(
                icon = Icons.Outlined.Fullscreen,
                label = stringResource(MR.strings.pref_fullscreen),
                checked = fullscreen,
                onClick = onClickFullscreen,
            )
        }
    }
}

@Composable
private fun MenuActionRow(
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    label: String,
    checked: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            iconPainter != null -> Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (checked != null) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
