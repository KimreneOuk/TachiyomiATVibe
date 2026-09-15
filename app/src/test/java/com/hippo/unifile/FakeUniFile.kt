package com.hippo.unifile

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Test-only [UniFile] backed by a plain [File] tree. Lives in this package so
 * it can reach the package-private parent constructor; JVM unit tests cannot
 * use [UniFile.fromFile] because unifile's [RawFile] touches
 * android.text.TextUtils. No android framework method is ever invoked.
 */
class FakeUniFile(
    parent: UniFile?,
    private val backing: File,
) : UniFile(parent) {

    override fun createFile(displayName: String): UniFile? {
        val child = File(backing, displayName)
        if (child.exists()) return null
        return if (child.createNewFile()) FakeUniFile(this, child) else null
    }

    override fun createDirectory(displayName: String): UniFile? {
        val child = File(backing, displayName)
        if (child.exists()) return null
        return if (child.mkdirs()) FakeUniFile(this, child) else null
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
        return children.map { FakeUniFile(this, it) }.toTypedArray()
    }

    override fun listFiles(filter: FilenameFilter?): Array<UniFile>? {
        val children = backing.listFiles() ?: return null
        return children.filter { filter == null || filter.accept(this, it.name) }
            .map { FakeUniFile(this, it) }
            .toTypedArray()
    }

    override fun findFile(displayName: String): UniFile? {
        val child = File(backing, displayName)
        return if (child.exists()) FakeUniFile(this, child) else null
    }

    override fun renameTo(displayName: String): Boolean {
        val target = File(backing.parentFile ?: return false, displayName)
        return backing.renameTo(target)
    }

    override fun openOutputStream(): OutputStream = FileOutputStream(backing)

    override fun openOutputStream(append: Boolean): OutputStream = FileOutputStream(backing, append)

    override fun openInputStream(): InputStream = FileInputStream(backing)

    override fun createRandomAccessFile(mode: String): UniRandomAccessFile =
        throw UnsupportedOperationException("not needed in tests")
}
