package dev.escriba.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.PairingState
import dev.escriba.datalayer.Peer
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.ui.EnrolledVoicesCard
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.EscribaIcons
import dev.escriba.mobile.ui.components.Overline
import dev.escriba.mobile.ui.components.SettingRow
import dev.escriba.mobile.ui.components.StatusChip
import dev.escriba.mobile.ui.components.Tone
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.ui.today.HM
import dev.escriba.mobile.ui.today.local
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/** Lo que solo puede hacer la actividad (abrir ajustes del sistema). */
class SystemActions(
    val isBatteryExempt: () -> Boolean,
    val requestBatteryExemption: () -> Unit,
    val openAppSettings: () -> Unit,
)

enum class SettingsPage { WATCH, VAULT, VOICES, MODELS, BENCHMARK, STORAGE, BATTERY, LOG }

/** Columna con desplazamiento y los márgenes del sistema. */
@Composable
fun Page(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = Space.s4, end = Space.s4, top = padding.calculateTopPadding() + Space.s2, bottom = padding.calculateBottomPadding() + Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) { content() }
}

@Composable
fun SettingsScreen(app: EscribaApp, padding: PaddingValues, open: (SettingsPage) -> Unit) {
    val c = Escriba.colors
    val dark by app.appearance.dark.collectAsStateWithLifecycle()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val folder by app.models.folder.collectAsStateWithLifecycle()
    val voices by app.db.sessions().observeVoices().collectAsStateWithLifecycle(emptyList())

    Page(padding) {
        Overline("Apariencia")
        EsCard {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val isDark = dark ?: systemDark
                listOf(false to "Claro", true to "Oscuro").forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = isDark == value,
                        onClick = { app.appearance.set(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
        }
        Overline("Escriba", Modifier.padding(top = Space.s3))
        EsCard(Modifier) {
            val rows = listOf(
                Triple(EscribaIcons.Watch, "Reloj", peer?.let { "Emparejado con ${it.name}" } ?: "Sin emparejar") to SettingsPage.WATCH,
                Triple(Icons.Rounded.Edit, "Bóveda y perfiles", app.vault.displayPath() ?: "Sin elegir") to SettingsPage.VAULT,
                Triple(EscribaIcons.People, "Voces registradas", if (voices.isEmpty()) "Ninguna" else voices.joinToString { it.name }) to SettingsPage.VOICES,
                Triple(EscribaIcons.Waveform, "Modelos", if (folder == null) "Sin carpeta de modelos" else "${app.models.models.value.size} modelos") to SettingsPage.MODELS,
                Triple(Icons.Rounded.Delete, "Almacenamiento", "Audio guardado y cuándo se borra") to SettingsPage.STORAGE,
                Triple(EscribaIcons.Battery, "Batería", "Que el sistema no cierre Escriba") to SettingsPage.BATTERY,
                Triple(EscribaIcons.Notes, "Registro", "Lo que ha hecho la app, para diagnosticar") to SettingsPage.LOG,
            )
            rows.forEachIndexed { i, (row, page) ->
                if (i > 0) HorizontalDivider(color = c.rule)
                SettingRow(row.first, row.second, row.third, onClick = { open(page) })
            }
        }
        EsCard {
            SettingRow(Icons.Rounded.PlayArrow, "Tour de inicio", "Volver a configurar paso a paso", onClick = { app.onboarding.restart() })
        }
    }
}

// --- Voces -------------------------------------------------------------------

@Composable
fun VoicesPage(app: EscribaApp, padding: PaddingValues) {
    Page(padding) {
        Text(
            "Pon nombre a las voces desde «Voces» en el detalle de una sesión. Las huellas se guardan solo en este móvil: nunca van a la bóveda ni al reloj.",
            style = MaterialTheme.typography.bodyLarge, color = Escriba.colors.inkMuted,
        )
        EnrolledVoicesCard(app)
    }
}

// --- Reloj -------------------------------------------------------------------

private val DATE = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", dev.escriba.mobile.ui.today.ES)

@Composable
fun WatchPage(app: EscribaApp, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val pairing by app.pairing.state.collectAsStateWithLifecycle()
    val status by app.inbox.watchStatus.collectAsStateWithLifecycle()
    var nodes by remember { mutableStateOf<List<Peer>?>(null) }
    val c = Escriba.colors

    LaunchedEffect(Unit) {
        while (true) {
            nodes = runCatching { app.link.peers() }.getOrDefault(emptyList())
            delay(10_000)
        }
    }

    Page(padding) {
        EsCard {
            val p = peer
            if (p == null) {
                StatusChip("Sin emparejar", Tone.WAIT)
                Text("Sin emparejamiento el reloj no envía nada.", style = MaterialTheme.typography.bodyLarge, color = c.ink)
            } else {
                val connected = nodes?.firstOrNull { it.id == p.nodeId }
                when {
                    nodes == null -> StatusChip("Comprobando", Tone.BUSY)
                    connected == null -> StatusChip("No conectado", Tone.WAIT)
                    connected.nearby -> StatusChip("Conectado por Bluetooth", Tone.DONE)
                    else -> StatusChip("Solo por la nube: no se usa", Tone.WAIT)
                }
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text("Emparejado desde ${p.pairedAtMs.local().format(DATE)} · clave ${p.keyId}", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            }
            status?.let { st ->
                Text(
                    "Último aviso a las ${st.atMs.local().format(HM)}: " + when (st.phase) {
                        "CAPTURING" -> "grabando"
                        "PAUSED" -> "en pausa"
                        else -> "parado"
                    } + " · ${st.pendingFragments} por enviar (${st.pendingBytes / 1024} KB)" +
                        (st.batteryPct?.let { " · batería ${it.toInt()} %" } ?: "") + if (st.charging) ", cargando" else "",
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
            }
        }

        when (val state = pairing) {
            is PairingState.Code -> EsCard {
                Text("Comprueba que el reloj muestra el mismo código:", style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Text(state.code, style = Escriba.type.metric.copy(fontSize = 40.sp, lineHeight = 44.sp), color = c.ink)
                when {
                    state.localConfirmed && !state.peerConfirmed -> Text("Ahora confirma en el reloj.", color = c.inkMuted)
                    state.peerConfirmed && !state.localConfirmed -> Text("El reloj ya ha confirmado.", color = c.inkMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    if (!state.localConfirmed) Button(onClick = { scope.launch { runCatching { app.pairing.confirm() } } }) { Text("Coincide") }
                    OutlinedButton(onClick = { app.pairing.cancel() }) { Text("No coincide") }
                }
            }
            is PairingState.Waiting -> EsCard {
                Text(state.message, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                OutlinedButton(onClick = { app.pairing.cancel() }) { Text("Cancelar") }
            }
            else -> {
                (state as? PairingState.Failed)?.let { Text(it.reason, color = c.danger, style = MaterialTheme.typography.bodyMedium) }
                (state as? PairingState.Done)?.let { StatusChip("Emparejamiento completado", Tone.DONE) }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    if (peer == null) Button(onClick = { app.pairing.openWindowAsPhone() }) { Text("Emparejar reloj") }
                    else OutlinedButton(onClick = { app.pairing.openWindowAsPhone() }) { Text("Volver a emparejar") }
                    OutlinedButton(onClick = {
                        scope.launch { runCatching { app.link.pingNearby() }.onFailure { AppLog.e("ui", "Fallo al enviar el ping", it) } }
                    }) { Text("Probar conexión") }
                }
            }
        }
    }
}

// --- Batería -----------------------------------------------------------------

@Composable
fun BatteryPage(actions: SystemActions, padding: PaddingValues) {
    var exempt by remember { mutableStateOf(actions.isBatteryExempt()) }
    LaunchedEffect(Unit) {
        while (true) {
            exempt = actions.isBatteryExempt()
            delay(2_000)
        }
    }
    val c = Escriba.colors
    @Composable
    fun Step(title: String, body: String, extra: @Composable () -> Unit = {}) {
        EsCard {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            extra()
        }
    }
    Page(padding) {
        Text(
            "MagicOS cierra las apps en segundo plano para ahorrar batería. Si cierra Escriba, el audio espera en el reloj hasta que vuelvas a abrir la app.",
            style = MaterialTheme.typography.bodyLarge, color = c.inkMuted,
        )
        Step("1. Optimización de batería de Android", if (exempt) "Escriba ya está excluida." else "Escriba está optimizada: el sistema puede cerrarla.") {
            if (exempt) StatusChip("Excluida", Tone.DONE) else Button(onClick = actions.requestBatteryExemption) { Text("Excluir a Escriba") }
        }
        Step(
            "2. Inicio de aplicaciones de MagicOS",
            "Ajustes → Batería → Inicio de aplicaciones → Escriba: desactiva «Gestionar automáticamente» y activa inicio automático, inicio secundario (lo usa el reloj para despertar a Escriba) y ejecución en segundo plano.",
        ) { OutlinedButton(onClick = actions.openAppSettings) { Text("Abrir los ajustes de Escriba") } }
        Step("3. En el reloj", "En la app del reloj, sección «Móvil», pulsa «Sin optimización de batería» y acepta.")
        Step("4. Comprobación", "Cierra Escriba en el móvil (deslízala en recientes) y pulsa «Ping al móvil» en el reloj: debe responder.")
    }
}

// --- Registro ----------------------------------------------------------------

@Composable
fun LogPage(padding: PaddingValues) {
    val log by AppLog.events.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }
    val c = Escriba.colors
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = padding.calculateTopPadding() + Space.s2, bottom = padding.calculateBottomPadding() + Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        item { OutlinedButton(onClick = { AppLog.events.clear() }) { Text("Limpiar") } }
        items(log) { entry ->
            Text(entry.toString(), style = Escriba.type.time.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal), color = c.ink)
        }
    }
}
