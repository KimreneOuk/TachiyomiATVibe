package eu.kanade.translation.remote

object BackendPageKey {
    fun partition(pageKey: String, backend: InferenceBackend): String = "${backend.name}:$pageKey"
}
