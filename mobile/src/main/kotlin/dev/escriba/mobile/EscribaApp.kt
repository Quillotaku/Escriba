package dev.escriba.mobile

import android.app.Application
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.datalayer.PairingCoordinator
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.link.Inbox
import dev.escriba.mobile.transcription.Benchmark
import dev.escriba.mobile.transcription.ModelStore
import dev.escriba.mobile.transcription.Transcriber
import dev.escriba.mobile.vault.NoteWriter
import dev.escriba.mobile.processing.ProcessWorker
import dev.escriba.mobile.processing.SessionProcessor
import dev.escriba.mobile.vault.ProfileRepository
import dev.escriba.mobile.vault.RetentionWorker
import dev.escriba.mobile.vault.VaultAccess
import dev.escriba.mobile.vault.VaultBootstrap
import dev.escriba.shared.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    lateinit var vault: VaultAccess
        private set

    lateinit var profiles: ProfileRepository
        private set

    lateinit var notes: NoteWriter
        private set

    lateinit var models: ModelStore
        private set

    lateinit var transcriber: Transcriber
        private set

    lateinit var benchmark: Benchmark
        private set

    lateinit var processor: SessionProcessor
        private set

    /** Para tareas sueltas de la app que no dependen de ninguna pantalla. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        link = DataLayerLink(this, origin = "mobile", peerCapability = Protocol.CAPABILITY_WATCH)
        pairing = PairingCoordinator(link, isWatch = false, deviceName = android.os.Build.MODEL)
        db = EscribaDb.open(this)
        vault = VaultAccess(this)
        profiles = ProfileRepository(this, vault, link)
        notes = NoteWriter(vault, profiles, db)
        models = ModelStore(this)
        transcriber = Transcriber(models)
        benchmark = Benchmark(scope, db, transcriber)
        processor = SessionProcessor(db, profiles, transcriber, notes)
        inbox = Inbox(File(filesDir, "inbox"), link, db) { sessionId, speechMs -> ProcessWorker.enqueue(this, sessionId, speechMs = speechMs) }
        RetentionWorker.schedule(this)

        // Al abrir la app: perfiles al día (y al reloj, si han cambiado) y sesiones que se quedaron sin procesar.
        scope.launch {
            if (!vault.isConfigured) return@launch
            runCatching { VaultBootstrap.repair(vault) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { AppLog.i("boveda", "Reparado: ${it.joinToString()}") }
            runCatching { profiles.reload(); profiles.syncToWatch() }
            db.sessions().unprocessed().filter { it.isComplete }.forEach { ProcessWorker.enqueue(this@EscribaApp, it.id, speechMs = it.speechMs) }
        }
    }
}
