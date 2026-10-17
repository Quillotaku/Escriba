package dev.escriba.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.AppScaffold
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.ui.EscribaWearTheme
import dev.escriba.wear.ui.ProfilesScreen

/**
 * Lista completa de perfiles («Más…» en el Tile). Elegir uno empieza a grabar con él o, si ya
 * se está grabando, cambia de perfil (cierra la sesión y abre otra).
 */
class ProfilePickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EscribaWearApp
        setContent {
            EscribaWearTheme {
                AppScaffold {
                    ProfilesScreen(app) { id, current, _ ->
                        if (!current) {
                            runCatching { RecorderService.send(this@ProfilePickerActivity, RecorderService.switchIntent(this@ProfilePickerActivity, id, "lista")) }
                                .onFailure { AppLog.e("ui", "No se pudo iniciar la grabación", it) }
                        }
                        finish()
                    }
                }
            }
        }
    }
}
