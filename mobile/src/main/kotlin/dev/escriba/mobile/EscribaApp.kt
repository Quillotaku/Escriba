package dev.escriba.mobile

import android.app.Application
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.shared.Protocol

class EscribaApp : Application() {

    lateinit var link: DataLayerLink
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        link = DataLayerLink(this, origin = "mobile", peerCapability = Protocol.CAPABILITY_WATCH)
    }
}
