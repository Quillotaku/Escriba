package dev.escriba.mobile.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/** The file name of a new profile created from the app. */
class ProfileSlugTest {

    @Test
    fun `no accents spaces or capitals`() {
        assertEquals("acme-meetings", profileSlug("Acme Meetings"))
        assertEquals("ideas-rapidas", profileSlug("Ideas Rápidas"))
        assertEquals("cafe-with-ana", profileSlug("  Café with Ana!  "))
        assertEquals("ano-2026", profileSlug("Año 2026"))
    }

    @Test
    fun `without valid letters it is empty and the app uses profile`() {
        assertEquals("", profileSlug("¿?"))
    }
}
