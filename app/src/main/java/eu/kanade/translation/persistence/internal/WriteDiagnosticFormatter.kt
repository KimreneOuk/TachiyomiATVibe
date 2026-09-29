package eu.kanade.translation.persistence.internal

/**
 * Formats the comma-separated write diagnostic fields shared by the chapter
 * store and batch write gate.
 */
internal fun formatWriteDiagnostic(
    pageKey: String,
    vararg fields: Pair<String, Any?>,
): String = buildString {
    append("pageKey=").append(pageKey)
    fields.forEach { (name, value) -> append(", ").append(name).append('=').append(value) }
}
