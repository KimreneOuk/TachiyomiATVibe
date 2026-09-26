package eu.kanade.translation.engines.translator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The lane phase whose occupancy is being reported to the reader. */
enum class NativeStallPhase {
    NATIVE_LANE,
}

/**
 * Bounded, non-terminal observation of a native lane that outlived its result
 * timer. It is intentionally separate from page stage status: the invocation
 * remains alive in [eu.kanade.translation.scheduling.NativeRunQuarantine] until
 * its real exit, while this state refuses new interactive promises.
 */
data class NativeStallState(
    val token: Long,
    val pageKey: String,
    val startedAtEpochMs: Long,
    val stalledAtEpochMs: Long,
    val phase: NativeStallPhase = NativeStallPhase.NATIVE_LANE,
)

/**
 * One-shot observer for one native-lane occupancy.
 *
 * [onLaneOccupied] and [onLaneReleased] are called by the quarantine while it
 * holds its admission mutex. The watchdog never acquires that mutex and owns
 * only one pending timer/state, so a second occupancy replaces the first.
 */
class NativeStallWatchdog(
    private val scope: CoroutineScope,
    private val thresholdMs: Long,
    private val clock: Clock = SystemClock,
) {
    /** Injectable clock seam used by the pure virtual-time test. */
    interface Clock {
        fun nowEpochMs(): Long
        suspend fun delay(millis: Long)
    }

    private object SystemClock : Clock {
        override fun nowEpochMs(): Long = System.currentTimeMillis()
        override suspend fun delay(millis: Long) = kotlinx.coroutines.delay(millis)
    }

    private val _state = MutableStateFlow<NativeStallState?>(null)
    val state: StateFlow<NativeStallState?> = _state

    @Volatile
    private var occupiedToken = NO_TOKEN

    @Volatile
    private var timer: Job? = null

    /** Called inside the quarantine's admission lock when the lane is granted. */
    fun onLaneOccupied(token: Long, pageKey: String, startedAtEpochMs: Long) {
        timer?.cancel()
        occupiedToken = token
        _state.value = null
        timer = scope.launch {
            clock.delay(thresholdMs)
            if (occupiedToken == token) {
                _state.value = NativeStallState(
                    token = token,
                    pageKey = pageKey,
                    startedAtEpochMs = startedAtEpochMs,
                    stalledAtEpochMs = clock.nowEpochMs(),
                )
            }
        }
    }

    /** Called inside the quarantine's admission lock after the real exit. */
    fun onLaneReleased(token: Long) {
        if (occupiedToken != token) return
        occupiedToken = NO_TOKEN
        timer?.cancel()
        timer = null
        _state.value = null
    }

    private companion object {
        const val NO_TOKEN = Long.MIN_VALUE
    }
}
