package dev.escriba.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfilePaletteTest {

    @Test
    fun `each palette color finds itself`() {
        ProfilePalette.LIGHT.forEachIndexed { i, rgb -> assertEquals(i, ProfilePalette.nearest("#%06X".format(rgb))) }
    }

    @Test
    fun `vault colors land on the closest one`() {
        assertEquals(0, ProfilePalette.nearest("#2E7D32")) // green → pine
        assertEquals(1, ProfilePalette.nearest("#1565C0")) // blue → lapis
        assertEquals(2, ProfilePalette.nearest("#F9A825")) // yellow → ochre
        assertEquals(5, ProfilePalette.nearest("#d84315")) // orange → terracotta
        assertEquals(3, ProfilePalette.nearest("#8E24AA")) // purple → plum
        assertEquals(4, ProfilePalette.nearest("#9E9E9E")) // grey → slate
        assertEquals(4, ProfilePalette.nearest("#FFFFFF")) // white → slate
        assertEquals(4, ProfilePalette.nearest("#607D8B")) // Material's blue grey → slate
    }

    @Test
    fun `what isn't a color has no position`() {
        assertNull(ProfilePalette.nearest(null))
        assertNull(ProfilePalette.nearest("red"))
        assertNull(ProfilePalette.nearest("#12345"))
    }
}
