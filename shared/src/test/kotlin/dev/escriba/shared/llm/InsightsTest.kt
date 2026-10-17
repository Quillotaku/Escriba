package dev.escriba.shared.llm

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsightsTest {

    @Test
    fun `reads the model's JSON answer`() {
        val json = """
            {"title": "Review with Acme", "summary": "The migration is reviewed.\n- Point 1",
             "decisions": ["Migrate in November"],
             "tasks": [{"task": "Send the quote", "owner": "Ana", "due": "2026-10-20"},
                       {"task": "Review the contract", "owner": null, "due": null}],
             "people": ["Ana"], "projects": [], "clients": ["Acme"], "tags": ["migration"], "trivial": false}
        """
        val i = Insights.parse(json)
        assertEquals("Review with Acme", i.title)
        assertEquals(2, i.tasks.size)
        assertEquals("2026-10-20", i.tasks[0].due)
        assertNull(i.tasks[1].owner)
        assertEquals(listOf("Acme"), i.clients)
    }

    @Test
    fun `the grammar declares every field in the data model's order`() {
        val order = listOf("title", "summary", "decisions", "tasks", "people", "projects", "clients", "tags", "trivial")
        val positions = order.map { Grammars.INSIGHTS.indexOf("\\\"$it\\\":") }
        assertTrue(positions.all { it > 0 }, positions.toString())
        assertEquals(positions.sorted(), positions)
        assertTrue(Grammars.INSIGHTS.startsWith("root ::="))
        assertTrue("string ::=" in Grammars.MERGED)
    }

    @Test
    fun `spoken dates and validation`() {
        val today = LocalDate.of(2026, 10, 16)
        assertEquals("Friday, October 16, 2026", Dates.spoken(today))
        assertEquals("2026-10-23", Dates.validate("2026-10-23", today))
        assertNull(Dates.validate("2026-02-30", today)) // doesn't exist
        assertNull(Dates.validate("2019-01-01", today)) // far in the past: made up
        assertNull(Dates.validate(null, today))
    }

    @Test
    fun `links only to existing notes`() {
        val linker = EntityLinker(listOf("Ana López", "Pedro Martínez", "Pedro Gómez", "Acme"))
        assertEquals("Ana López", linker.match("ana lopez"))
        assertEquals("Ana López", linker.match("Ana")) // the only Ana
        assertNull(linker.match("Pedro")) // ambiguous: no guessing
        assertEquals("[[Acme]]", linker.linkOrPlain("ACME"))
        assertEquals("Lucía", linker.linkOrPlain(" Lucía "))
    }

    @Test
    fun `merging chunks without duplicates`() {
        val a = Insights("A", "s1", listOf("Decide X"), listOf(Task("Send report", "Ana")), listOf("Ana"), tags = listOf("x"))
        val b = Insights("B", "s2", listOf("decide x"), listOf(Task("send report"), Task("Call")), listOf("ana", "Luis"), trivial = true)
        val m = InsightsMerger.merge(listOf(a, b), MergedSummary("Title", "Summary"))
        assertEquals("Title", m.title)
        assertEquals(listOf("Decide X"), m.decisions)
        assertEquals(listOf("Send report", "Call"), m.tasks.map { it.text })
        assertEquals(listOf("Ana", "Luis"), m.people)
        assertEquals(false, m.trivial)
    }
}
