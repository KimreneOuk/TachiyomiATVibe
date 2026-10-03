package eu.kanade.translation.persistence.artifact

/**
 * Defines the named store operations that require a durable manifest
 * publication as part of their completion contract.
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
 * Other writes still publish through the artifact engine, but have no named
 * commit-point label.
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
    EXPLICIT_FLUSH("explicit flush requests from fenced CAS seams"),

    ;

    /** Returns true if this mutation must trigger durable publication immediately. */
    val isMandatoryDurable: Boolean get() = true

    companion object {
        /**
         * Returns true if [point] represents a mandatory commit point.
         * Null represents an operation without a named commit point.
         */
        fun isCommitPoint(point: CommitPoint?): Boolean = point != null
    }
}
