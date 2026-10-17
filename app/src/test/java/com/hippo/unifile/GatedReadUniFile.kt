package com.hippo.unifile

import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test-only [UniFile] backed by a plain [File] tree (same recipe as
 * [FakeUniFile]) whose FIRST read of [gatedFileName] signals [beforeGate] and
 * then parks the calling thread on [gate]. Children stay gated so the
 * per-directory document index resolves to gated instances too. Lives in this
 * package to reach the package-private parent constructor. JVM tests only.
 */
class GatedReadUniFile(
    parent: UniFile?,
    private val backing: File,
    private val gatedFileName: String,
    private val beforeGate: CompletableDeferred<Unit>,
    private val gate: CountDownLatch,
) : UniFile(parent) {

    private val gatedOnce = AtomicBoolean(false)

    private fun child(file: File): GatedReadUniFile = GatedReadUniFile(
        parent = this,
        backing = file,
        gatedFileName = gatedFileName,
        beforeGate = beforeGate,
        gate = gate,
    )

    override fun createFile(displayName: String): UniFile? {
        val created = File(backing, displayName)
        if (created.exists()) return null
        return if (created.createNewFile()) child(created) else null
    }

    override fun createDirectory(displayName: String): UniFile? {
        val created = File(backing, displayName)
        if (created.exists()) return null
        return if (created.mkdirs()) child(created) else null
    }

    override fun getUri(): android.net.Uri =
        throw UnsupportedOperationException("getUri is not used in JVM tests")

    override fun getName(): String? = backing.name

    override fun getType(): String? = null

    override fun getFilePath(): String? = backing.absolutePath

    override fun isDirectory(): Boolean = backing.isDirectory

    override fun isFile(): Boolean = backing.isFile

    override fun lastModified(): Long = backing.lastModified()

    override fun length(): Long = backing.length()

    override fun canRead(): Boolean = backing.canRead()

    override fun canWrite(): Boolean = backing.canWrite()

    override fun delete(): Boolean = when {
        !backing.exists() -> false
        backing.isDirectory -> backing.deleteRecursively()
        else -> backing.delete()
    }

    override fun exists(): Boolean = backing.exists()

    override fun listFiles(): Array<UniFile>? {
        val children = backing.listFiles() ?: return null
        return children.map { child(it) }.toTypedArray()
    }

    override fun listFiles(filter: FilenameFilter?): Array<UniFile>? {
        val children = backing.listFiles() ?: return null
        return children.filter { filter == null || filter.accept(this, it.name) }
            .map { child(it) }
            .toTypedArray()
    }

    override fun findFile(displayName: String): UniFile? {
        val childFile = File(backing, displayName)
        return if (childFile.exists()) child(childFile) else null
    }

    override fun renameTo(displayName: String): Boolean {
        val target = File(backing.parentFile ?: return false, displayName)
        return backing.renameTo(target)
    }

    override fun openOutputStream(): OutputStream = FileOutputStream(backing)

    override fun openOutputStream(append: Boolean): OutputStream = FileOutputStream(backing, append)

    override fun openInputStream(): InputStream {
        if (backing.isFile && backing.name == gatedFileName && gatedOnce.compareAndSet(false, true)) {
            beforeGate.complete(Unit)
            gate.await()
        }
        return FileInputStream(backing)
    }

    override fun createRandomAccessFile(mode: String): UniRandomAccessFile =
        throw UnsupportedOperationException("not needed in tests")
}
