package dev.escriba.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfilePaletteTest {

    @Test
    fun `cada color de la paleta se encuentra a si mismo`() {
        ProfilePalette.LIGHT.forEachIndexed { i, rgb -> assertEquals(i, ProfilePalette.nearest("#%06X".format(rgb))) }
    }

    @Test
    fun `los colores de la boveda caen en el mas parecido`() {
        assertEquals(0, ProfilePalette.nearest("#2E7D32")) // verde → pino
        assertEquals(1, ProfilePalette.nearest("#1565C0")) // azul → lapislázuli
        assertEquals(2, ProfilePalette.nearest("#F9A825")) // amarillo → ocre
        assertEquals(5, ProfilePalette.nearest("#d84315")) // naranja → teja
        assertEquals(3, ProfilePalette.nearest("#8E24AA")) // morado → ciruela
        assertEquals(4, ProfilePalette.nearest("#9E9E9E")) // gris → pizarra
        assertEquals(4, ProfilePalette.nearest("#FFFFFF")) // blanco → pizarra
        assertEquals(4, ProfilePalette.nearest("#607D8B")) // gris azulado de Material → pizarra
    }

    @Test
    fun `lo que no es un color no tiene posicion`() {
        assertNull(ProfilePalette.nearest(null))
        assertNull(ProfilePalette.nearest("rojo"))
        assertNull(ProfilePalette.nearest("#12345"))
    }
}
