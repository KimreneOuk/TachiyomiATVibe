package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SwitchCard
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Every translation-facing toggle of the reader translation sheet: pipeline
 * activation plus the overlay affordances (floater, translated-text visibility,
 * translate button placement) that used to live in the reader settings sheet.
 */
@Composable
fun TranslationActivationSection(
    state: TranslationSettingsState,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onFloaterEnabledChange: (Boolean) -> Unit,
    onFloaterIdleFadeChange: (Boolean) -> Unit,
    onShowTranslatedTextChange: (Boolean) -> Unit,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
    onResetFloaterPosition: () -> Unit,
) {
    Column {
        SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_activation))

        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SwitchCard(
                icon = Icons.Outlined.Translate,
                title = stringResource(ATMR.strings.pref_translation_enabled),
                subtitle = stringResource(ATMR.strings.pref_translation_enabled_summary),
                checked = state.enabled,
                onCheckedChange = onTranslationEnabledChange,
            )
            SwitchCard(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(ATMR.strings.pref_auto_translate),
                subtitle = stringResource(ATMR.strings.pref_auto_translate_summary),
                checked = state.autoTranslate,
                onCheckedChange = { auto ->
                    if (auto && !state.enabled) onTranslationEnabledChange(true)
                    onAutoTranslateChange(auto)
                },
            )
        }

        SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_overlay))

        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SwitchCard(
                icon = Icons.Outlined.Layers,
                title = stringResource(ATMR.strings.pref_floater_show),
                subtitle = stringResource(ATMR.strings.reader_translation_floater_summary),
                checked = state.floaterEnabled,
                onCheckedChange = onFloaterEnabledChange,
            )
            if (state.floaterEnabled) {
                SwitchCard(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(ATMR.strings.pref_floater_idle_fade),
                    subtitle = stringResource(ATMR.strings.pref_floater_idle_fade_summary),
                    checked = state.floaterIdleFade,
                    onCheckedChange = onFloaterIdleFadeChange,
                )
                TranslationActionRow(
                    icon = Icons.Outlined.Refresh,
                    title = stringResource(ATMR.strings.pref_floater_reset_position),
                    subtitle = stringResource(ATMR.strings.pref_floater_reset_position_summary),
                    onClick = onResetFloaterPosition,
                )
            }
            SwitchCard(
                icon = Icons.Outlined.Visibility,
                title = stringResource(ATMR.strings.pref_show_translations),
                subtitle = stringResource(ATMR.strings.pref_show_translations_summary),
                checked = state.showTranslatedText,
                onCheckedChange = onShowTranslatedTextChange,
            )
            SwitchCard(
                icon = Icons.Outlined.Translate,
                title = stringResource(ATMR.strings.pref_reader_translate_button_mode),
                subtitle = stringResource(
                    if (state.translateButtonWithControls) {
                        ATMR.strings.pref_reader_translate_button_mode_summary_with_controls
                    } else {
                        ATMR.strings.pref_reader_translate_button_mode_summary_default
                    },
                ),
                checked = state.translateButtonWithControls,
                onCheckedChange = onTranslateButtonWithControlsChange,
            )
        }
    }
}

/** Tappable slate row without a switch (floater reset, data wipe entries). */
@Composable
internal fun TranslationActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val accent = if (danger) MaterialTheme.colorScheme.error else DesignTokens.AccentPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else DesignTokens.CardFill)
            .border(
                1.dp,
                if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else DesignTokens.CardBorder,
                DesignTokens.ShapeCard,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(DesignTokens.ShapeBadge)
                .background(DesignTokens.SheetBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else DesignTokens.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DesignTokens.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = DesignTokens.TextMuted,
                fontSize = 11.sp,
            )
        }
        Text(
            text = stringResource(ATMR.strings.action_reset).uppercase(),
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
