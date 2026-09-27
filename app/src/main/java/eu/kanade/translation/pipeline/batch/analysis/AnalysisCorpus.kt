package eu.kanade.translation.pipeline.batch.analysis

import eu.kanade.translation.persistence.artifact.SidecarPointer

/** Rebuilt OCR page entry with both durable storage and analysis wire identities. */
internal class AnalysisCorpusEntry(
    val storagePageKey: String,
    val naturalPageIndex: Int,
    val contentFingerprint: String,
    val snapshotPointer: SidecarPointer,
    val wirePageKey: String,
    val wireBlockIds: List<String>,
    val blockTexts: List<String>,
    val estimatedInputTokens: Int,
)

/** Durable OCR corpus used to construct deterministic translation envelopes. */
internal class AnalysisCorpus(
    val entries: List<AnalysisCorpusEntry>,
    val corpusFingerprint: String,
)
