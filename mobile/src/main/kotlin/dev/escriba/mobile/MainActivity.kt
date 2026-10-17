package dev.escriba.mobile

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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.ui.nav.AppRoot
import dev.escriba.mobile.ui.onboarding.OnboardingScreen
import dev.escriba.mobile.ui.settings.SystemActions
import dev.escriba.mobile.ui.theme.EscribaTheme
import dev.escriba.mobile.ui.theme.resolve

/** The phone app: Today, sessions, dashboard and settings. */
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
        val app = application as EscribaApp
        // The processing notification needs permission; the first time, the tour asks for it.
        if (app.onboarding.done.value && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val actions = SystemActions(
            isBatteryExempt = { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
            requestBatteryExemption = ::requestBatteryExemption,
            openAppSettings = ::openAppSettings,
        )
        setContent {
            val dark by app.appearance.dark.collectAsStateWithLifecycle()
            val tourDone by app.onboarding.done.collectAsStateWithLifecycle()
            EscribaTheme(dark = app.appearance.resolve(dark)) {
                if (tourDone) AppRoot(app, actions) else OnboardingScreen(app, actions)
            }
        }
    }

    @SuppressLint("BatteryLife") // sideloaded personal app: it needs to receive audio in the background
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { AppLog.e("ui", "Couldn't open the battery setting", it) }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
