package dev.escriba.mobile

import android.app.Application
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.datalayer.PairingCoordinator
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.link.Inbox
import dev.escriba.shared.Protocol
import java.io.File

class EscribaApp : Application() {

    lateinit var link: DataLayerLink
        private set

    lateinit var pairing: PairingCoordinator
        private set

    lateinit var db: EscribaDb
        private set

    lateinit var inbox: Inbox
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        link = DataLayerLink(this, origin = "mobile", peerCapability = Protocol.CAPABILITY_WATCH)
        pairing = PairingCoordinator(link, isWatch = false, deviceName = android.os.Build.MODEL)
        db = EscribaDb.open(this)
        inbox = Inbox(File(filesDir, "inbox"), link, db)
    }
}
