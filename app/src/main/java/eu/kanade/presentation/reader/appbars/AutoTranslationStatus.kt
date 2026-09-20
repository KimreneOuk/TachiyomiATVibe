package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderAutoTranslationSlotState
import eu.kanade.tachiyomi.ui.reader.ReaderAutoTranslationUiState
import eu.kanade.translation.scheduling.AutoActivityStatus
import eu.kanade.translation.scheduling.AutoDeferralReason
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private val AutoStatusShape = RoundedCornerShape(12.dp)

val ReaderAutoTranslationUiState.hasVisibleAutoFeedback: Boolean
    get() = identity != null &&
        (
            configuredAheadTarget > 0 ||
                foreground != null ||
                aheadSlots.isNotEmpty() ||
                pauseReason != null ||
                failedAheadCount > 0
            )

/** Pure reader status projection used by the composable and presentation tests. */
data class ReaderAutoTranslationStatusSnapshot(
    val readyAheadCount: Int,
    val availableAheadTarget: Int,
    val orderedSlotPageIndices: List<Int>,
)

fun ReaderAutoTranslationUiState.toAutoTranslationStatusSnapshot(): ReaderAutoTranslationStatusSnapshot {
    val available = availableAheadTarget.coerceAtLeast(0)
    return ReaderAutoTranslationStatusSnapshot(
        readyAheadCount = readyAheadCount.coerceIn(0, available),
        availableAheadTarget = available,
        orderedSlotPageIndices = orderedSlots.map { it.pageIndex },
    )
}

/**
 * Compact status for the bottom reader bar and the controls-hidden rail. The
 * numerator is always [ReaderAutoTranslationUiState.readyAheadCount], which
 * excludes queued, processing, deferred, failed, and visible-page slots.
 */
@Composable
fun AutoTranslationStatus(
    state: ReaderAutoTranslationUiState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!state.hasVisibleAutoFeedback) return

    val status = state.toAutoTranslationStatusSnapshot()
    val available = status.availableAheadTarget
    val ready = status.readyAheadCount
    val summary = when {
        state.pauseReason != null -> stringResource(
            ATMR.strings.reader_auto_status_paused,
            state.pauseReason.localizedLabel,
            ready,
            available,
        )
        state.failedAheadCount > 0 -> stringResource(
            ATMR.strings.reader_auto_status_failed,
            ready,
            available,
            state.failedAheadCount,
        )
        state.activity == AutoActivityStatus.Working && ready == 0 && available > 0 -> stringResource(
            ATMR.strings.reader_auto_status_preparing,
            available,
        )
        state.activity == AutoActivityStatus.Working && state.foreground != null && available == 0 ->
            stringResource(ATMR.strings.reader_auto_status_current)
        state.activity == AutoActivityStatus.FullyReady && available > 0 -> stringResource(
            ATMR.strings.reader_auto_status_all_ready,
            ready,
        )
        available > 0 -> stringResource(ATMR.strings.reader_auto_status_ready, ready, available)
        else -> stringResource(ATMR.strings.reader_auto_status_ready_empty)
    }

    val slotLabelsBuilder = StringBuilder()
    for (index in state.orderedSlots.indices) {
        val slot = state.orderedSlots[index]
        if (index > 0) slotLabelsBuilder.append(", ")
        //  P5: the shared forAutoSlot mapper owns the slot copy — the
        // status surface projects the truth record verbatim (spec §6.2.8).
        slotLabelsBuilder
            .append(slot.pageIndex + 1)
            .append(": ")
            .append(slot.truth.label)
    }
    val slotLabels = slotLabelsBuilder.toString()
    Surface(
        modifier = modifier
            .widthIn(max = if (compact) 260.dp else 320.dp)
            .clearAndSetSemantics {
                contentDescription = if (slotLabels.isBlank()) summary else "$summary. $slotLabels"
            },
        shape = AutoStatusShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            SlotRail(
                state = state,
                compact = compact,
            )
        }
    }
}

@Composable
private fun SlotRail(
    state: ReaderAutoTranslationUiState,
    compact: Boolean,
) {
    if (state.orderedSlots.isEmpty()) return
    Row(
        modifier = Modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (slot in state.orderedSlots) {
            Box(
                modifier = Modifier
                    .size(if (compact) 7.dp else 9.dp)
                    .clip(CircleShape)
                    .background(slot.state.indicatorColor),
            )
        }
    }
}

private val AutoDeferralReason.localizedLabel: String
    @Composable get() = when (this) {
        AutoDeferralReason.Memory -> stringResource(ATMR.strings.reader_auto_reason_memory)
        AutoDeferralReason.Network -> stringResource(ATMR.strings.reader_auto_reason_network)
        AutoDeferralReason.SourceUnavailable -> stringResource(ATMR.strings.reader_auto_reason_source)
        AutoDeferralReason.LocalComputeBusy -> stringResource(ATMR.strings.reader_auto_reason_compute)
    }

private val ReaderAutoTranslationSlotState.indicatorColor: Color
    @Composable get() = when (this) {
        ReaderAutoTranslationSlotState.Queued -> MaterialTheme.colorScheme.outline
        ReaderAutoTranslationSlotState.ReadingText,
        ReaderAutoTranslationSlotState.Cleaning,
        ReaderAutoTranslationSlotState.Translating,
        ReaderAutoTranslationSlotState.Rendering,
        -> MaterialTheme.colorScheme.primary
        ReaderAutoTranslationSlotState.Ready -> MaterialTheme.colorScheme.tertiary
        is ReaderAutoTranslationSlotState.Deferred -> MaterialTheme.colorScheme.secondary
        is ReaderAutoTranslationSlotState.Failed -> MaterialTheme.colorScheme.error
    }
