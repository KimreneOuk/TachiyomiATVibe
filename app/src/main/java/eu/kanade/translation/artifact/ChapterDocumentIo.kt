package eu.kanade.translation.artifact

import com.hippo.unifile.UniFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * TachiyomiAT: chapter document access rooted at the chapter's manga directory.
 * Names are `/`-separated paths relative to that root (e.g.
 * `Group_Chapter 1_artifacts/images/0001/gen-fp.jpg`). The abstraction keeps
 * the crash-safety and migration logic unit-testable; production uses the
 * [UniFile] implementation.
 */
interface ChapterDocumentIo {
    fun exists(name: String): Boolean
    fun length(name: String): Long
    fun read(name: String): ByteArray?

    /** Opens [name] without forcing callers to retain the whole payload in memory. */
    fun openInputStream(name: String): InputStream? = read(name)?.inputStream()

    /** Creates or overwrites [name], creating parent directories as needed. */
    fun write(name: String, bytes: ByteArray): Boolean
    fun rename(from: String, to: String): Boolean
    fun delete(name: String): Boolean

    /** Immediate child names of a directory, or null when it does not exist. */
    fun list(directoryName: String): List<String>?
}

/** [ChapterDocumentIo] over a [UniFile] manga directory. */
class UniFileChapterDocumentIo(
    private val root: UniFile,
) : ChapterDocumentIo {

    private fun resolve(name: String): UniFile? {
        var current: UniFile = root
        name.split('/').forEach { segment ->
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun resolveOrCreate(name: String): UniFile? {
        val segments = name.split('/')
        var current: UniFile = root
        segments.dropLast(1).forEach { segment ->
            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
        }
        val last = segments.last()
        return current.findFile(last) ?: current.createFile(last)
    }

    override fun exists(name: String): Boolean = resolve(name)?.exists() == true

    override fun length(name: String): Long = resolve(name)?.length() ?: 0L

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

    override fun write(name: String, bytes: ByteArray): Boolean = runCatching {
        val target = resolveOrCreate(name) ?: return false
        target.openOutputStream().use { output ->
            output.write(bytes)
            output.flush()
        }
        true
    }.getOrDefault(false)

    override fun rename(from: String, to: String): Boolean {
        val source = resolve(from) ?: return false
        val segments = to.split('/')
        var parent: UniFile = root
        segments.dropLast(1).forEach { segment ->
            parent = parent.findFile(segment) ?: parent.createDirectory(segment) ?: return false
        }
        return source.renameTo(segments.last())
    }

    override fun delete(name: String): Boolean = resolve(name)?.delete() == true

    override fun list(directoryName: String): List<String>? =
        resolve(directoryName)?.takeIf { it.isDirectory }?.listFiles()?.mapNotNull { it.name }
}

/**
 * TachiyomiAT: crash-safe document publication (lifecycle contract §§14–15).
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
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exists(name: String): Boolean = io.exists(name)

    fun publish(name: String, bytes: ByteArray, validate: (ByteArray) -> Boolean): Boolean {
        val tempName = tempNameFor(name)
        val backupName = backupNameFor(name)
        if (!io.write(tempName, bytes)) return false
        val written = io.read(tempName)
        if (written == null || !written.contentEquals(bytes) || !validate(written)) {
            io.delete(tempName)
            return false
        }
        io.delete(backupName)
        if (io.exists(name) && !io.rename(name, backupName)) {
            io.delete(tempName)
            return false
        }
        if (!io.rename(tempName, name)) {
            if (io.exists(backupName)) io.rename(backupName, name)
            return false
        }
        return true
    }

    inline fun <reified T> publishJson(name: String, value: T): Boolean = publish(
        name,
        json.encodeToString(value).toByteArray(Charsets.UTF_8),
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
        val quarantineName = corruptNameFor(name)
        io.delete(quarantineName)
        val hadPrimary = io.exists(name)
        val quarantined = !hadPrimary || io.rename(name, quarantineName)
        if (quarantined && io.rename(backupName, name)) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT chapter document recovered from backup: name=$name quarantined=$quarantineName"
            }
            return true
        }
        if (quarantined && hadPrimary) {
            io.rename(quarantineName, name)
        }
        return false
    }

    fun delete(name: String): Boolean = io.delete(name)

    fun rawIo(): ChapterDocumentIo = io

    companion object {
        fun tempNameFor(name: String): String = "$name.tmp"
        fun backupNameFor(name: String): String = "$name.bak"
        fun corruptNameFor(name: String): String = "$name.corrupt"
    }
}
