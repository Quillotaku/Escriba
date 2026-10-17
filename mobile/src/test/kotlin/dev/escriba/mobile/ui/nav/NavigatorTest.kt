package dev.escriba.mobile.ui.nav

import dev.escriba.mobile.ui.settings.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pilas por pestaña y el botón Atrás. */
class NavigatorTest {

    private val nav = Navigator()

    @Test
    fun `atras dentro de una pestana y luego a Hoy y luego fuera`() {
        nav.select(Tab.SESSIONS)
        nav.push(Route.Session("s1", 1))
        assertEquals(Route.Session("s1", 1), nav.stack().last())

        assertTrue(nav.back())
        assertEquals(listOf<Route>(Route.Sessions), nav.stack().toList())

        assertTrue(nav.back()) // en la raíz de Sesiones, Atrás vuelve a Hoy
        assertEquals(Tab.TODAY, nav.tab)

        assertFalse(nav.back()) // en la raíz de Hoy, Atrás sale de la app
    }

    @Test
    fun `cada pestana conserva su pila al cambiar`() {
        nav.select(Tab.SETTINGS)
        nav.push(Route.Setting(SettingsPage.MODELS))
        nav.push(Route.Setting(SettingsPage.BENCHMARK))
        nav.select(Tab.SESSIONS)
        nav.select(Tab.SETTINGS)
        assertEquals(Route.Setting(SettingsPage.BENCHMARK), nav.stack().last())
        assertEquals(3, nav.stack().size)
    }

    @Test
    fun `pulsar la pestana en la que estas vuelve a su raiz`() {
        nav.select(Tab.SETTINGS)
        nav.push(Route.Setting(SettingsPage.VAULT))
        nav.push(Route.ProfileEdit("trabajo"))
        nav.select(Tab.SETTINGS)
        assertEquals(listOf<Route>(Route.Settings), nav.stack().toList())
    }
}
