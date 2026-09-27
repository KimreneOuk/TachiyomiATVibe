package eu.kanade.tachiyomi.ui.reader

import androidx.compose.runtime.Immutable
import eu.kanade.translation.presentation.PageUiTruth
import eu.kanade.translation.presentation.TranslationUiTruth
import eu.kanade.translation.scheduling.AutoActivityStatus
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.AutoDeferralReason
import eu.kanade.translation.scheduling.AutoSlotState
import eu.kanade.translation.scheduling.AutoTranslationSnapshot
import eu.kanade.translation.scheduling.AutoWindowSlot

/**
 * Reader-facing state for one rolling auto-translation window.
 *
 * This is a projection of the live coordinator snapshot, not a reduction of
 * the chapter-wide durable store. The foreground slot is kept separate from
 * the ordered ahead slots so a background page can never replace the visible
 * page's stage or inflate the ready-ahead numerator.
 */
@Immutable
data class ReaderAutoTranslationUiState(
    val identity: AutoChapterIdentity?,
    val visiblePageIndex: Int?,
    val configuredAheadTarget: Int,
    val availableAheadTarget: Int,
    val foreground: ReaderAutoTranslationSlot?,
    val aheadSlots: List<ReaderAutoTranslationSlot>,
    val orderedSlots: List<ReaderAutoTranslationSlot>,
    val readyAheadCount: Int,
    val activity: AutoActivityStatus,
    val pauseReason: AutoDeferralReason?,
    val failedAheadCount: Int,
    /** Scheduler owner/store generation accepted by the Reader collector. */
    val ownerVersion: Long? = null,
    /** Monotonic desired-window version within [ownerVersion]. */
    val windowVersion: Long = 0L,
) {
    companion object {
        fun empty(identity: AutoChapterIdentity? = null): ReaderAutoTranslationUiState =
            ReaderAutoTranslationUiState(
                identity = identity,
                visiblePageIndex = null,
                configuredAheadTarget = 0,
                availableAheadTarget = 0,
                foreground = null,
                aheadSlots = emptyList(),
                orderedSlots = emptyList(),
                readyAheadCount = 0,
                activity = AutoActivityStatus.Idle,
                pauseReason = null,
                failedAheadCount = 0,
                ownerVersion = null,
                windowVersion = 0L,
            )
    }
}

@Immutable
data class ReaderAutoTranslationSlot(
    val pageIndex: Int,
    val state: ReaderAutoTranslationSlotState,
    /**
     *  P5 (spec §0.2.2, §6.2.8): the slot's shared truth from the pure
     * [TranslationUiTruth.forAutoSlot] mapper, produced by [toReaderSlot] —
     * the status surface renders this record instead of reinterpreting the
     * slot state. The default only covers direct constructions (tests); the
     * projection always supplies the mapped truth.
     */
    val truth: PageUiTruth = TranslationUiTruth.forAutoSlot(AutoSlotState.Queued),
)

/**
 * UI-safe copy of the transient coordinator state. Keeping the mapping
 * explicit prevents a queued page from being presented as an active OCR
 * stage and preserves the typed memory/network/source deferral reasons. The
 * current production coordinator emits Memory and SourceUnavailable; Network
 * and LocalComputeBusy remain mapped here for future producers and are not
 * fabricated by the reader.
 */
@Immutable
sealed interface ReaderAutoTranslationSlotState {
    data object Queued : ReaderAutoTranslationSlotState
    data object ReadingText : ReaderAutoTranslationSlotState
    data object Cleaning : ReaderAutoTranslationSlotState
    data object Translating : ReaderAutoTranslationSlotState
    data object Rendering : ReaderAutoTranslationSlotState
    data object Ready : ReaderAutoTranslationSlotState
    data class Deferred(val reason: AutoDeferralReason) : ReaderAutoTranslationSlotState
    data class Failed(val retryable: Boolean) : ReaderAutoTranslationSlotState
}

/**
 * Drops a snapshot that belongs to another chapter/session. A null snapshot
 * (auto disabled, reader closed, or coordinator shutdown) also resets the
 * projection while retaining the expected identity for lifecycle rebinding.
 */
