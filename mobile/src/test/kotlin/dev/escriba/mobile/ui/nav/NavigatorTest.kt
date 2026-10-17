package dev.escriba.mobile.ui.nav

import dev.escriba.mobile.ui.settings.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-tab stacks and the Back button. */
class NavigatorTest {

    private val nav = Navigator()

    @Test
    fun `back within a tab then to Today then out`() {
        nav.select(Tab.SESSIONS)
        nav.push(Route.Session("s1", 1))
        assertEquals(Route.Session("s1", 1), nav.stack().last())

        assertTrue(nav.back())
        assertEquals(listOf<Route>(Route.Sessions), nav.stack().toList())

        assertTrue(nav.back()) // at the root of Sessions, Back returns to Today
        assertEquals(Tab.TODAY, nav.tab)

        assertFalse(nav.back()) // at the root of Today, Back leaves the app
    }

    @Test
    fun `each tab keeps its stack when switching`() {
        nav.select(Tab.SETTINGS)
        nav.push(Route.Setting(SettingsPage.MODELS))
        nav.push(Route.Setting(SettingsPage.BENCHMARK))
        nav.select(Tab.SESSIONS)
        nav.select(Tab.SETTINGS)
        assertEquals(Route.Setting(SettingsPage.BENCHMARK), nav.stack().last())
        assertEquals(3, nav.stack().size)
    }

    @Test
    fun `tapping the current tab goes back to its root`() {
        nav.select(Tab.SETTINGS)
        nav.push(Route.Setting(SettingsPage.VAULT))
        nav.push(Route.ProfileEdit("work"))
        nav.select(Tab.SETTINGS)
        assertEquals(listOf<Route>(Route.Settings), nav.stack().toList())
    }
}
