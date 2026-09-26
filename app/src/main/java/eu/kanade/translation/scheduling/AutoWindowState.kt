package eu.kanade.translation.scheduling

/**
 * TachiyomiAT: pure, transient contract for the rolling auto-translation window.
 *
 * These models define ONLY what the rolling coordinator intends to keep ready
 * and how that intent is observed. They carry no execution behavior, hold no
 * coroutines, and are NOT serialized — persisted durable state remains on
 * [eu.kanade.translation.model.PageTranslation]. A later ticket wires the
 * coordinator that reconciles page position, readiness, and memory/network
 * signals into these values and publishes an [AutoTranslationSnapshot].
 *
 * Equality is structural throughout, so re-emitting an equivalent snapshot
 * through a StateFlow produces no observably different value.
 */

/**
 * Chapter/session identity carried by every auto snapshot. Stable for the life
 * of one reader session; a chapter switch or reader reopen produces a new
 * identity so observers can distinguish disjoint windows.
 */
data class AutoChapterIdentity(
    val chapterId: Long,
    val sessionKey: String,
)

/**
 * Reason a target page is not being admitted right now. Surfaced to the reader
 * as a concise pause/deferral label rather than a fabricated percentage.
 *
 * [precedence] picks the dominant reason when several slots are deferred:
 * memory and network are the canonical pause causes shown first.
 */
enum class AutoDeferralReason(val precedence: Int) {
    LocalComputeBusy(precedence = 1),
    SourceUnavailable(precedence = 2),
    Network(precedence = 3),
    Memory(precedence = 4),
}

/**
 * Transient scheduling state for one page in the auto window. Distinct from the
 * durable [eu.kanade.translation.model.PageLifecycle] / stage model: a page
 * becomes [Queued] the moment the coordinator targets it, without writing a
 * persisted RUNNING stage. A stage becomes active here only at the executor
 * boundary where that work actually starts.
 *
 * [Ready] means a translated result can actually be displayed — the same gate
 * as [eu.kanade.translation.model.isTranslationDisplayReady]. Queued and
 * processing states never count as ready.
 */
sealed interface AutoSlotState {
    data object Queued : AutoSlotState
    data object ReadingText : AutoSlotState
    data object Cleaning : AutoSlotState
    data object Translating : AutoSlotState
    data object Rendering : AutoSlotState
    data object Ready : AutoSlotState

    data class Deferred(val reason: AutoDeferralReason) : AutoSlotState

    data class Failed(val retryable: Boolean) : AutoSlotState
}

/** Work is queued or actively executing through a real pipeline stage. */
val AutoSlotState.isActive: Boolean
    get() = this is AutoSlotState.Queued ||
        this is AutoSlotState.ReadingText ||
        this is AutoSlotState.Cleaning ||
        this is AutoSlotState.Translating ||
        this is AutoSlotState.Rendering

/** Work is past queue admission and inside a real pipeline stage. */
val AutoSlotState.isProcessing: Boolean
    get() = this is AutoSlotState.ReadingText ||
        this is AutoSlotState.Cleaning ||
        this is AutoSlotState.Translating ||
        this is AutoSlotState.Rendering

/** A translated, displayable result exists for this slot. */
val AutoSlotState.isReady: Boolean
    get() = this is AutoSlotState.Ready

/** The deferral reason if this slot is currently deferred, otherwise null. */
val AutoSlotState.deferralReason: AutoDeferralReason?
    get() = (this as? AutoSlotState.Deferred)?.reason

/**
 * One page slot in the rolling auto window. [pageIndex] is the 0-based reader
 * page index supplied by the reader page resolver.
 * Ordering is implied by position in [AutoTranslationSnapshot.orderedSlots]:
 * the foreground slot first, then ahead slots in ascending page index.
 */
data class AutoWindowSlot(
    val pageIndex: Int,
    val state: AutoSlotState,
)

/**
 * Pure desired-window calculation for a visible page [visiblePageIndex], a
 * configured ahead target [configuredAheadTarget] (the `N` pages the user asked
 * to keep ready AFTER the visible page), and a chapter of [pageCount] pages.
 * Produces the clamped ahead set without touching execution or storage.
 *
 * A configured value of `N` represents the next `N` pages AFTER the visible
 * page; the visible page is never counted as ahead. The window clamps cleanly
 * at chapter boundaries and reports the actual available target via
 * [availableAheadTarget].
 */
data class AutoWindowBounds(
    val visiblePageIndex: Int,
    val configuredAheadTarget: Int,
    val pageCount: Int,
) {
    init {
        require(visiblePageIndex >= 0) { "visiblePageIndex must be >= 0 but was $visiblePageIndex" }
        require(configuredAheadTarget >= 0) { "configuredAheadTarget must be >= 0 but was $configuredAheadTarget" }
        require(pageCount >= 0) { "pageCount must be >= 0 but was $pageCount" }
    }

    private val lastIndex: Int = pageCount - 1

    /**
     * The number of ahead pages actually available after clamping against the
     * chapter end. Equal to [configuredAheadTarget] when the chapter has enough
     * pages, and fewer (possibly zero) near the final page.
     */
    val availableAheadTarget: Int =
        if (pageCount == 0) {
            0
        } else {
            configuredAheadTarget.coerceAtMost((lastIndex - visiblePageIndex).coerceAtLeast(0))
        }

    /**
     * Ordered 0-based page indices of the ahead target: [visiblePageIndex] + 1
     * up to the clamped boundary. Empty at the last page, for an empty chapter,
     * or when [configuredAheadTarget] is zero.
     */
    val aheadPageIndices: List<Int> =
        if (availableAheadTarget == 0) {
            emptyList()
        } else {
            (visiblePageIndex + 1..visiblePageIndex + availableAheadTarget).toList()
        }

    /** True when the visible page is inside the chapter bounds. */
    val hasVisiblePage: Boolean get() = pageCount > 0 && visiblePageIndex <= lastIndex
}

