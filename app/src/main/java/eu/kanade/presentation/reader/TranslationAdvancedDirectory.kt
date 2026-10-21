package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Advanced settings landing page: a compact directory instead of one long
 * technical list. Each row opens a category screen that reuses the exact
 * preference widgets that previously lived in the flat sheet. Data management
 * sits behind a divider so destructive operations stay visually separate.
 */
@Composable
internal fun ColumnScope.TranslationAdvancedDirectory(
    onOpenEngine: () -> Unit,
    onOpenOcr: () -> Unit,
    onOpenInpaint: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenData: () -> Unit,
) {
    DirectoryRow(
        icon = Icons.Outlined.SmartToy,
        title = stringResource(ATMR.strings.translation_engine_active_title),
        description = stringResource(ATMR.strings.translation_advanced_engine_summary),
        onClick = onOpenEngine,
    )
    DirectoryRow(
        icon = Icons.Outlined.DocumentScanner,
        title = stringResource(ATMR.strings.translation_advanced_ocr),
        description = stringResource(ATMR.strings.translation_advanced_ocr_summary),
        onClick = onOpenOcr,
    )
    DirectoryRow(
        icon = Icons.Outlined.AutoFixHigh,
        title = stringResource(ATMR.strings.translation_section_inpainting),
        description = stringResource(ATMR.strings.translation_advanced_inpaint_summary),
        onClick = onOpenInpaint,
    )
    DirectoryRow(
        icon = Icons.Outlined.Timeline,
        title = stringResource(ATMR.strings.translation_section_activity),
        description = stringResource(ATMR.strings.translation_advanced_activity_summary),
        onClick = onOpenActivity,
    )

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )

    DirectoryRow(
        icon = Icons.Outlined.DeleteSweep,
        title = stringResource(ATMR.strings.translation_section_data_management),
        description = stringResource(ATMR.strings.translation_advanced_data_summary),
        onClick = onOpenData,
    )
}

@Composable
private fun DirectoryRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    TextPreferenceWidget(
        title = title,
        subtitle = description,
        icon = icon,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        onPreferenceClick = onClick,
        widget = {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
