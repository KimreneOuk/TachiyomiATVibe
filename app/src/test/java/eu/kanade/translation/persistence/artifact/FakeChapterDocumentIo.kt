package eu.kanade.translation.persistence.artifact

/** In-memory [ChapterDocumentIo] double for unit tests. */
class FakeChapterDocumentIo : ChapterDocumentIo {
    val files = LinkedHashMap<String, ByteArray>()
    val directories = mutableSetOf<String>()
    val deletedNames = mutableListOf<String>()
    val renamed = mutableListOf<Pair<String, String>>()
    val writtenNames = mutableListOf<String>()
    val listedDirectories = mutableListOf<String>()
    val lastModifiedTimes = mutableMapOf<String, Long>()
    var failWrites = false
    var supportsNoReplaceRename = true
    var renameChangesLastModified = false
    var fileBacked = false

    override fun isFileBacked(): Boolean = fileBacked

    /** Owned artifact moves remain available even when this double models URI/SAF. */
    val ownedRenamesToFail = mutableSetOf<String>()

    /** Injects a target between admission observation and the backend move. */
    var beforeRenameAttempt: ((from: String, to: String) -> Unit)? = null

    /** Optional failure/race seam for the owned artifact publication path. */
    var beforeOwnedRenameAttempt: ((from: String, to: String) -> Unit)? = null

    /** Write-name fragments that should fail while the rest of the document remains writable. */
    val writeNamesToFail = mutableSetOf<String>()

    /** Exact `from` names whose rename must fail. */
    val renamesToFail = mutableSetOf<String>()

    /** Exact paths whose deletion must fail, for crash/SAF cleanup tests. */
    val deleteNamesToFail = mutableSetOf<String>()

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

    override fun lastModified(name: String): Long = lastModifiedTimes[name] ?: 0L

    val syncedWrites = mutableListOf<String>()

    override fun write(name: String, bytes: ByteArray, syncToDisk: Boolean): Boolean {
        if (failWrites || writeNamesToFail.any { fragment -> name.contains(fragment) }) return false
        writtenNames += name
        if (syncToDisk) syncedWrites += name
        files[name] = bytes.copyOf()
        ensureParents(name)
        return true
    }

    override fun renameNoReplace(from: String, to: String): RenameResult {
        if (!supportsNoReplaceRename) return RenameResult.UNSUPPORTED
        if (from in renamesToFail) return RenameResult.FAILED
        if (files.containsKey(to) || directories.contains(to)) return RenameResult.DESTINATION_EXISTS
        beforeRenameAttempt?.invoke(from, to)
        if (files.containsKey(to) || directories.contains(to)) return RenameResult.DESTINATION_EXISTS
        if (!files.containsKey(from)) return RenameResult.FAILED
        files[to] = files.remove(from)!!
        lastModifiedTimes.remove(from)?.let { modified ->
            lastModifiedTimes[to] = if (renameChangesLastModified) modified + 1L else modified
        }
        ensureParents(to)
        renamed += from to to
        return RenameResult.MOVED
    }

    override fun renameOwned(from: String, to: String): Boolean {
        if (from in ownedRenamesToFail) return false
        val bytes = files[from] ?: return false
        beforeOwnedRenameAttempt?.invoke(from, to)
        files.remove(from)
        files[to] = bytes
        lastModifiedTimes.remove(from)?.let { modified ->
            lastModifiedTimes[to] = if (renameChangesLastModified) modified + 1L else modified
        }
        ensureParents(to)
        renamed += from to to
        return true
    }

    override fun delete(name: String): Boolean {
        deletedNames += name
        if (name in deleteNamesToFail) return false
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
        if (directoryName.isNotEmpty() && !directories.contains(directoryName)) return null
        val isChild: (String) -> Boolean = { name ->
            if (directoryName.isEmpty()) parentOf(name) == null else parentOf(name) == directoryName
        }
        return (
            files.keys.filter(isChild).map { it.substringAfterLast('/') } +
                directories.filter(isChild).map { it.substringAfterLast('/') }
            )
            .distinct()
            .sorted()
    }
}
