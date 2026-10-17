package dev.escriba.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.Peer
import dev.escriba.datalayer.PairingState
import dev.escriba.mobile.data.SessionEntity
import dev.escriba.mobile.ui.SessionVoicesDialog
import dev.escriba.mobile.ui.ModelsScreen
import dev.escriba.mobile.ui.VaultScreen
import dev.escriba.mobile.processing.ProcessWorker
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fase 3: sesiones recibidas, enlace con el reloj, guía de batería y registro. */
class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        (application as EscribaApp).link.startForegroundListening()
    }

    override fun onStop() {
        (application as EscribaApp).link.stopForegroundListening()
        super.onStop()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // La notificación del procesado (transcripción) necesita permiso desde Android 13.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as EscribaApp
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var tab by remember { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            listOf("Sesiones", "Bóveda", "Modelos", "Reloj", "Batería", "Registro").forEachIndexed { i, label ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(label) })
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                        when (tab) {
                            0 -> SessionsScreen(app)
                            1 -> VaultScreen(app)
                            2 -> ModelsScreen(app)
                            3 -> LinkScreen(app)
                            4 -> BatteryGuideScreen(
                                isExempt = { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
                                requestExemption = ::requestBatteryExemption,
                                openAppSettings = ::openAppSettings,
                            )
                            else -> LogScreen()
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("BatteryLife") // app personal instalada por ADB: necesita recibir audio en segundo plano
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { AppLog.e("ui", "No se pudo abrir el ajuste de batería", it) }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}

private val DATE = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.forLanguageTag("es-ES"))
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

private fun Long.local() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

private fun minutes(ms: Long): String = when {
    ms < 60_000 -> "${ms / 1000} s"
    else -> "${ms / 60_000} min"
}

@Composable
private fun SessionsScreen(app: EscribaApp) {
    val sessions by app.db.sessions().observeSessions().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<SessionEntity?>(null) }
    var pickProfileFor by remember { mutableStateOf<String?>(null) }
    var voicesFor by remember { mutableStateOf<String?>(null) }
    val diarized by app.db.sessions().observeDiarizations().collectAsStateWithLifecycle(initialValue = emptyList())
    val diarizedIds = diarized.map { it.sessionId }.toSet()
    val allNotes by app.db.sessions().observeNotes().collectAsStateWithLifecycle(initialValue = emptyList())
    val notesBySession = allNotes.groupBy { it.sessionId }
    val profileSet by app.profiles.set.collectAsStateWithLifecycle()

    Text("Sesiones recibidas", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.padding(4.dp))
    if (sessions.isEmpty()) {
        Text("Todavía no ha llegado nada del reloj. Empareja el reloj en la pestaña «Reloj» y graba algo.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sessions, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.startMs.local().format(DATE), fontWeight = FontWeight.SemiBold)
                    val end = s.endMs ?: s.lastSpeechEndMs
                    Text("${s.startMs.local().format(TIME)}–${end.local().format(TIME)} · ${minutes(end - s.startMs)} · voz ${minutes(s.speechMs)}")
                    Text(
                        "${s.receivedFragments}${s.expectedFragments?.let { "/$it" } ?: ""} fragmentos · " +
                            "%.1f MB · ".format(s.bytes / 1_048_576.0) +
                            when {
                                s.isComplete -> "completa"
                                s.endMs != null -> "faltan fragmentos"
                                else -> "en curso"
                            },
                    )
                    Text("Perfil: ${s.processedProfileId ?: s.profileId}", style = MaterialTheme.typography.bodySmall)
                    val notes = notesBySession[s.id].orEmpty()
                    when {
                        s.processError != null -> Text("Error: ${s.processError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        notes.isNotEmpty() -> notes.forEach { Text("📝 ${it.path}", style = MaterialTheme.typography.bodySmall) }
                        s.isComplete -> Text("Pendiente de escribir en la bóveda", style = MaterialTheme.typography.bodySmall)
                    }
                    if (s.audioDeletedAtMs != null) Text("Audio ya borrado del móvil (retención)", style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (s.isComplete) {
                            TextButton(onClick = { ProcessWorker.enqueue(app, s.id, now = true) }) { Text(if (notes.isEmpty()) "Procesar ahora" else "Reprocesar") }
                            Box {
                                TextButton(onClick = { pickProfileFor = s.id }) { Text("Con otro perfil…") }
                                DropdownMenu(expanded = pickProfileFor == s.id, onDismissRequest = { pickProfileFor = null }) {
                                    profileSet?.profiles?.values?.forEach { p ->
                                        DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                            ProcessWorker.enqueue(app, s.id, p.id, now = true)
                                            pickProfileFor = null
                                        })
                                    }
                                }
                            }
                        }
                        if (s.isComplete && (s.audioDeletedAtMs == null || s.id in diarizedIds)) TextButton(onClick = { voicesFor = s.id }) { Text("Voces") }
                        TextButton(onClick = { toDelete = s }) { Text("Borrar") }
                    }
                }
            }
        }
    }

    voicesFor?.let { id -> SessionVoicesDialog(app, id) { voicesFor = null } }

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Borrar la sesión?") },
            text = { Text("Se borrará su audio del móvil. Las notas ya escritas en la bóveda no se tocan. No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.inbox.delete(s.id) }
                    toDelete = null
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LinkScreen(app: EscribaApp) {
    val scope = rememberCoroutineScope()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val pairing by app.pairing.state.collectAsStateWithLifecycle()
    val status by app.inbox.watchStatus.collectAsStateWithLifecycle()
    var nodes by remember { mutableStateOf<List<Peer>?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            nodes = runCatching { app.link.peers() }.getOrDefault(emptyList())
            delay(10_000)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reloj", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val p = peer
                if (p == null) {
                    Text("Sin emparejar", fontWeight = FontWeight.SemiBold)
                    Text("Sin emparejamiento el reloj no envía nada.")
                } else {
                    Text("Emparejado con ${p.name}", fontWeight = FontWeight.SemiBold)
                    Text("Desde ${p.pairedAtMs.local().format(DATE)} · clave ${p.keyId}", style = MaterialTheme.typography.bodySmall)
                    val connected = nodes?.firstOrNull { it.id == p.nodeId }
                    Text(
                        when {
                            nodes == null -> "Comprobando conexión…"
                            connected == null -> "No conectado"
                            connected.nearby -> "Conectado por Bluetooth"
                            else -> "Solo accesible por la nube: no se usa"
                        },
                    )
                }
                status?.let { st ->
                    Text(
                        "Último estado ${st.atMs.local().format(TIME)}: ${phaseLabel(st.phase)} · " +
                            "${st.pendingFragments} pendientes (${st.pendingBytes / 1024} KB) · " +
                            "batería ${st.batteryPct?.toInt() ?: "?"} %" + if (st.charging) " (cargando)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        when (val state = pairing) {
            is PairingState.Code -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Comprueba que el reloj muestra el mismo código:")
                    Text(state.code, fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    when {
                        state.localConfirmed && !state.peerConfirmed -> Text("Ahora confirma en el reloj.")
                        state.peerConfirmed && !state.localConfirmed -> Text("El reloj ya ha confirmado.")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.localConfirmed) {
                            Button(onClick = { scope.launch { runCatching { app.pairing.confirm() } } }) { Text("Coincide") }
                        }
                        OutlinedButton(onClick = { app.pairing.cancel() }) { Text("No coincide") }
                    }
                }
            }

            is PairingState.Waiting -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.message)
                    OutlinedButton(onClick = { app.pairing.cancel() }) { Text("Cancelar") }
                }
            }

            else -> {
                (state as? PairingState.Failed)?.let { Text(it.reason, color = MaterialTheme.colorScheme.error) }
                (state as? PairingState.Done)?.let { Text("Emparejamiento completado ✓") }
                Button(onClick = { app.pairing.openWindowAsPhone() }) {
                    Text(if (peer == null) "Emparejar reloj" else "Volver a emparejar")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { app.link.pingNearby() }.onFailure { AppLog.e("ui", "Fallo al enviar el ping", it) }
                }
            }) { Text("Ping al reloj") }
        }
    }
}

