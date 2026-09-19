package eu.kanade.translation.orchestration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single admission owner for reader and chapter-batch translation work.
 *
 * Admission is deliberately small and synchronous. It decides ownership, but
 * it does not wait for native work or perform cancellation; the quiescent
 * BATCH -> READER transition is layered on top by the next phase. Keeping the
 * state transition atomic makes every caller observe the same answer even when
 * lifecycle callbacks and UI actions arrive on different dispatchers.
 */
class TranslationSessionCoordinator(
    private val onBatchSwitchRequested: (() -> Unit)? = null,
) {

    private val lock = Any()
    private val _state = MutableStateFlow(TranslationSessionState.IDLE)

    val state: StateFlow<TranslationSessionState> = _state.asStateFlow()

    /**
     * Admit reader-owned work. A reader request during a batch is rejected
     * until the UI explicitly confirms a switch; the confirmed P3-01 path
     * performs the existing non-blocking pause callback and transfers the
     * owner. P3-02 replaces that callback with a joined transition.
     */
    fun requestReaderSession(intent: ReaderSessionIntent): SessionAdmission {
        var invokeBatchPause = false
        val admission = synchronized(lock) {
            when (_state.value) {
                TranslationSessionState.IDLE -> {
                    _state.value = TranslationSessionState.READER_SESSION
                    SessionAdmission.Admitted(TranslationSessionState.READER_SESSION)
                }

                TranslationSessionState.READER_SESSION -> {
                    SessionAdmission.Admitted(TranslationSessionState.READER_SESSION)
                }

                TranslationSessionState.BATCH_SESSION -> {
                    if (!intent.confirmBatchSwitch) {
                        SessionAdmission.Rejected(SessionRejection.BATCH_ACTIVE)
                    } else {
                        _state.value = TranslationSessionState.READER_SESSION
                        invokeBatchPause = true
                        SessionAdmission.Switched(TranslationSessionState.BATCH_SESSION)
                    }
                }

                TranslationSessionState.PAUSING -> {
                    SessionAdmission.Rejected(SessionRejection.PAUSING_IN_PROGRESS)
                }
            }
        }
        if (invokeBatchPause) onBatchSwitchRequested?.invoke()
        return admission
    }

    /** Admit batch-owned work, or return a typed rejection for an active reader. */
    fun requestBatchSession(intent: BatchSessionIntent): SessionAdmission = synchronized(lock) {
        when (_state.value) {
            TranslationSessionState.IDLE -> {
                _state.value = TranslationSessionState.BATCH_SESSION
                SessionAdmission.Admitted(TranslationSessionState.BATCH_SESSION)
            }

            TranslationSessionState.BATCH_SESSION -> {
                SessionAdmission.Admitted(TranslationSessionState.BATCH_SESSION)
            }

            TranslationSessionState.PAUSING -> {
                SessionAdmission.Rejected(SessionRejection.PAUSING_IN_PROGRESS)
            }

            TranslationSessionState.READER_SESSION -> {
                SessionAdmission.Rejected(SessionRejection.READER_ACTIVE)
            }
        }
    }

    /** Enter the explicit quiescing state for a batch-to-reader transition. */
    fun beginPausing(): Boolean = synchronized(lock) {
        if (_state.value != TranslationSessionState.BATCH_SESSION) return false
        _state.value = TranslationSessionState.PAUSING
        true
    }

    /** Complete a future joined batch pause and transfer ownership to the reader. */
    fun completePausingForReader(): Boolean = synchronized(lock) {
        if (_state.value != TranslationSessionState.PAUSING) return false
        _state.value = TranslationSessionState.READER_SESSION
        true
    }

    /** Release the current owner. An optional expected state prevents stale teardown callbacks. */
    fun finishSession(expectedState: TranslationSessionState? = null) {
        synchronized(lock) {
            if (expectedState == null || _state.value == expectedState) {
                _state.value = TranslationSessionState.IDLE
            }
        }
    }
}

enum class TranslationSessionState {
    IDLE,
    BATCH_SESSION,
    PAUSING,
    READER_SESSION,
}

data class ReaderSessionIntent(
    val chapterId: Long? = null,
    val confirmBatchSwitch: Boolean = false,
)

data class BatchSessionIntent(
    val chapterIds: Set<Long> = emptySet(),
)

sealed interface SessionAdmission {
    data class Admitted(val state: TranslationSessionState) : SessionAdmission

    data class Switched(val previous: TranslationSessionState) : SessionAdmission

    data class Rejected(val reason: SessionRejection) : SessionAdmission
}

enum class SessionRejection {
    BATCH_ACTIVE,
    PAUSING_IN_PROGRESS,
    READER_ACTIVE,
}
