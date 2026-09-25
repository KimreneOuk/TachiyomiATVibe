package eu.kanade.translation.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

    /**
     * Perform the joined BATCH -> READER transition. The caller supplies the
     * non-blocking pause and the batch-job join so this coordinator never
     * reaches through manager, reader, or store locks. A timed-out join leaves
     * the state in PAUSING and retries forever; no reader admission is returned
     * until the old batch has genuinely unwound.
     */
    suspend fun switchBatchToReader(
        intent: ReaderSessionIntent,
        pauseBatch: () -> Unit,
        joinBatch: suspend (timeoutMs: Long) -> Boolean,
        timeoutMs: Long = 3_000L,
        retryDelayMs: Long = 2_000L,
        onJoinTimeout: (timeoutMs: Long) -> Unit = {},
    ): SessionAdmission {
        if (!intent.confirmBatchSwitch) return requestReaderSession(intent)

        val previous = synchronized(lock) {
            when (_state.value) {
                TranslationSessionState.IDLE -> {
                    _state.value = TranslationSessionState.READER_SESSION
                    return@synchronized null
                }

                TranslationSessionState.READER_SESSION -> return@synchronized null
                TranslationSessionState.PAUSING -> {
                    return@synchronized TranslationSessionState.PAUSING
                }

                TranslationSessionState.BATCH_SESSION -> {
                    _state.value = TranslationSessionState.PAUSING
                    TranslationSessionState.BATCH_SESSION
                }
            }
        }

        if (previous == null) return SessionAdmission.Admitted(TranslationSessionState.READER_SESSION)
        if (previous == TranslationSessionState.PAUSING) {
            return SessionAdmission.Rejected(SessionRejection.PAUSING_IN_PROGRESS)
        }

        try {
            pauseBatch()
            while (true) {
                if (synchronized(lock) { _state.value != TranslationSessionState.PAUSING }) {
                    return SessionAdmission.Rejected(SessionRejection.PAUSING_IN_PROGRESS)
                }
                if (joinBatch(timeoutMs)) {
                    synchronized(lock) {
                        if (_state.value == TranslationSessionState.PAUSING) {
                            _state.value = TranslationSessionState.READER_SESSION
                            return SessionAdmission.Switched(TranslationSessionState.BATCH_SESSION)
                        }
                    }
                    return SessionAdmission.Rejected(SessionRejection.PAUSING_IN_PROGRESS)
                }
                onJoinTimeout(timeoutMs)
                delay(retryDelayMs)
            }
        } catch (cancelled: CancellationException) {
            // A reader teardown can cancel the transition while the native
            // batch is still unwinding. Restore the batch owner rather than
            // leaving the global admission gate stranded in PAUSING.
            synchronized(lock) {
                if (_state.value == TranslationSessionState.PAUSING) {
                    _state.value = TranslationSessionState.BATCH_SESSION
                }
            }
            throw cancelled
        }
    }

    /**
     * Abort an in-flight reader handoff when the reader is torn down. The
     * batch remains the owner; a still-running switch coroutine observes the
     * state change and cannot publish a reader session after teardown.
     */
    fun abortPausingToBatch(): Boolean = synchronized(lock) {
        if (_state.value != TranslationSessionState.PAUSING) return false
        _state.value = TranslationSessionState.BATCH_SESSION
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