private fun phaseLabel(phase: String) = when (phase) {
    "CAPTURING" -> "grabando"
    "PAUSED" -> "en pausa"
    else -> "parado"
}

@Composable
private fun BatteryGuideScreen(isExempt: () -> Boolean, requestExemption: () -> Unit, openAppSettings: () -> Unit) {
    var exempt by remember { mutableStateOf(isExempt()) }
    LaunchedEffect(Unit) {
        while (true) {
            exempt = isExempt()
            delay(2_000)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Que el sistema no cierre Escriba", style = MaterialTheme.typography.titleLarge)
        Text(
            "MagicOS cierra las apps en segundo plano para ahorrar batería. Si cierra Escriba, el audio se queda " +
                "esperando en el reloj hasta que vuelvas a abrir la app del móvil. En la fase 1 se comprobó que sin " +
                "estos ajustes el móvil no recibe nada.",
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Optimización de batería de Android", fontWeight = FontWeight.SemiBold)
                Text(if (exempt) "✓ Escriba ya está excluida." else "Escriba está optimizada: puede cerrarse en segundo plano.")
                if (!exempt) Button(onClick = requestExemption) { Text("Excluir a Escriba") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Inicio de aplicaciones de MagicOS", fontWeight = FontWeight.SemiBold)
                Text(
                    "Ajustes → Batería → Inicio de aplicaciones (o «Gestión de inicio de apps») → Escriba → desactiva " +
                        "«Gestionar automáticamente» y activa las tres opciones:\n" +
                        "• Inicio automático\n• Inicio secundario (lo usa el reloj para despertar a Escriba)\n• Ejecutar en segundo plano",
                )
                OutlinedButton(onClick = openAppSettings) { Text("Abrir los ajustes de Escriba") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. En el reloj (HyperOS)", fontWeight = FontWeight.SemiBold)
                Text(
                    "En la app del reloj, sección «Móvil», pulsa «Sin optimización de batería» y acepta. Si el reloj " +
                        "tiene un ajuste de batería por app, déjalo en «Sin restricciones».",
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4. Comprobación", fontWeight = FontWeight.SemiBold)
                Text("Cierra Escriba en el móvil (deslízala en recientes) y pulsa «Ping al móvil» en el reloj: debe responder.")
            }
        }
    }
}

@Composable
private fun LogScreen() {
    val log by AppLog.events.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Registro", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { AppLog.events.clear() }) { Text("Limpiar") }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        items(log) { entry -> Text(entry.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}
