package eu.kanade.translation.persistence

/** Formats the comma-separated write diagnostic fields shared by the store. */
internal fun formatWriteDiagnostic(
    pageKey: String,
    vararg fields: Pair<String, Any?>,
): String = buildString {
    append("pageKey=").append(pageKey)
    fields.forEach { (name, value) -> append(", ").append(name).append('=').append(value) }
}
