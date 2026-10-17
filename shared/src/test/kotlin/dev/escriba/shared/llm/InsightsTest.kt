package dev.escriba.shared.llm

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsightsTest {

    @Test
    fun `lee la respuesta JSON del modelo`() {
        val json = """
            {"titulo": "Revisión con Acme", "resumen": "Se repasa la migración.\n- Punto 1",
             "decisiones": ["Migrar en noviembre"],
             "tareas": [{"tarea": "Enviar presupuesto", "responsable": "Ana", "fecha": "2026-10-20"},
                        {"tarea": "Revisar contrato", "responsable": null, "fecha": null}],
             "personas": ["Ana"], "proyectos": [], "clientes": ["Acme"], "etiquetas": ["migración"], "trivial": false}
        """
        val i = Insights.parse(json)
        assertEquals("Revisión con Acme", i.title)
        assertEquals(2, i.tasks.size)
        assertEquals("2026-10-20", i.tasks[0].due)
        assertNull(i.tasks[1].owner)
        assertEquals(listOf("Acme"), i.clients)
    }

    @Test
    fun `la gramatica declara todos los campos en el orden del modelo de datos`() {
        val order = listOf("titulo", "resumen", "decisiones", "tareas", "personas", "proyectos", "clientes", "etiquetas", "trivial")
        val positions = order.map { Grammars.INSIGHTS.indexOf("\\\"$it\\\":") }
        assertTrue(positions.all { it > 0 }, positions.toString())
        assertEquals(positions.sorted(), positions)
        assertTrue(Grammars.INSIGHTS.startsWith("root ::="))
        assertTrue("string ::=" in Grammars.MERGED)
    }

    @Test
    fun `fechas habladas y validacion`() {
        val today = LocalDate.of(2026, 10, 16)
        assertEquals("viernes, 16 de octubre de 2026", Dates.spoken(today))
        assertEquals("2026-10-23", Dates.validate("2026-10-23", today))
        assertNull(Dates.validate("2026-02-30", today)) // no existe
        assertNull(Dates.validate("2019-01-01", today)) // pasado lejano: inventada
        assertNull(Dates.validate(null, today))
    }

    @Test
    fun `enlaza solo con notas existentes`() {
        val linker = EntityLinker(listOf("Ana López", "Pedro Martínez", "Pedro Gómez", "Acme"))
        assertEquals("Ana López", linker.match("ana lopez"))
        assertEquals("Ana López", linker.match("Ana")) // única Ana
        assertNull(linker.match("Pedro")) // ambiguo: no se adivina
        assertEquals("[[Acme]]", linker.linkOrPlain("ACME"))
        assertEquals("Lucía", linker.linkOrPlain(" Lucía "))
    }

    @Test
    fun `fusion de trozos sin repetidos`() {
        val a = Insights("A", "r1", listOf("Decidir X"), listOf(Task("Enviar informe", "Ana")), listOf("Ana"), tags = listOf("x"))
        val b = Insights("B", "r2", listOf("decidir x"), listOf(Task("enviar informe"), Task("Llamar")), listOf("ana", "Luis"), trivial = true)
        val m = InsightsMerger.merge(listOf(a, b), MergedSummary("Título", "Resumen"))
        assertEquals("Título", m.title)
        assertEquals(listOf("Decidir X"), m.decisions)
        assertEquals(listOf("Enviar informe", "Llamar"), m.tasks.map { it.text })
        assertEquals(listOf("Ana", "Luis"), m.people)
        assertEquals(false, m.trivial)
    }
}
