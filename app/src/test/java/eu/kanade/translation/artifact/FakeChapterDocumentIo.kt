package eu.kanade.translation.artifact

/** In-memory [ChapterDocumentIo] double for unit tests. */
class FakeChapterDocumentIo : ChapterDocumentIo {
    val files = LinkedHashMap<String, ByteArray>()
    val directories = mutableSetOf<String>()
    val deletedNames = mutableListOf<String>()
    val renamed = mutableListOf<Pair<String, String>>()
    val writtenNames = mutableListOf<String>()
    val listedDirectories = mutableListOf<String>()
    var failWrites = false

    /** Exact `from` names whose rename must fail. */
    val renamesToFail = mutableSetOf<String>()

    private fun parentOf(name: String): String? {
        val index = name.lastIndexOf('/')
        return if (index <= 0) null else name.substring(0, index)
    }

    private fun ensureParents(name: String) {
        var current = name
        while (true) {
            val parent = parentOf(current) ?: break
            directories += parent
            current = parent
        }
    }

    override fun exists(name: String): Boolean = files.containsKey(name) || directories.contains(name)

    override fun length(name: String): Long = files[name]?.size?.toLong() ?: 0L

    override fun read(name: String): ByteArray? = files[name]?.copyOf()

    override fun write(name: String, bytes: ByteArray): Boolean {
        if (failWrites) return false
        writtenNames += name
        files[name] = bytes.copyOf()
        ensureParents(name)
        return true
    }

    override fun rename(from: String, to: String): Boolean {
        if (from in renamesToFail) return false
        if (!files.containsKey(from)) return false
        files[to] = files.remove(from)!!
        ensureParents(to)
        renamed += from to to
        return true
    }

    override fun delete(name: String): Boolean {
        deletedNames += name
        val removedFile = files.remove(name) != null
        val removedDir = directories.remove(name)
        if (removedDir) {
            files.keys.filter { it.startsWith("$name/") }.forEach { files.remove(it) }
            directories.filter { it.startsWith("$name/") }.toSet().forEach { directories.remove(it) }
        }
        return removedFile || removedDir
    }

    override fun list(directoryName: String): List<String>? {
        listedDirectories += directoryName
        if (!directories.contains(directoryName)) return null
        return (
            files.keys.filter { parentOf(it) == directoryName }.map { it.substringAfterLast('/') } +
                directories.filter { parentOf(it) == directoryName }.map { it.substringAfterLast('/') }
            )
            .distinct()
            .sorted()
    }
}
