package eu.kanade.translation.translator

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.SharedProviderRequestAdmission
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.RevisionReport
import eu.kanade.translation.model.RevisionScope
import eu.kanade.translation.model.detachedCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.coroutineContext

object RevisionDriver {

    interface ProgressListener {
        fun onBegin(totalBlocks: Int, skippedBlocks: Int, userEditedBlocks: Int, groupsCount: Int)
        fun onGroupStart(pageKeys: Set<String>, groupTargetsCount: Int)
        fun onGroupComplete(appliedCount: Int, failedCount: Int)
        fun onOverBudget(pageKey: String, blockIndex: Int, estimatedTokens: Int, maxPromptTokens: Int)
        fun onFinished()
    }

    suspend fun runRevision(
        store: ChapterTranslationStore,
        contextualTranslator: ContextualTextTranslator,
        scope: RevisionScope,
        requestedOutputTokens: Int,
        chapterId: Long,
        chapterName: String,
        listener: ProgressListener? = null,
        onPageUpdated: (suspend (String) -> Unit)? = null,
    ): RevisionReport {
        val liveState = store.state.value
        val orderedPageKeys = liveState.keys.sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
        val orderedPages = LinkedHashMap<String, PageTranslation>()
        orderedPageKeys.forEach { pk -> liveState[pk]?.let { orderedPages[pk] = it.detachedCopy() } }

        val chapterGlossary = store.glossarySnapshot()
        val revisionPlan = RevisionPlanner.plan(
            orderedPages = orderedPages,
            chapterGlossary = chapterGlossary,
            requestedOutputTokens = requestedOutputTokens,
            scope = scope,
        )

        val totalTargets = revisionPlan.allTargets.size
        val skippedRevisionBlocks = orderedPages.values.sumOf { page ->
            page.blocks.count { block ->
                (scope == RevisionScope.ALL_TRANSLATED || block.needsRevision) &&
                    (block.userEditedAt != null || block.text.isBlank() || block.translation.isBlank())
            }
        }
        val userEditedRevisionBlocks = orderedPages.values.sumOf { page ->
            page.blocks.count { block ->
                (scope == RevisionScope.ALL_TRANSLATED || block.needsRevision) && block.userEditedAt != null
            }
        }

        listener?.onBegin(
            totalBlocks = totalTargets,
            skippedBlocks = skippedRevisionBlocks,
            userEditedBlocks = userEditedRevisionBlocks,
            groupsCount = revisionPlan.groups.size,
        )

        logcat(LogPriority.INFO) {
            "TachiyomiAT standalone Pass 2 START chapter=$chapterName " +
                "eligible=$totalTargets skipped=$skippedRevisionBlocks " +
                "userEdited=$userEditedRevisionBlocks groups=${revisionPlan.groups.size} " +
                "overBudget=${revisionPlan.overBudget.size}"
        }

        val runStartedAt = System.currentTimeMillis()
        val acceptedChanges = mutableListOf<RevisionReport.AcceptedChange>()
        var totalKept = 0
        var totalCorrected = 0
        var totalUnresolved = 0

        revisionPlan.overBudget.forEach { ob ->
            logcat(LogPriority.WARN) {
                "TachiyomiAT revision over-budget target retained: " +
                    "chapter=$chapterName pageKey=${ob.target.pageKey} " +
                    "blockIndex=${ob.target.blockIndex} " +
                    "estimatedTokens=${ob.estimatedTokens} maxPromptTokens=${ob.maxPromptTokens}"
            }
            listener?.onOverBudget(
                pageKey = ob.target.pageKey,
                blockIndex = ob.target.blockIndex,
                estimatedTokens = ob.estimatedTokens,
                maxPromptTokens = ob.maxPromptTokens,
            )
            totalUnresolved++
        }

        revisionPlan.groups.forEachIndexed { groupIndex, group ->
            coroutineContext.ensureActive()
            val refsGrouped = group.targets.groupBy { it.pageKey }
            listener?.onGroupStart(refsGrouped.keys, group.targets.size)

            val groupLiveState = store.state.value
            val groupTargetKeys = group.targets.map { it.pageKey to it.blockIndex }.toHashSet()
            val chunkPages = LinkedHashMap<String, PageTranslation>()
            group.targets.map { it.pageKey }.distinct().forEach { pageKey ->
                val livePage = groupLiveState[pageKey] ?: return@forEach
                val rebuiltBlocks = livePage.blocks.mapIndexed { idx, block ->
                    if ((pageKey to idx) in groupTargetKeys) {
                        block.detachedCopy()
                    } else {
                        block.detachedCopy().copy(needsRevision = false)
                    }
                }.toMutableList()
                chunkPages[pageKey] = livePage.copy(blocks = rebuiltBlocks)
            }
            val contextChunk = TranslationContextChunk(
                pages = chunkPages,
                blockCount = group.targets.size,
                rollingContext = "",
                glossary = ChapterGlossaryBuilder.formatGlossary(group.chapterGlossary),
                estimatedPromptTokens = group.estimatedPromptTokens,
                maxOutputTokens = group.maxOutputTokens,
            )

            var appliedCount = 0
            var failedCount = 0
            var batch: ContextualTranslationBatch? = null
            try {
                batch = SharedProviderRequestAdmission.withRequest {
                    contextualTranslator.translateContextualStructured(contextChunk, isPass2 = true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failedCount = group.targets.size
                logcat(LogPriority.ERROR, e) {
                    "TachiyomiAT Pass 2 group ${groupIndex + 1}/${revisionPlan.groups.size} request failed " +
                        "chapter=$chapterName pageKeys=${refsGrouped.keys} targets=${group.targets.size} " +
                        "reason=${e.message ?: e::class.java.simpleName}"
                }
                totalUnresolved += failedCount
                listener?.onGroupComplete(0, failedCount)
                return@forEachIndexed
            }

            val mergeLive = RevisionCommitter.mergeLiveSnapshot(
                store,
                group.targets.map { it.pageKey }.distinct(),
            )
            val mergeResult = RevisionMerger.merge(
                livePages = mergeLive,
                batch = batch,
                requestTargets = group.targets,
                chapterId = chapterId,
                chapterName = chapterName,
            )

            val orderedAnchorIds = RevisionCommitter.orderedAnchorIds(batch)
            val commitOutcome = RevisionCommitter.commit(
                store = store,
                mergeLive = mergeLive,
                groupTargets = group.targets,
                batch = batch,
                orderedAnchorIds = orderedAnchorIds,
                mergeResult = mergeResult,
                chapterId = chapterId,
                chapterName = chapterName,
            )
            appliedCount = commitOutcome.appliedCount
            failedCount = commitOutcome.failedCount

            totalKept += commitOutcome.keptCount
            totalCorrected += commitOutcome.correctedCount
            totalUnresolved += failedCount

            mergeResult.corrected.forEach { correctedTarget ->
                val pageKey = correctedTarget.pageKey
                val blockIndex = correctedTarget.blockIndex
                val liveBlock = store.state.value[pageKey]?.blocks?.getOrNull(blockIndex)
                if (liveBlock != null && liveBlock.translation == correctedTarget.afterDraft) {
                    acceptedChanges += RevisionReport.AcceptedChange(
                        pageKey = pageKey,
                        blockIndex = blockIndex,
                        beforeDraft = correctedTarget.beforeDraft,
                        afterDraft = correctedTarget.afterDraft,
                    )
                }
            }

            commitOutcome.touchedPages.forEach { pageKey ->
                onPageUpdated?.invoke(pageKey)
            }

            listener?.onGroupComplete(appliedCount, failedCount)
            logcat(LogPriority.INFO) {
                "TachiyomiAT Pass 2 group ${groupIndex + 1}/${revisionPlan.groups.size} complete " +
                    "chapter=$chapterName revised=$appliedCount failed=$failedCount " +
                    "targets=${group.targets.size}"
            }
        }

        listener?.onFinished()

        return RevisionReport(
            scope = scope,
            runStartedAt = runStartedAt,
            runFinishedAt = System.currentTimeMillis(),
            keptCount = totalKept,
            correctedCount = totalCorrected,
            unresolvedCount = totalUnresolved,
            changes = acceptedChanges,
        )
    }
}
