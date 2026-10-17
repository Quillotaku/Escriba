package dev.escriba.mobile

import android.app.Application
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.datalayer.PairingCoordinator
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.link.Inbox
import dev.escriba.mobile.transcription.Benchmark
import dev.escriba.mobile.transcription.ModelStore
import dev.escriba.mobile.transcription.ModelSlot
import dev.escriba.mobile.llm.Summarizer
import dev.escriba.mobile.ui.onboarding.Onboarding
import dev.escriba.mobile.ui.theme.Appearance
import dev.escriba.mobile.voices.Diarizer
import dev.escriba.mobile.voices.VoiceStore
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

    lateinit var summarizer: Summarizer
        private set

    lateinit var voices: VoiceStore
        private set

    lateinit var appearance: Appearance
        private set

    lateinit var onboarding: Onboarding
        private set

    /** For loose app tasks that don't depend on any screen. */
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
        val slot = ModelSlot()
        transcriber = Transcriber(models, slot)
        summarizer = Summarizer(models, slot)
        voices = VoiceStore(db)
        appearance = Appearance(this)
        onboarding = Onboarding(this)
        processor = SessionProcessor(db, vault, profiles, transcriber, summarizer, Diarizer(models, slot), voices, notes)
        benchmark = Benchmark(this, scope, db, transcriber, summarizer, profiles, processor)
        inbox = Inbox(File(filesDir, "inbox"), link, db) { sessionId, speechMs -> ProcessWorker.enqueue(this, sessionId, speechMs = speechMs) }
        RetentionWorker.schedule(this)

        // When the app opens: profiles up to date (and sent to the watch, if they changed) and sessions left unprocessed.
        scope.launch {
            if (!vault.isConfigured) return@launch
            runCatching { profiles.reload(); profiles.syncToWatch() }
            db.sessions().unprocessed().filter { it.isComplete }.forEach { ProcessWorker.enqueue(this@EscribaApp, it.id, speechMs = it.speechMs) }
        }
    }
}
