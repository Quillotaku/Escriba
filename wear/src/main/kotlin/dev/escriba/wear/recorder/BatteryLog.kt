package dev.escriba.wear.recorder

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

/**
 * Battery log to measure the real cost of recording.
 *
 * It's written to `files/battery.csv`:
 * `epochMs,level%,charging,tempC,state,speechPct`. To copy it to the PC (debug builds):
 * `adb exec-out run-as dev.escriba cat files/battery.csv > battery.csv`.
 */
object BatteryLog {

    data class Reading(val level: Float, val charging: Boolean, val tempC: Float)

    fun read(context: Context): Reading? {
        val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        return Reading(level * 100f / scale, plugged, temp)
    }

    fun append(context: Context, state: String, speechPct: Int) {
        val r = read(context) ?: return
        val file = File(context.filesDir, "battery.csv")
        if (!file.exists()) file.writeText("epochMs,level,charging,tempC,state,speechPct\n")
        file.appendText("${System.currentTimeMillis()},${r.level},${r.charging},${r.tempC},$state,$speechPct\n")
    }
}
