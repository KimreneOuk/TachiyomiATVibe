package eu.kanade.translation.engines.rendering

import eu.kanade.translation.engines.vision.segmentation.BubbleMaskRle
import eu.kanade.translation.engines.vision.segmentation.MaskConversionBudgets
import eu.kanade.translation.engines.vision.segmentation.MaskGeometry
import eu.kanade.translation.engines.vision.segmentation.OrderedMaskResult
import java.util.IdentityHashMap

/**
 * Per-page mask grouping for layout planning.
 *
 * The grouping policy is deliberately isolated from the planner facade: it
 * walks input masks in order, verifies fingerprint buckets with full geometry
 * equality, and bounds both references and conversions. The public package
 * symbols remain unchanged for the rendering tests and planner callers.
 */
internal class SharedMaskSession(
    val budgets: MaskConversionBudgets = MaskConversionBudgets(),
    private val fingerprint: (BubbleMaskRle) -> Long = ::defaultMaskFingerprint,
) {
    private val referenceGroups = IdentityHashMap<BubbleMaskRle, Int>()
    private val grouplessIds = IdentityHashMap<BubbleMaskRle, Int>()
    private val fingerprintBuckets = HashMap<Long, MutableList<Int>>()
    private val groupMasks = ArrayList<BubbleMaskRle>()
    private val conversions = ArrayList<MaskGeometry?>()
    private var converted = false
    private var nextGrouplessId = -1

    /** Number of distinct verified mask groups on this page. */
    internal val groupCount: Int get() = groupMasks.size

    /**
     * Group id for [mask]: `0 until groupCount` when grouped, negative when
     * groupless. Safe to call repeatedly — reference identity short-circuits
     * with no rescan.
     */
    fun groupIdOf(mask: BubbleMaskRle): Int {
        referenceGroups[mask]?.let { return it }
        grouplessIds[mask]?.let { return it }
        if (referenceGroups.size + grouplessIds.size >= MAX_MASK_REFERENCES_PER_PAGE) {
            return assignGroupless(mask)
        }
        // The fingerprint scan consumes the page RLE-int budget; abort
        // (groupless) before scanning when the budget would be exceeded.
        if (budgets.rleIntsScanned + mask.runs.size > budgets.maxRleIntsScannedPerPage) {
            return assignGroupless(mask)
        }
        budgets.rleIntsScanned += mask.runs.size
        val hash = fingerprint(mask)
        fingerprintBuckets[hash]?.forEach { candidate ->
            if (geometricallyEqual(groupMasks[candidate], mask)) {
                referenceGroups[mask] = candidate
                return candidate
            }
        }
        if (groupMasks.size >= MAX_UNIQUE_MASKS_PER_PAGE) return assignGroupless(mask)
        val groupId = groupMasks.size
        groupMasks += mask
        fingerprintBuckets.getOrPut(hash) { mutableListOf() }.add(groupId)
        referenceGroups[mask] = groupId
        return groupId
    }

    /** Convert every grouped mask once, in group order. Idempotent. */
    fun convertAll() {
        if (converted) return
        converted = true
        repeat(groupMasks.size) { groupId ->
            conversions += when (val result = MaskGeometry.fromOrderedRle(groupMasks[groupId], budgets)) {
                is OrderedMaskResult.Success -> result.geometry
                is OrderedMaskResult.Fallback -> null
            }
        }
    }

    /** Geometry for a grouped id, or null when groupless or conversion fell back. */
    fun geometryFor(groupId: Int): MaskGeometry? {
        if (groupId < 0) return null
        convertAll()
        return conversions.getOrNull(groupId)
    }

    private fun assignGroupless(mask: BubbleMaskRle): Int {
        val id = nextGrouplessId
        nextGrouplessId -= 1
        grouplessIds[mask] = id
        return id
    }

    private fun geometricallyEqual(a: BubbleMaskRle, b: BubbleMaskRle): Boolean =
        a.width == b.width && a.height == b.height && a.bounds == b.bounds && a.runs == b.runs

    internal companion object {
        /** Hard guard: distinct mask references grouped per page (beyond → groupless). */
        internal const val MAX_MASK_REFERENCES_PER_PAGE = 128

        /** Hard guard: distinct unique masks grouped per page (beyond → groupless). */
        internal const val MAX_UNIQUE_MASKS_PER_PAGE = 32
    }
}

/** Bounded FNV-1a-style 64-bit streaming fingerprint over geometry-defining fields. */
internal fun defaultMaskFingerprint(mask: BubbleMaskRle): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    fun mix(value: Int) {
        hash = (hash xor value.toLong()) * 0x100000001b3L
    }
    mix(mask.width)
    mix(mask.height)
    for (bound in mask.bounds) mix(bound)
    mix(mask.runs.size)
    for (run in mask.runs) mix(run)
    return hash
}
