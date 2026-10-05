package eu.kanade.translation.persistence.artifact

import com.hippo.unifile.UniFile
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTrace
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.MessageDigest

/** Result of a destination-admitted move that never replaces an existing path. */
enum class RenameResult {
    MOVED,
    DESTINATION_EXISTS,
    UNSUPPORTED,
    FAILED,
}

/**
 * chapter document access rooted at the chapter's manga directory.
 * Names are `/`-separated paths relative to that root (e.g.
 * `Group_Chapter 1_artifacts/images/0001/gen-fp.jpg`). The abstraction keeps
 * the crash-safety and migration logic unit-testable; production uses the
 * [UniFile] implementation.
 */
interface ChapterDocumentIo {
    fun exists(name: String): Boolean
    fun length(name: String): Long
    fun read(name: String): ByteArray?

    /** Last-modified evidence for identity-aware preservation; zero means unavailable. */
    fun lastModified(name: String): Long = 0L

    /** Opens [name] without forcing callers to retain the whole payload in memory. */
    fun openInputStream(name: String): InputStream? = read(name)?.inputStream()

    /** Creates or overwrites [name], creating parent directories as needed. */
    fun write(name: String, bytes: ByteArray, syncToDisk: Boolean = false): Boolean

    /**
     * Moves [from] to [to] only when the backend can enforce no replacement.
     * Unsupported backends must return [RenameResult.UNSUPPORTED] without
     * mutating either path.
     */
    fun renameNoReplace(from: String, to: String): RenameResult

    /**
     * Moves a document between names exclusively owned by an atomic publisher.
     * Unlike [renameNoReplace], this operation may replace the destination and
     * must remain available on URI-backed SAF providers.
     */
    fun renameOwned(from: String, to: String): Boolean
    fun delete(name: String): Boolean

    /** Immediate child names of a directory, or null when it does not exist. */
    fun list(directoryName: String): List<String>?

    /** Indicates whether this IO is backed by a local java.io.File filesystem. */
    fun isFileBacked(): Boolean = false
}

