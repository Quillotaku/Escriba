package dev.escriba.mobile.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/** El nombre de archivo de un perfil nuevo creado desde la app. */
class ProfileSlugTest {

    @Test
    fun `sin acentos ni espacios ni mayusculas`() {
        assertEquals("reuniones-acme", profileSlug("Reuniones Acme"))
        assertEquals("ideas-rapidas", profileSlug("Ideas Rápidas"))
        assertEquals("cafe-con-ana", profileSlug("  Café con Ana!  "))
        assertEquals("ano-2026", profileSlug("Año 2026"))
    }

    @Test
    fun `sin letras validas queda vacio y la app usa perfil`() {
        assertEquals("", profileSlug("¿?"))
    }
}
