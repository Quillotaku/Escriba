package dev.escriba.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.Peer
import kotlinx.coroutines.launch

/** Fase 1: pantalla de diagnóstico del enlace con el reloj. */
class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        (application as EscribaApp).link.startForegroundListening()
    }

    override fun onStop() {
        (application as EscribaApp).link.stopForegroundListening()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val link = (application as EscribaApp).link
        setContent {
            EscribaTheme {
                LinkScreen(
                    loadPeers = { link.peers() },
                    ping = { link.pingNearby() },
                )
            }
        }
    }
}

@Composable
private fun EscribaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@Composable
private fun LinkScreen(loadPeers: suspend () -> List<Peer>, ping: suspend () -> Int) {
    val scope = rememberCoroutineScope()
    val log by AppLog.events.entries.collectAsStateWithLifecycle()
    var peers by remember { mutableStateOf<List<Peer>?>(null) }

    suspend fun refresh() {
        peers = runCatching { loadPeers() }
            .onFailure { AppLog.e("ui", "No se pudieron leer los nodos", it) }
            .getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Escriba · enlace con el reloj", style = MaterialTheme.typography.titleLarge)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val current = peers
                    when {
                        current == null -> Text("Buscando nodos…")
                        current.isEmpty() -> Text("No hay ningún reloj conectado.")
                        else -> current.forEach { peer ->
                            Text("${peer.name} — " +
                                (if (peer.nearby) "conexión directa" else "solo por la nube (no se usa)") +
                                (if (peer.hasEscriba) " · Escriba instalada" else " · sin Escriba"))
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { refresh() } }) { Text("Actualizar") }
                Button(onClick = {
                    scope.launch {
                        runCatching { ping() }
                            .onSuccess { if (it == 0) AppLog.i("ui", "No hay ningún reloj cercano con Escriba") }
                            .onFailure { AppLog.e("ui", "Fallo al enviar el ping", it) }
                    }
                }) { Text("Ping al reloj") }
                OutlinedButton(onClick = { AppLog.events.clear() }) { Text("Limpiar") }
            }

            Text("Registro", style = MaterialTheme.typography.titleMedium)
            val listState = rememberLazyListState()
            LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(log) { entry ->
                    Text(entry.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}