/**
 * Aggregate activity for the whole window, derived from the per-slot states.
 * Kept distinct from per-slot [AutoSlotState] so the reader can render one
 * compact status ("Auto · 3 ahead ready" / "Auto paused · Low memory") without
 * re-deriving it from the slot list.
 */
enum class AutoActivityStatus {
    /** Target slots are queued or executing through a real pipeline stage. */
    Working,

    /** No active progress; at least one slot is deferred (see active deferral). */
    Paused,

    /** Visible page and every available ahead slot are display-ready. */
    FullyReady,

    /** No active, deferred, or fully-ready work (e.g. only failed/stuck slots). */
    Idle,
}

/**
 * Immutable, value-only snapshot of the rolling auto window for one chapter
 * session, published as a StateFlow by the coordinator and observed by the
 * reader.
 *
 * Equality is structural across every field, so feeding an equivalent snapshot
 * back through a StateFlow is a no-op for `distinctUntilChanged` and Compose
 * collection — re-emitting an equivalent value creates no observably different
 * state. Derived values ([readyAheadCount], [status], …) are deterministic
 * functions of the constructor fields and therefore can never diverge.
 *
 * The visible foreground page is represented separately from the ordered ahead
 * slots: [readyAheadCount] and the ahead target count only pages AFTER the
 * visible page, never the visible page itself.
 *
 * All fields are values (no object identity, no bitmaps, no coroutine state),
 * so the snapshot is safe to retain across recomposition and process frames.
 */
data class AutoTranslationSnapshot(
    val identity: AutoChapterIdentity,
    val visiblePageIndex: Int,
    val configuredAheadTarget: Int,
    val availableAheadTarget: Int,
    val foreground: AutoWindowSlot?,
    val aheadSlots: List<AutoWindowSlot>,
    /** Monotonic scheduler owner/store version; changes on every owner replacement. */
    val ownerVersion: Long = 0L,
    /** Monotonic desired-window version; changes synchronously with navigation anchors. */
    val windowVersion: Long = 0L,
) {
    init {
        require(visiblePageIndex >= 0) { "visiblePageIndex must be >= 0 but was $visiblePageIndex" }
        require(configuredAheadTarget >= 0) { "configuredAheadTarget must be >= 0 but was $configuredAheadTarget" }
        require(availableAheadTarget >= 0) { "availableAheadTarget must be >= 0 but was $availableAheadTarget" }
        require(availableAheadTarget <= configuredAheadTarget) {
            "availableAheadTarget ($availableAheadTarget) must not exceed configuredAheadTarget ($configuredAheadTarget)"
        }
        require(aheadSlots.size <= availableAheadTarget) {
            "aheadSlots size (${aheadSlots.size}) must not exceed availableAheadTarget ($availableAheadTarget)"
        }
        require(foreground == null || foreground.pageIndex == visiblePageIndex) {
            "foreground pageIndex (${foreground?.pageIndex}) must equal visiblePageIndex ($visiblePageIndex)"
        }
        require(aheadSlots.all { it.pageIndex > visiblePageIndex }) {
            "aheadSlots must not include the visible page ($visiblePageIndex)"
        }
        require(
            aheadSlots.indices.all { i -> i == 0 || aheadSlots[i - 1].pageIndex < aheadSlots[i].pageIndex },
        ) { "aheadSlots must be ordered by strictly increasing pageIndex" }
    }

    /** Every slot in deterministic order: foreground first, then ahead by index. */
    val orderedSlots: List<AutoWindowSlot>
        get() = if (foreground != null) listOf(foreground) + aheadSlots else aheadSlots

    /**
     * Display-ready ahead pages only. Queued, processing, deferred, and failed
     * ahead slots never count. The visible page is never counted here even when
     * it is ready.
     */
    val readyAheadCount: Int
        get() = aheadSlots.count { it.state.isReady }

    /** Ahead slots currently queued or executing through a real stage. */
    val activeAheadCount: Int
        get() = aheadSlots.count { it.state.isActive }

    /** Ahead slots that have failed and may or may not be retried. */
    val failedAheadCount: Int
        get() = aheadSlots.count { it.state is AutoSlotState.Failed }

    private val foregroundComplete: Boolean
        get() = foreground == null || foreground.state.isReady

    /**
     * The dominant deferral reason across the window, or null when no slot is
     * deferred. Higher [AutoDeferralReason.precedence] wins so memory and
     * network pauses surface first.
     */
    val activeDeferral: AutoDeferralReason?
        get() = orderedSlots
            .mapNotNull { it.state.deferralReason }
            .maxByOrNull { it.precedence }

    /**
     * Aggregate window status. Working wins whenever any slot is queued or
     * executing; otherwise a deferral yields Paused; otherwise FullyReady when
     * the visible page and every available ahead slot are ready; else Idle.
     */
    val status: AutoActivityStatus
        get() = when {
            orderedSlots.any { it.state.isActive } -> AutoActivityStatus.Working
            activeDeferral != null -> AutoActivityStatus.Paused
            foregroundComplete && readyAheadCount >= availableAheadTarget -> AutoActivityStatus.FullyReady
            else -> AutoActivityStatus.Idle
        }
}
