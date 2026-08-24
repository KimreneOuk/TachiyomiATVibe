package eu.kanade.translation.translator

/**
 * Canonical page/block identity for chapter-batch AI requests and responses.
 * Requests and responses are plain `ID|text` lines; these stable IDs are the
 * only protocol surface a provider is asked to echo back.
 */
object BatchTranslationProtocol {
    const val VERSION = 1

    fun pageId(naturalPageIndex: Int): String =
        "p${naturalPageIndex.toString().padStart(4, '0')}"

    fun blockId(naturalPageIndex: Int, stableBlockIndex: Int): String =
        "${pageId(naturalPageIndex)}_b${stableBlockIndex.toString().padStart(4, '0')}"
}