/** [ChapterDocumentIo] over a [UniFile] manga directory. */
class UniFileChapterDocumentIo(
    private val root: UniFile,
) : ChapterDocumentIo {

    override fun isFileBacked(): Boolean = root.filePath != null

    private val dirCache = java.util.concurrent.ConcurrentHashMap<String, UniFile>()

    /**
     * perf: per-directory name→document index. A SAF [UniFile.findFile]
     * scan walks the directory children through ONE binder round-trip per
     * entry — on the artifact tree (hundreds of sidecars in `generations/`,
     * one subdirectory per page) that made every sidecar read a multi-second
     * scan and a 70-page resume revalidation take minutes of silent work.
     * The FIRST access to a directory pays ONE [UniFile.listFiles] call; all
     * further lookups are map hits — including MISSES: absent names are cached
     * under an [absent] marker, because the publish sweep probes names that do
     * not exist (`.bak` before the first rotation) on every atomic write.
     * Mutations through this IO keep the index coherent ([delete] and renames
     * mark ABSENT or insert surgically; creates insert directly).
     */
    private val fileIndex = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Any>>()

    /** Marks a probed-and-missing name so repeated misses stay O(1). */
    private val absent = Any()

    private fun lookupInDir(dir: UniFile, dirPath: String, fileName: String): UniFile? {
        val index = fileIndex[dirPath]
        if (index != null) {
            return when (val hit = index[fileName]) {
                // Never-seen name: one direct probe (it may exist outside this
                // IO), then cached — a NEGATIVE result is cached too, because
                // publish paths probe absent `.bak`/`.tmp` names constantly and
                // each probe was a full SAF child walk (seconds per call).
                // Mutations through this IO keep entries coherent (delete and
                // rename mark ABSENT, creates insert, nothing else survives).
                null -> {
                    val found = dir.findFile(fileName)
                    index[fileName] = found ?: absent
                    found
                }
                absent -> null
                else -> hit as UniFile
            }
        }
        val built = java.util.concurrent.ConcurrentHashMap<String, Any>()
        runCatching { dir.listFiles() }.getOrNull()?.forEach { child ->
            child.name?.let { name -> built[name] = child }
        }
        fileIndex[dirPath] = built
        return when (val hit = built[fileName]) {
            null -> {
                val found = dir.findFile(fileName)
                built[fileName] = found ?: absent
                found
            }
            absent -> null
            else -> hit as UniFile
        }
    }

    private fun invalidateIndex(dirPath: String) {
        fileIndex.remove(dirPath)
    }

    private fun resolveDir(dirPath: String): UniFile? {
        if (dirPath.isEmpty()) return root
        dirCache[dirPath]?.takeIf { it.exists() && it.isDirectory }?.let { return it }
        val segments = dirPath.split('/')
        var current: UniFile = root
        var currentPath = ""
        for (segment in segments) {
            if (segment.isEmpty()) continue
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            val cached = dirCache[currentPath]
            if (cached != null && cached.exists() && cached.isDirectory) {
                current = cached
            } else {
                current = current.findFile(segment) ?: return null
                dirCache[currentPath] = current
            }
        }
        return current
    }

    private fun resolve(name: String): UniFile? {
        if (name.isEmpty()) return root
        val slashIndex = name.lastIndexOf('/')
        if (slashIndex == -1) {
            return lookupInDir(root, "", name)
        }
        val dirPath = name.substring(0, slashIndex)
        val fileName = name.substring(slashIndex + 1)
        val dir = resolveDir(dirPath) ?: return null
        return lookupInDir(dir, dirPath, fileName)
    }

    private fun resolveOrCreate(name: String): UniFile? {
        val segments = name.split('/')
        var current: UniFile = root
        var currentPath = ""
        segments.dropLast(1).forEach { segment ->
            if (segment.isNotEmpty()) {
                currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
                val cached = dirCache[currentPath]
                if (cached != null && cached.exists() && cached.isDirectory) {
                    current = cached
                } else {
                    current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
                    dirCache[currentPath] = current
                }
            }
        }
        val last = segments.last()
        return current.findFile(last)
            ?: current.createFile(last)?.also { created ->
                fileIndex.getOrPut(currentPath) { java.util.concurrent.ConcurrentHashMap() }[last] = created
            }
    }

    override fun exists(name: String): Boolean = resolve(name)?.exists() == true

    override fun length(name: String): Long = resolve(name)?.length() ?: 0L

    override fun lastModified(name: String): Long = resolve(name)?.lastModified() ?: 0L

    override fun read(name: String): ByteArray? = runCatching {
        resolve(name)?.takeIf { it.isFile }?.openInputStream()?.use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output)
            output.toByteArray()
        }
    }.getOrNull()

    override fun openInputStream(name: String): InputStream? = runCatching {
        resolve(name)?.takeIf { it.isFile }?.openInputStream()
    }.getOrNull()

    override fun write(name: String, bytes: ByteArray, syncToDisk: Boolean): Boolean = runCatching {
        val target = resolveOrCreate(name) ?: return false
        target.openOutputStream().use { output ->
            output.write(bytes)
            output.flush()
            if (syncToDisk && output is java.io.FileOutputStream) {
                try {
                    output.fd.sync()
                } catch (_: Exception) {}
            }
        }
        true
    }.getOrDefault(false)

    override fun renameNoReplace(from: String, to: String): RenameResult {
        val source = resolve(from) ?: return RenameResult.FAILED
        invalidateIndex(to.substringBeforeLast('/', ""))
        val segments = to.split('/')
        if (segments.any { it.isEmpty() }) return RenameResult.FAILED
        var parent: UniFile = root
        segments.dropLast(1).forEach { segment ->
            parent = parent.findFile(segment) ?: parent.createDirectory(segment)
                ?: return RenameResult.FAILED
        }
        val sourcePath = source.filePath?.let(java.nio.file.Path::of)
        val parentPath = parent.filePath?.let(java.nio.file.Path::of)
            ?: return RenameResult.UNSUPPORTED
        if (sourcePath == null) return RenameResult.UNSUPPORTED
        val targetPath = parentPath.resolve(segments.last())
        return try {
            // Files.move without REPLACE_EXISTING is the enforceable raw-file
            // admission primitive. URI/SAF providers expose no equivalent.
            Files.move(sourcePath, targetPath)
            // Files.move leaves the source UniFile bound to the old path, so
            // only mark the source absent and re-list the target directory
            // (cold path — quarantine restore).
            fileIndex[from.substringBeforeLast('/', "")]?.put(from.substringAfterLast('/'), absent)
            invalidateIndex(to.substringBeforeLast('/', ""))
            RenameResult.MOVED
        } catch (_: FileAlreadyExistsException) {
            RenameResult.DESTINATION_EXISTS
        } catch (_: NoSuchFileException) {
            RenameResult.FAILED
        } catch (_: UnsupportedOperationException) {
            RenameResult.UNSUPPORTED
        } catch (_: java.io.IOException) {
            RenameResult.FAILED
        }
    }

    override fun renameOwned(from: String, to: String): Boolean {
        val source = resolve(from) ?: return false
        val segments = to.split('/')
        if (segments.any { it.isEmpty() }) return false
        var parent: UniFile = root
        segments.dropLast(1).forEach { segment ->
            parent = parent.findFile(segment) ?: parent.createDirectory(segment) ?: return false
        }
        val renamed = runCatching { source.renameTo(segments.last()) }.getOrDefault(false)
        if (!renamed) return false
        // Surgical index update — SAF documents rebind in place (the renamed
        // UniFile's URI is updated), so no directory re-listing is needed.
        // Implementations that do NOT rebind (raw/test doubles still point at
        // the old path) are detected by the existence probe and fall back to a
        // re-list instead of caching a stale entry.
        val fromDir = from.substringBeforeLast('/', "")
        val toDir = to.substringBeforeLast('/', "")
        if (source.exists()) {
            fileIndex[fromDir]?.put(from.substringAfterLast('/'), absent)
            fileIndex[toDir]?.put(to.substringAfterLast('/'), source)
        } else {
            invalidateIndex(fromDir)
            invalidateIndex(toDir)
        }
        return true
    }

    override fun delete(name: String): Boolean {
        dirCache.remove(name)
        val deleted = resolve(name)?.delete() == true
        if (deleted) {
            // Mark the name absent rather than dropping the entry: a later
            // resolve of the deleted name (publish sweeps `.bak` every round)
            // must not re-walk the directory.
            fileIndex[name.substringBeforeLast('/', "")]?.put(name.substringAfterLast('/'), absent)
        }
        return deleted
    }

    override fun list(directoryName: String): List<String>? =
        resolve(directoryName)?.takeIf { it.isDirectory }?.listFiles()?.mapNotNull { it.name }
}

