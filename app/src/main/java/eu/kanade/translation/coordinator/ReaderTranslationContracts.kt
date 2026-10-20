package eu.kanade.translation.coordinator

import eu.kanade.translation.model.SinglePageOutcome
import java.io.InputStream

enum class ReaderTrigger { MANUAL_TAP, AUTO_VIEWPORT }

data class ReaderSessionToken(val chapterIdentity: String, val readerGeneration: Long)

/** Captures a fresh-stream factory, never an open stream. Null means source-unavailable. */
data class ReaderPageWork(val pageKey: String, val readerPageIndex: Int, val streamFn: (() -> InputStream)?)

sealed interface ReaderSubmission {
    data class Queued(val pageKey: String) : ReaderSubmission
    data class Promoted(val pageKey: String) : ReaderSubmission
    data class Existing(val outcome: SinglePageOutcome?) : ReaderSubmission
    data class Rejected(val outcome: SinglePageOutcome.Rejected) : ReaderSubmission
}
