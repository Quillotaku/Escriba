package dev.escriba.mobile.ui.nav

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.ui.sessions.SessionDetailScreen
import dev.escriba.mobile.ui.sessions.SessionsScreen
import dev.escriba.mobile.ui.settings.BatteryPage
import dev.escriba.mobile.ui.settings.BenchmarkPage
import dev.escriba.mobile.ui.settings.ModelsPage
import dev.escriba.mobile.ui.settings.ProfileEditorPage
import dev.escriba.mobile.ui.settings.StoragePage
import dev.escriba.mobile.ui.settings.VaultPage
import dev.escriba.mobile.ui.settings.LogPage
import dev.escriba.mobile.ui.settings.SettingsPage
import dev.escriba.mobile.ui.settings.SettingsScreen
import dev.escriba.mobile.ui.settings.SystemActions
import dev.escriba.mobile.ui.settings.VoicesPage
import dev.escriba.mobile.ui.settings.WatchPage
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.ui.today.TodayScreen
import dev.escriba.mobile.ui.dashboard.DashboardScreen
import dev.escriba.mobile.ui.components.EscribaIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.scale

/** Destinos de la app. Las tres pestañas tienen cada una su propia pila. */
sealed interface Route : NavKey {
    data object Today : Route
    data object Sessions : Route
    data object Dashboard : Route
    data object Settings : Route
    data class Session(val id: String, val focus: Int) : Route
    data class Setting(val page: SettingsPage) : Route
    data class ProfileEdit(val id: String) : Route
}

internal enum class Tab(val root: Route, val label: String, val icon: ImageVector) {
    TODAY(Route.Today, "Hoy", Icons.Rounded.Home),
    SESSIONS(Route.Sessions, "Sesiones", Icons.AutoMirrored.Rounded.List),
    DASHBOARD(Route.Dashboard, "Panel", EscribaIcons.Chart),
    SETTINGS(Route.Settings, "Ajustes", Icons.Rounded.Settings),
}

/**
 * Pila por pestaña. Atrás retrocede dentro de la pestaña; en la raíz de «Sesiones» o «Ajustes»
 * vuelve a «Hoy», y desde la raíz de «Hoy» sale de la app.
 */
internal class Navigator {
    private val stacks = mutableStateMapOf<Tab, SnapshotStateList<Route>>()
    var tab by mutableStateOf(Tab.TODAY)
        private set

    fun stack(t: Tab = tab): SnapshotStateList<Route> = stacks.getOrPut(t) { listOf(t.root).toMutableStateList() }

    fun select(t: Tab) {
        // Pulsar la pestaña en la que ya estás vuelve a su raíz.
        if (t == tab) stack(t).let { s -> while (s.size > 1) s.removeAt(s.lastIndex) }
        tab = t
    }

    fun push(route: Route) {
        stack().add(route)
    }

    /** `false` si no queda nada atrás y hay que salir de la app. */
    fun back(): Boolean {
        val s = stack()
        return when {
            s.size > 1 -> { s.removeAt(s.lastIndex); true }
            tab != Tab.TODAY -> { tab = Tab.TODAY; true }
            else -> false
        }
    }
}

private fun title(route: Route): String? = when (route) {
    Route.Today -> null
    Route.Sessions -> "Sesiones"
    Route.Dashboard -> "Panel"
    Route.Settings -> "Ajustes"
    is Route.Session -> "Sesión"
    is Route.ProfileEdit -> "Perfil"
    is Route.Setting -> when (route.page) {
        SettingsPage.WATCH -> "Reloj"
        SettingsPage.VAULT -> "Bóveda y perfiles"
        SettingsPage.VOICES -> "Voces registradas"
        SettingsPage.MODELS -> "Modelos"
        SettingsPage.BENCHMARK -> "Pruebas de rendimiento"
        SettingsPage.STORAGE -> "Almacenamiento"
        SettingsPage.BATTERY -> "Batería"
        SettingsPage.LOG -> "Registro"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(app: EscribaApp, actions: SystemActions) {
    val nav = remember { Navigator() }
    val activity = LocalActivity.current
    val c = Escriba.colors
    val stack = nav.stack()
    val current = stack.last()

    Scaffold(
        containerColor = c.paper,
        topBar = {
            val t = title(current)
            if (t != null) {
                TopAppBar(
                    title = { Text(t, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        if (stack.size > 1) IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = c.paper, titleContentColor = c.ink, navigationIconContentColor = c.ink),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = c.paperRaised) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = nav.tab == t,
                        onClick = { nav.select(t) },
                        icon = {
                            val scale by animateFloatAsState(
                                targetValue = if (nav.tab == t) 1.15f else 1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                label = "icono",
                            )
                            Icon(t.icon, null, Modifier.scale(scale))
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.lapis, selectedTextColor = c.lapis, indicatorColor = c.lapisSoft,
                            unselectedIconColor = c.inkMuted, unselectedTextColor = c.inkMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // Cada pestaña muestra su propia pila; al cambiar de pestaña se conserva lo que había.
        // Cambiar de pestaña desliza hacia su lado; dentro de una pestaña, entrar empuja y volver retira.
        AnimatedContent(
            targetState = nav.tab,
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(260)) { direction * it / 6 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -direction * it / 6 } + fadeOut(tween(160)))
            },
            label = "pestañas",
        ) { tab ->
        NavDisplay(
            backStack = nav.stack(tab),
            onBack = { if (!nav.back()) activity?.finish() },
            transitionSpec = {
                (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(200))).togetherWith(fadeOut(tween(150)))
            },
            popTransitionSpec = {
                fadeIn(tween(200)).togetherWith(slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)))
            },
            predictivePopTransitionSpec = {
                fadeIn(tween(200)).togetherWith(slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)))
            },
            entryProvider = entryProvider {
                entry<Route.Today> {
                    TodayScreen(
                        app, padding,
                        openSession = { id, i -> nav.push(Route.Session(id, i)) },
                        openWatch = {
                            nav.select(Tab.SETTINGS)
                            nav.push(Route.Setting(SettingsPage.WATCH))
                        },
                        openPanel = { nav.select(Tab.DASHBOARD) },
                    )
                }
                entry<Route.Dashboard> { DashboardScreen(app, padding) { id, i -> nav.push(Route.Session(id, i)) } }
                entry<Route.Sessions> { SessionsScreen(app, padding) { id, i -> nav.push(Route.Session(id, i)) } }
                entry<Route.Settings> { SettingsScreen(app, padding) { page -> nav.push(Route.Setting(page)) } }
                entry<Route.Session> { r -> SessionDetailScreen(app, r.id, r.focus, padding) { nav.back() } }
                entry<Route.ProfileEdit> { r -> ProfileEditorPage(app, r.id, padding) { nav.back() } }
                entry<Route.Setting> { r ->
                    when (r.page) {
                        SettingsPage.WATCH -> WatchPage(app, padding)
                        SettingsPage.VAULT -> VaultPage(app, padding) { id -> nav.push(Route.ProfileEdit(id)) }
                        SettingsPage.VOICES -> VoicesPage(app, padding)
                        SettingsPage.MODELS -> ModelsPage(app, padding) { nav.push(Route.Setting(SettingsPage.BENCHMARK)) }
                        SettingsPage.BENCHMARK -> BenchmarkPage(app, padding)
                        SettingsPage.STORAGE -> StoragePage(app, padding)
                        SettingsPage.BATTERY -> BatteryPage(actions, padding)
                        SettingsPage.LOG -> LogPage(padding)
                    }
                }
            },
        )
        }
    }
}
