package dev.escriba.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.recorder.BatteryLog
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecorderSettings
import dev.escriba.wear.transfer.UploadWorker
import dev.escriba.wear.ui.EscribaWearTheme
import dev.escriba.wear.ui.WatchActions
import dev.escriba.wear.ui.WatchList
import dev.escriba.wear.ui.WearApp

/** La app del reloj: grabar, elegir perfil, el móvil, ajustes y diagnóstico. */
class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        AppLog.i("ui", "Permisos: " + result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        micBlocked.value = micPermanentlyDenied()
    }

    /** Sin micrófono no hay Escriba: si se denegó para siempre, la única salida son los Ajustes. */
    private val micBlocked = mutableStateOf(false)

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun micPermanentlyDenied() = !micGranted() && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

    private fun openAppSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
    }

    override fun onResume() {
        super.onResume()
        // Puede volver de Ajustes con el permiso concedido.
        if (micGranted()) micBlocked.value = false
    }

    override fun onStart() {
        super.onStart()
        (application as EscribaWearApp).link.startForegroundListening()
    }

    override fun onStop() {
        (application as EscribaWearApp).link.stopForegroundListening()
        super.onStop()
    }

    @SuppressLint("BatteryLife") // app personal instalada por ADB: necesita sobrevivir en segundo plano
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            AppLog.i("ui", "Escriba ya está fuera de la optimización de batería")
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { AppLog.e("ui", "El reloj no permite abrir ese ajuste", it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())

        val app = application as EscribaWearApp
        val settings = RecorderSettings(this)
        val actions = WatchActions(
            toggleRecording = { RecorderService.send(this, RecorderService.intent(this, RecorderService.ACTION_TOGGLE, "app")) },
            switchProfile = { id ->
                runCatching { RecorderService.send(this, RecorderService.switchIntent(this, id, "app")) }
                    .onFailure { AppLog.e("ui", "No se pudo cambiar de perfil", it) }
            },
            sendNow = { UploadWorker.kick(this, manual = true) },
            requestBatteryExemption = ::requestBatteryExemption,
            batteryNow = { BatteryLog.read(this)?.level },
        )
        setContent {
            EscribaWearTheme {
                // El modo ambiente lo gestiona Compose: la pantalla principal se simplifica sola.
                CompositionLocalProvider(LocalAmbientModeManager provides rememberAmbientModeManager()) {
                    if (micBlocked.value) MicPermissionScreen(onOpenSettings = ::openAppSettings)
                    else WearApp(app, settings, actions)
                }
            }
        }
    }
}

@Composable
private fun MicPermissionScreen(onOpenSettings: () -> Unit) {
    AppScaffold {
        WatchList { spec ->
            item {
                ListHeader(Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text("Sin micrófono") }
            }
            item {
                Text(
                    "Escriba necesita el micrófono para grabar y se denegó el permiso. Actívalo en Ajustes → Permisos.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                )
            }
            item {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text("Abrir ajustes")
                }
            }
        }
    }
}
