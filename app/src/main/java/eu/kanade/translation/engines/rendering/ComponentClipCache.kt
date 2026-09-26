package eu.kanade.translation.engines.rendering

import eu.kanade.translation.engines.vision.segmentation.MaskGeometry

/**
 * compact, JVM-pure clip-object cache keyed by the per-page
 * `(planGeometryId, componentId)` pair. No coordinate strings are built and
 * there is no `android.graphics` dependency in this class, so JVM tests can
 * drive it with a fake clip type.
 *
 * A hit only counts when the stored entry refers to the SAME geometry instance —
 * a packed key arriving with a different geometry fails closed. Capacity is
 * checked BEFORE [create], so a would-exceed lookup never allocates a clip.
 */
internal class ComponentClipCache<P : Any>(
    private val maxComponents: Int,
    private val maxSpans: Int,
    private val create: (MaskGeometry.Component) -> P,
) {
    private class Entry<C>(val geometry: MaskGeometry, val value: C, val spanCount: Int)

    private val entries = LinkedHashMap<Long, Entry<P>>()
    private var cachedComponents = 0
    private var cachedSpans = 0

    /** Distinct components currently cached. */
    internal val size: Int get() = cachedComponents

    fun resolve(planGeometryId: Int, componentId: Int, geometry: MaskGeometry): P? {
        // Fail closed on an out-of-range component id.
        if (componentId !in geometry.components.indices) return null
        val key = pack(planGeometryId, componentId)
        val cached = entries[key]
        if (cached != null) {
            // Instance identity verification: the pair is only meaningful within
            // the geometry instance that produced it.
            return if (cached.geometry === geometry) cached.value else null
        }
        val component = geometry.components[componentId]
        if (cachedComponents + 1 > maxComponents) return null
        if (cachedSpans + component.spans.size > maxSpans) return null
        val value = create(component)
        entries[key] = Entry(geometry, value, component.spans.size)
        cachedComponents++
        cachedSpans += component.spans.size
        return value
    }

    fun clear() {
        entries.clear()
        cachedComponents = 0
        cachedSpans = 0
    }

    internal companion object {
        internal fun pack(planGeometryId: Int, componentId: Int): Long =
            (planGeometryId.toLong() shl 32) or (componentId.toLong() and 0xFFFF_FFFFL)
    }
}
