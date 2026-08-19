package eu.kanade.translation.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RollingContextManagerTest {

    @Test
    fun `rolling context accumulates glossary and retains latest micro summary`() {
        val manager = RollingContextManager()
        assertEquals(emptyMap<String, String>(), manager.getRollingContext().glossary)
        assertEquals("", manager.getRollingContext().microSummary)

        manager.updateContext(
            newGlossary = mapOf("Jin-Woo" to "Male protagonist", "Shadow" to "Ability"),
            newMicroSummary = "Jin-Woo enters the double dungeon with his party.",
        )

        val packet1 = manager.getRollingContext()
        assertEquals(2, packet1.glossary.size)
        assertEquals("Jin-Woo enters the double dungeon with his party.", packet1.microSummary)

        // Chunk 2 updates
        manager.updateContext(
            newGlossary = mapOf("Statue" to "Boss monster"),
            newMicroSummary = "The giant statue awakens and attacks the raid group.",
        )

        val packet2 = manager.getRollingContext()
        assertEquals(3, packet2.glossary.size)
        assertEquals("Male protagonist", packet2.glossary["Jin-Woo"])
        assertEquals("Boss monster", packet2.glossary["Statue"])
        assertEquals("The giant statue awakens and attacks the raid group.", packet2.microSummary)
    }

    @Test
    fun `toPromptContext correctly serializes non-empty glossary and micro-summary into prompt string`() {
        val packet = RollingContextPacket(
            glossary = linkedMapOf("Jin-Woo" to "Male protagonist", "Igris" to "Shadow Knight"),
            microSummary = "Jin-Woo encounters the red knight.",
        )
        val promptContext = packet.toPromptContext()
        assertTrue(promptContext.contains("Previous Scene Summary: Jin-Woo encounters the red knight."))
        assertTrue(promptContext.contains("Established Glossary:"))
        assertTrue(promptContext.contains("- Jin-Woo: Male protagonist"))
        assertTrue(promptContext.contains("- Igris: Shadow Knight"))
    }

    @Test
    fun `toPromptContext returns empty string when glossary and micro-summary are empty`() {
        val packet = RollingContextPacket()
        assertEquals("", packet.toPromptContext())

        val whitespaceSummaryPacket = RollingContextPacket(microSummary = "   ")
        assertEquals("", whitespaceSummaryPacket.toPromptContext())
    }

    @Test
    fun `reset clears accumulated glossary and micro-summary`() {
        val manager = RollingContextManager()
        manager.updateContext(
            newGlossary = mapOf("Dungeon" to "Instance"),
            newMicroSummary = "Inside the gate.",
        )
        assertEquals(1, manager.getRollingContext().glossary.size)
        assertEquals("Inside the gate.", manager.getRollingContext().microSummary)

        manager.reset()
        assertEquals(emptyMap<String, String>(), manager.getRollingContext().glossary)
        assertEquals("", manager.getRollingContext().microSummary)
        assertEquals("", manager.getRollingContext().toPromptContext())
    }
}