/**
 * 06: the single canonical artifact Json configuration. Every durable
 * artifact document — manifests, sidecars, and the  versioned DTOs —
 * serializes through this shared instance (compact UTF-8 output,
 * declaration-order fields, defaults encoded); creating bespoke `Json`
 * instances for durable documents is forbidden.
 */
val ArtifactDocumentJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * crash-safe document publication (lifecycle contract §§14–15).
 *
 * Publish sequence: write `name.tmp`, re-read and validate it, rotate the
 * current primary to `name.bak`, then rename the temp over the primary. Every
 * crash window is recoverable:
 *
 * - crash before the first rename leaves only an orphan `.tmp` (swept later);
 * - crash between the two renames leaves the previous version in `.bak`;
 * - [readValidated] restores a valid `.bak` over a missing/corrupt primary and
 *   quarantines a corrupt primary as `name.corrupt` instead of deleting it.
 *
 * The backup is retained until the next successful publish replaces it, and
 * the only recoverable copy is never mutated in place.
 */
class AtomicChapterDocuments(
    @PublishedApi
    internal val io: ChapterDocumentIo,
) {
    @PublishedApi
    internal val json = ArtifactDocumentJson

    fun exists(name: String): Boolean = io.exists(name)

    /**
     *  the whole write→validate→rotate→rename sequence runs under the
     * PROCESS-WIDE per-document-name lock ([lockFor] is companion state). The
     * temp name is deterministic ([tempNameFor]), so two in-process writers
     * targeting the same document from DIFFERENT store/documents instances
     * (the batch preflight publishing the run record vs. the >8-page open
     * path's background artifact-health manifest republisher)
     * used to collide on `name.tmp` and interleave the rotation renames — the
     * loser got a mechanical `false` with no diagnostics and every caller
     * mapped that to a fatal Rejected. Serializing per name eliminates both
     * the tmp collision and the rotate/rename interleaving regardless of which
     * instance the racing writer holds. No nesting: [publish] never calls
     * [publish], and the inner io calls take no other locks — no deadlock
     * risk. Writers to DIFFERENT names proceed in parallel.
     */
    fun publish(
        name: String,
        bytes: ByteArray,
        syncToDisk: Boolean = false,
        validate: (ByteArray) -> Boolean,
    ): Boolean = synchronized(lockFor(name)) {
        val tempName = tempNameFor(name)
        val backupName = backupNameFor(name)
        val writeSucceeded = try {
            io.write(tempName, bytes, syncToDisk = syncToDisk)
        } catch (failure: Throwable) {
            recordPhysicalWrite(success = false, bytes = bytes.size)
            throw failure
        }
        recordPhysicalWrite(success = writeSucceeded, bytes = bytes.size)
        if (!writeSucceeded) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT chapter document publish failed: stage=tmp-write name=$name"
            }
            return false
        }
        val written = io.read(tempName)
        val matches = written != null && written.contentEquals(bytes) && validate(written)
        if (!matches) {
            io.delete(tempName)
            logcat(LogPriority.WARN) {
                "TachiyomiAT chapter document publish failed: stage=tmp-readback-validate name=$name"
            }
            return false
        }
        io.delete(backupName)
        if (io.exists(name) && !io.renameOwned(name, backupName)) {
            io.delete(tempName)
            logcat(LogPriority.WARN) {
                "TachiyomiAT chapter document publish failed: stage=backup-rotation name=$name"
            }
            return false
        }
        if (!io.renameOwned(tempName, name)) {
            if (io.exists(backupName)) io.renameOwned(backupName, name)
            logcat(LogPriority.WARN) {
                "TachiyomiAT chapter document publish failed: stage=promote-rename name=$name"
            }
            return false
        }
        true
    }

    private fun recordPhysicalWrite(success: Boolean, bytes: Int) {
        runCatching {
            TranslationTrace.currentRuns().forEach { run ->
                TranslationPipelineDiagnostics.recordStorageIo(
                    identity = run.identity,
                    storage = "artifact",
                    physicalWriteCalls = if (success) 1L else 0L,
                    physicalWriteBytes = if (success) bytes.toLong() else 0L,
                    failedWriteAttempts = if (success) 0L else 1L,
                    failedAttemptBytes = if (success) 0L else bytes.toLong(),
                )
                if (run.isClosed) TranslationPipelineDiagnostics.flushStorageIo(run.identity)
            }
        }
    }

    inline fun <reified T> publishJson(
        name: String,
        value: T,
        syncToDisk: Boolean = false,
    ): Boolean = publish(
        name,
        json.encodeToString(value).toByteArray(Charsets.UTF_8),
        syncToDisk = syncToDisk,
    ) { bytes -> runCatching { json.decodeFromStream<T>(bytes.inputStream()) }.isSuccess }

    /**
     * Reads and validates [name]. A missing or corrupt primary is recovered
     * from the retained backup before parsing proceeds. Returns null when
     * neither copy validates or exists.
     */
    inline fun <reified T> readValidated(name: String, noinline validate: (T) -> Boolean = { true }): T? {
        val primaryBytes = io.read(name)
        if (primaryBytes != null) {
            val primary = runCatching { json.decodeFromStream<T>(primaryBytes.inputStream()) }.getOrNull()
            if (primary != null && validate(primary)) return primary
        }
        val backupName = backupNameFor(name)
        val backupBytes = io.read(backupName) ?: return null
        val backup = runCatching { json.decodeFromStream<T>(backupBytes.inputStream()) }.getOrNull() ?: return null
        if (!validate(backup)) return null
        logcat(LogPriority.WARN) {
            "TachiyomiAT chapter document primary invalid; recovering backup: name=$name"
        }
        recoverPrimaryFromBackup(name, backupName)
        return backup
    }

    /**
     * Moves a corrupt primary aside to `name.corrupt` and promotes the backup
     * to primary. The corrupt payload is quarantined, never deleted, and the
     * backup bytes are already safely parsed before this runs.
     */
    @PublishedApi
    internal fun recoverPrimaryFromBackup(name: String, backupName: String): Boolean {
        val hadPrimary = io.exists(name)
        val primaryBytes = io.read(name)
        val actualQuarantineName = if (hadPrimary) {
            quarantineCorruptPrimary(name, primaryBytes ?: name.toByteArray())
        } else {
            null
        }
        val quarantined = !hadPrimary || actualQuarantineName != null
        if (quarantined && io.renameNoReplace(backupName, name) == RenameResult.MOVED) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT chapter document recovered from backup: " +
                    "name=$name quarantined=${actualQuarantineName ?: "none"}"
            }
            return true
        }
        if (actualQuarantineName != null) {
            io.renameNoReplace(actualQuarantineName, name)
        }
        return false
    }

    /** Quarantines a corrupt primary without replacing an existing sibling. */
    fun quarantineCorrupt(name: String): String? = quarantineCorruptPrimary(
        name,
        io.read(name) ?: name.toByteArray(),
    )

    private fun quarantineCorruptPrimary(name: String, bytes: ByteArray): String? {
        val candidates = collisionSafeSiblings(corruptNameFor(name), bytes)
        for (candidate in candidates) {
            when (io.renameNoReplace(name, candidate)) {
                RenameResult.MOVED -> return candidate
                RenameResult.DESTINATION_EXISTS -> continue
                RenameResult.UNSUPPORTED,
                RenameResult.FAILED,
                -> return null
            }
        }
        return null
    }

    /** Returns only bounded, deterministic candidates; exhaustion fails closed. */
    private fun collisionSafeSiblings(baseName: String, bytes: ByteArray): List<String> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(8)
        val digestName = "$baseName.$digest"
        return buildList {
            add(baseName)
            add(digestName)
            (1..MAX_COLLISION_SUFFIX).forEach { suffix -> add("$digestName.$suffix") }
        }
    }

    fun delete(name: String): Boolean = io.delete(name)

    fun rawIo(): ChapterDocumentIo = io

    companion object {
        fun tempNameFor(name: String): String = "$name.tmp"
        fun backupNameFor(name: String): String = "$name.bak"
        fun corruptNameFor(name: String): String = "$name.corrupt"

        /**
         *  the process-wide per-document publication locks. Companion
         * state ON PURPOSE: the racing writer may hold a DIFFERENT
         * [AtomicChapterDocuments] instance (a second store instance over the
         * same chapter), so an instance-level map would not serialize them.
         * Keyed by document name; same-named documents in different chapters
         * share a lock, which only costs concurrency, never correctness.
         * Entries are one bare monitor each and bounded by the set of
         * document names this process has ever published.
         */
        private val publicationLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** The process-wide monitor guarding the whole publish of [name]. */
        internal fun lockFor(name: String): Any = publicationLocks.computeIfAbsent(name) { Any() }

        private const val MAX_COLLISION_SUFFIX = 16
    }
}
