package eu.kanade.translation.artifact

/**
 * T930 Slice A1: Commit-point contract.
 *
 * Defines the authoritative set of store operations that require durable manifest
 * publication. Under group commit (Slice B), mutations occurring outside these points
 * accumulate in an in-memory staged buffer and publish at the next commit point
 * or 250ms debounce window.
 *
 * CommitPoint = {
 *   OCR checkpoint CLOSE;
 *   page-terminal promotion;
 *   chapter phase records;
 *   chapter COMPLETE;
 *   user STOP drain;
 *   explicit flush requests from fenced CAS seams
 * }
 *
 * Everything else (intermediate candidate writes, live candidate patch/stage,
 * transient candidate updates) becomes stageable.
 */
enum class CommitPoint(val description: String) {
    /** Finalizing an OCR checkpoint (OcrCheckpointMode.CLOSE). */
    OCR_CHECKPOINT_CLOSE("OCR checkpoint CLOSE"),

    /** Promoting a page candidate to committed terminal display-ready state. */
    PAGE_TERMINAL_PROMOTION("page-terminal promotion"),

    /** Durable chapter phase or progress records (run record, phase transition, progress). */
    CHAPTER_PHASE_RECORD("chapter phase records"),

    /** Chapter translation terminal completion. */
    CHAPTER_COMPLETE("chapter COMPLETE"),

    /** Grace-bounded stop drain completing an in-flight page to its commit point. */
    USER_STOP_DRAIN("user STOP drain"),

    /** Explicit flush requests from fenced CAS seams or direct flush calls. */
    EXPLICIT_FLUSH("explicit flush requests from fenced CAS seams");

    /** Returns true if this mutation must trigger durable publication immediately. */
    val isMandatoryDurable: Boolean get() = true

    companion object {
        /**
         * Returns true if [point] represents a mandatory commit point.
         * Null represents stageable intermediate mutations.
         */
        fun isCommitPoint(point: CommitPoint?): Boolean = point != null

        /**
         * Returns true if the operation is stageable under group commit.
         */
        fun isStageable(point: CommitPoint?): Boolean = point == null
    }
}
