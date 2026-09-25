package eu.kanade.translation.artifact

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class AtomicChapterDocumentsTest {

    @Serializable
    data class Doc(val value: String)

    private fun documents(io: FakeChapterDocumentIo = FakeChapterDocumentIo()) = AtomicChapterDocuments(io)

    @Test
    fun `publish then read round-trips`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("chapter.manifest.json", Doc("one")) shouldBe true
        docs.readValidated<Doc>("chapter.manifest.json") shouldBe Doc("one")
        io.files.keys shouldBe setOf("chapter.manifest.json")
    }

    @Test
    fun `URI-backed owned publication supports primary and backup rotation`() {
        val io = FakeChapterDocumentIo().apply { supportsNoReplaceRename = false }
        val docs = documents(io)

        docs.publishJson("uri.manifest.json", Doc("one")) shouldBe true
        docs.publishJson("uri.manifest.json", Doc("two")) shouldBe true

        docs.readValidated<Doc>("uri.manifest.json") shouldBe Doc("two")
        io.files["uri.manifest.json.bak"] shouldNotBe null
        io.renamed shouldBe listOf(
            "uri.manifest.json.tmp" to "uri.manifest.json",
            "uri.manifest.json" to "uri.manifest.json.bak",
            "uri.manifest.json.tmp" to "uri.manifest.json",
        )
    }

    @Test
    fun `URI-backed owned publication rolls back after promotion failure`() {
        val io = FakeChapterDocumentIo().apply { supportsNoReplaceRename = false }
        val docs = documents(io)
        docs.publishJson("uri.manifest.json", Doc("one")) shouldBe true

        io.ownedRenamesToFail += "uri.manifest.json.tmp"
        docs.publishJson("uri.manifest.json", Doc("two")) shouldBe false
        docs.readValidated<Doc>("uri.manifest.json") shouldBe Doc("one")
        io.files["uri.manifest.json.tmp"] shouldNotBe null

        io.ownedRenamesToFail.clear()
        docs.publishJson("uri.manifest.json", Doc("two")) shouldBe true
        docs.readValidated<Doc>("uri.manifest.json") shouldBe Doc("two")
    }

    @Test
    fun `second publish retains the previous version as backup`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("m.json", Doc("one")) shouldBe true
        docs.publishJson("m.json", Doc("two")) shouldBe true
        io.files["m.json.bak"] shouldNotBe null
        docs.readValidated<Doc>("m.json") shouldBe Doc("two")
    }

    @Test
    fun `validation failure leaves the previous primary intact`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("m.json", Doc("good")) shouldBe true
        val before = io.files["m.json"]
        docs.publish("m.json", "not json".toByteArray()) { false } shouldBe false
        io.files["m.json"] shouldBe before
        io.files.containsKey("m.json.tmp") shouldBe false
        docs.readValidated<Doc>("m.json") shouldBe Doc("good")
    }

    @Test
    fun `write failure aborts publication without touching the primary`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("m.json", Doc("good")) shouldBe true
        io.failWrites = true
        docs.publishJson("m.json", Doc("bad")) shouldBe false
        docs.readValidated<Doc>("m.json") shouldBe Doc("good")
    }

    @Test
    fun `corrupt primary is recovered from backup and quarantined`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("m.json", Doc("one")) shouldBe true
        docs.publishJson("m.json", Doc("two")) shouldBe true
        io.files["m.json"] = "{ corrupted".toByteArray()
        // Recovery serves the retained backup ("one") and quarantines the
        // corrupt payload instead of deleting it.
        docs.readValidated<Doc>("m.json") shouldBe Doc("one")
        String(io.files["m.json.corrupt"]!!) shouldBe "{ corrupted"
    }

    @Test
    fun `backup recovery does not replace a destination inserted after admission`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("m.json", Doc("one")) shouldBe true
        docs.publishJson("m.json", Doc("two")) shouldBe true
        io.files["m.json"] = "{ corrupted".toByteArray()
        var inserted = false
        io.beforeRenameAttempt = { from, to ->
            if (!inserted && from == "m.json.bak" && to == "m.json") {
                io.files[to] = "external-primary".toByteArray()
                inserted = true
            }
        }

        docs.readValidated<Doc>("m.json") shouldBe Doc("one")
        io.files["m.json"] shouldBe "external-primary".toByteArray()
        io.files["m.json.bak"] shouldNotBe null
        io.files["m.json.corrupt"] shouldBe "{ corrupted".toByteArray()
    }

    @Test
    fun `quarantine exhaustion retains the corrupt primary and backup`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("m.json", Doc("one")) shouldBe true
        docs.publishJson("m.json", Doc("two")) shouldBe true
        val corrupt = "{ corrupted".toByteArray()
        io.files["m.json"] = corrupt
        io.beforeRenameAttempt = { from, to ->
            if (from == "m.json") io.files[to] = "occupied".toByteArray()
        }

        docs.readValidated<Doc>("m.json") shouldBe Doc("one")
        io.files["m.json"] shouldBe corrupt
        io.files["m.json.bak"] shouldNotBe null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(corrupt)
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(8)
        io.files.keys.none { it == "m.json.corrupt.$digest.17" } shouldBe true
    }

    @Test
    fun `missing primary falls back to retained backup`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("m.json", Doc("one")) shouldBe true
        docs.publishJson("m.json", Doc("two")) shouldBe true
        io.files.remove("m.json")
        docs.readValidated<Doc>("m.json") shouldBe Doc("one")
        io.files.containsKey("m.json") shouldBe true
    }

    @Test
    fun `primary failing semantic validation recovers the passing backup`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("m.json", Doc("one")) shouldBe true
        docs.publishJson("m.json", Doc("two")) shouldBe true
        docs.readValidated<Doc>("m.json") { it.value == "one" } shouldBe Doc("one")
        io.files["m.json"] shouldNotBe io.files["m.json.bak"]
    }

    @Test
    fun `returns null when neither primary nor backup validates`() {
        val io = FakeChapterDocumentIo()
        val docs = documents(io)
        docs.publishJson("m.json", Doc("one")) shouldBe true
        docs.publishJson("m.json", Doc("two")) shouldBe true
        docs.readValidated<Doc>("m.json") { false } shouldBe null
    }
}
