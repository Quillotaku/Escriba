package dev.escriba.wear

import android.app.Application
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.shared.Protocol

class EscribaWearApp : Application() {

    lateinit var link: DataLayerLink
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        link = DataLayerLink(this, origin = "wear", peerCapability = Protocol.CAPABILITY_PHONE)
    }
}