fun projectReaderAutoTranslationUiState(
    snapshot: AutoTranslationSnapshot?,
    expectedIdentity: AutoChapterIdentity?,
): ReaderAutoTranslationUiState {
    if (snapshot == null || expectedIdentity == null || snapshot.identity != expectedIdentity) {
        return ReaderAutoTranslationUiState.empty(expectedIdentity)
    }

    val foreground = snapshot.foreground?.toReaderSlot()
    val aheadSlots = snapshot.aheadSlots.map { it.toReaderSlot() }
    return ReaderAutoTranslationUiState(
        identity = snapshot.identity,
        visiblePageIndex = snapshot.visiblePageIndex,
        configuredAheadTarget = snapshot.configuredAheadTarget,
        availableAheadTarget = snapshot.availableAheadTarget,
        foreground = foreground,
        aheadSlots = aheadSlots,
        orderedSlots = snapshot.orderedSlots.map { it.toReaderSlot() },
        readyAheadCount = snapshot.readyAheadCount,
        activity = snapshot.status,
        pauseReason = snapshot.activeDeferral,
        failedAheadCount = snapshot.failedAheadCount,
        ownerVersion = snapshot.ownerVersion,
        windowVersion = snapshot.windowVersion,
    )
}

/**
 * Collector gate for the live switching snapshot stream. A null snapshot is a valid lifecycle
 * reset; a non-null snapshot from another chapter/session must be ignored rather than clearing a
 * still-valid active projection.
 */
internal fun isReaderAutoTranslationSnapshotForIdentity(
    snapshot: AutoTranslationSnapshot?,
    expectedIdentity: AutoChapterIdentity?,
): Boolean = snapshot == null || (expectedIdentity != null && snapshot.identity == expectedIdentity)

/**
 * Accepts only a snapshot that is not older than the last Reader publication. The scheduler's
 * owner version wins over the per-owner window version, so a replacement store may reset its
 * window counter without allowing the previous owner to regress the projection.
 */
internal fun isReaderAutoTranslationSnapshotVersionAccepted(
    snapshot: AutoTranslationSnapshot?,
    expectedIdentity: AutoChapterIdentity?,
    acceptedOwnerVersion: Long?,
    acceptedWindowVersion: Long,
    requireStrictlyNew: Boolean = false,
): Boolean {
    if (snapshot == null) return true
    if (expectedIdentity == null || snapshot.identity != expectedIdentity) return false
    val ownerVersion = acceptedOwnerVersion ?: return true
    return snapshot.ownerVersion > ownerVersion ||
        (
            snapshot.ownerVersion == ownerVersion &&
                (
                    if (requireStrictlyNew) {
                        snapshot.windowVersion > acceptedWindowVersion
                    } else {
                        snapshot.windowVersion >= acceptedWindowVersion
                    }
                    )
            )
}

private fun AutoWindowSlot.toReaderSlot(): ReaderAutoTranslationSlot =
    ReaderAutoTranslationSlot(
        pageIndex = pageIndex,
        state = state.toReaderState(),
        //  P5 (spec §6.2.8): the shared rolling-auto slot truth, produced
        // ONLY by the pure forAutoSlot mapper — the status surface projects
        // this record instead of reinterpreting the slot state.
        truth = TranslationUiTruth.forAutoSlot(state),
    )

private fun AutoSlotState.toReaderState(): ReaderAutoTranslationSlotState = when (this) {
    AutoSlotState.Queued -> ReaderAutoTranslationSlotState.Queued
    AutoSlotState.ReadingText -> ReaderAutoTranslationSlotState.ReadingText
    AutoSlotState.Cleaning -> ReaderAutoTranslationSlotState.Cleaning
    AutoSlotState.Translating -> ReaderAutoTranslationSlotState.Translating
    AutoSlotState.Rendering -> ReaderAutoTranslationSlotState.Rendering
    AutoSlotState.Ready -> ReaderAutoTranslationSlotState.Ready
    is AutoSlotState.Deferred -> ReaderAutoTranslationSlotState.Deferred(reason)
    is AutoSlotState.Failed -> ReaderAutoTranslationSlotState.Failed(retryable)
}
