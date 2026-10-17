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
 * Full list of profiles ("More" on the Tile). Picking one starts recording with it or, if already
 * recording, switches profile (closes the session and opens another).
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
                            runCatching { RecorderService.send(this@ProfilePickerActivity, RecorderService.switchIntent(this@ProfilePickerActivity, id, "list")) }
                                .onFailure { AppLog.e("ui", "Couldn't start the recording", it) }
                        }
                        finish()
                    }
                }
            }
        }
    }
}
