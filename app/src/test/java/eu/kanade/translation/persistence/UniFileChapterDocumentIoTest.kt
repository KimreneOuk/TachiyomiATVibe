package eu.kanade.translation.persistence

import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class UniFileChapterDocumentIoTest {

    @Test
    fun `rename resolves a fresh destination instead of trusting an existing moved handle`() {
        val tree = RenamedHandleTree()
        val io = UniFileChapterDocumentIo(tree.root)
        io.write("manifest", "old".toByteArray()) shouldBe true
        io.read("manifest") shouldBe "old".toByteArray()

        io.renameOwned("manifest", "manifest.bak") shouldBe true
        tree.files["manifest.bak"] = "fresh".toByteArray()

        io.read("manifest.bak") shouldBe "fresh".toByteArray()
        io.exists("manifest") shouldBe false
        tree.listCalls shouldBe 0
    }

    @Test
    fun `overwriting an existing name refreshes an indexed absence`() {
        val tree = RenamedHandleTree()
        val io = UniFileChapterDocumentIo(tree.root)
        io.exists("manifest.bak") shouldBe false
        tree.files["manifest.bak"] = "old".toByteArray()

        io.write("manifest.bak", "fresh".toByteArray()) shouldBe true

        tree.files["manifest.bak"] shouldBe "fresh".toByteArray()
        io.read("manifest.bak") shouldBe "fresh".toByteArray()
        tree.listCalls shouldBe 1
    }

    @Test
    fun `verified publication stages a rotated backup without a directory relist or retry`() {
        val tree = RenamedHandleTree()
        val io = UniFileChapterDocumentIo(tree.root)
        val documents = AtomicChapterDocuments(io)
        documents.publish("manifest", "old state".toByteArray()) { true } shouldBe true
        tree.writes.clear()
        tree.deletes.clear()

        val receipt = documents.publishVerified("manifest", "new".toByteArray()) { true }

        tree.files["manifest"] shouldBe "new".toByteArray()
        tree.files["manifest.bak"] shouldBe "new".toByteArray()
        receipt shouldBe AtomicChapterDocuments.VerifiedPublishReceipt.Complete(3)
        io.read("manifest") shouldBe "new".toByteArray()
        io.read("manifest.bak") shouldBe "new".toByteArray()
        tree.writes shouldBe listOf("manifest.tmp", "manifest.bak.tmp")
        tree.deletes shouldBe listOf("manifest.bak.bak")
        tree.listCalls shouldBe 1
    }

    @Test
    fun `verified publication replaces a longer backup on a non-truncating provider`() {
        val tree = RenamedHandleTree().apply { nonTruncatingWrites = true }
        val io = UniFileChapterDocumentIo(tree.root)
        val documents = AtomicChapterDocuments(io)
        documents.publish("manifest", "old longer state".toByteArray()) { true } shouldBe true

        val receipt = documents.publishVerified("manifest", "new".toByteArray()) { true }

        io.read("manifest") shouldBe "new".toByteArray()
        receipt shouldBe AtomicChapterDocuments.VerifiedPublishReceipt.Complete(3)
        io.read("manifest.bak") shouldBe "new".toByteArray()
    }

    @Test
    fun `failed mid-write backup copy retains the old backup bytes`() {
        val tree = RenamedHandleTree().apply { nonTruncatingWrites = true }
        val io = UniFileChapterDocumentIo(tree.root)
        val documents = AtomicChapterDocuments(io)
        val old = "old longer state".toByteArray()
        documents.publish("manifest", old) { true } shouldBe true
        tree.failWritesMatching = { it.contains(".bak") }

        val receipt = documents.publishVerified("manifest", "new".toByteArray()) { true }

        io.read("manifest") shouldBe "new".toByteArray()
        receipt shouldBe AtomicChapterDocuments.VerifiedPublishReceipt.BackupNotVerified(
            3,
            "same-state backup copy failed write/readback: name=manifest",
        )
        io.read("manifest.bak") shouldBe old
    }

    @Test
    fun `failed backup promotion restores the previous backup at its original name`() {
        val tree = RenamedHandleTree().apply { nonTruncatingWrites = true }
        val io = UniFileChapterDocumentIo(tree.root)
        val documents = AtomicChapterDocuments(io)
        val old = "old longer state".toByteArray()
        documents.publish("manifest", old) { true } shouldBe true
        tree.failedRenames += "manifest.bak.tmp"

        val receipt = documents.publishVerified("manifest", "new".toByteArray()) { true }

        io.read("manifest") shouldBe "new".toByteArray()
        receipt shouldBe AtomicChapterDocuments.VerifiedPublishReceipt.BackupNotVerified(
            3,
            "same-state backup copy failed write/readback: name=manifest",
        )
        io.read("manifest.bak") shouldBe old
        io.exists("manifest.bak.bak") shouldBe false
    }

    @Test
    fun `orphaned longer backup staging bytes cannot poison the next publication`() {
        val tree = RenamedHandleTree().apply { nonTruncatingWrites = true }
        val io = UniFileChapterDocumentIo(tree.root)
        val documents = AtomicChapterDocuments(io)
        documents.publish("manifest", "old longer state".toByteArray()) { true } shouldBe true
        tree.files["manifest.bak.tmp"] = "orphaned incomplete longer bytes".toByteArray()

        val receipt = documents.publishVerified("manifest", "new".toByteArray()) { true }

        io.read("manifest") shouldBe "new".toByteArray()
        receipt shouldBe AtomicChapterDocuments.VerifiedPublishReceipt.Complete(3)
        io.read("manifest.bak") shouldBe "new".toByteArray()
        io.exists("manifest.bak.tmp") shouldBe false
    }

    @Test
    fun `failed promotion and rollback retain the old backup under the rotation sibling`() {
        val tree = RenamedHandleTree().apply { nonTruncatingWrites = true }
        val io = UniFileChapterDocumentIo(tree.root)
        val documents = AtomicChapterDocuments(io)
        val old = "old longer state".toByteArray()
        documents.publish("manifest", old) { true } shouldBe true
        tree.failedRenames += setOf("manifest.bak.tmp", "manifest.bak.bak")

        val receipt = documents.publishVerified("manifest", "new".toByteArray()) { true }

        io.read("manifest") shouldBe "new".toByteArray()
        receipt shouldBe AtomicChapterDocuments.VerifiedPublishReceipt.BackupNotVerified(
            3,
            "same-state backup copy failed write/readback: name=manifest",
        )
        io.exists("manifest.bak") shouldBe false
        io.read("manifest.bak.bak") shouldBe old
    }

    private class RenamedHandleTree {
        val files = mutableMapOf<String, ByteArray>()
        val writes = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        val failedRenames = mutableSetOf<String>()
        var listCalls = 0
        var nonTruncatingWrites = false
        var failWritesMatching: ((String) -> Boolean)? = null
        val root = mockk<UniFile>()

        init {
            every { root.listFiles() } answers {
                listCalls++
                files.keys.map(::handle).toTypedArray()
            }
            every { root.findFile(any()) } answers {
                firstArg<String>().takeIf(files::containsKey)?.let(::handle)
            }
            every { root.createFile(any()) } answers {
                val name = firstArg<String>()
                files[name] = byteArrayOf()
                handle(name)
            }
        }

        private fun handle(initialName: String): UniFile {
            var name = initialName
            var renamedBytes: ByteArray? = null
            return mockk<UniFile>().also { file ->
                every { file.name } answers { name }
                every { file.isFile } returns true
                every { file.exists() } answers { files.containsKey(name) }
                every { file.openInputStream() } answers {
                    (renamedBytes ?: files.getValue(name)).inputStream()
                }
                every { file.openOutputStream() } answers {
                    val previous = if (nonTruncatingWrites) files.getValue(name) else byteArrayOf()
                    files[name] = previous
                    object : ByteArrayOutputStream() {
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            val fail = failWritesMatching?.invoke(name) == true
                            super.write(buffer, offset, if (fail) minOf(2, length) else length)
                            val written = toByteArray()
                            files[name] = previous.copyOf(maxOf(previous.size, written.size)).also {
                                written.copyInto(it)
                            }
                            if (fail) throw java.io.IOException("injected mid-write failure: $name")
                        }

                        override fun close() {
                            writes += name
                            super.close()
                        }
                    }
                }
                every { file.renameTo(any()) } answers {
                    if (name in failedRenames) {
                        false
                    } else {
                        val bytes = files.remove(name)!!
                        name = firstArg()
                        files[name] = bytes
                        // Some provider handles survive rename but retain the old content view.
                        renamedBytes = bytes
                        true
                    }
                }
                every { file.delete() } answers {
                    deletes += name
                    files.remove(name) != null
                }
            }
        }
    }
}
