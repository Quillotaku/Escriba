package dev.escriba.mobile.voices

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.transcription.ModelSlot
import dev.escriba.mobile.transcription.ModelStore
import dev.escriba.mobile.transcription.SpeechAudio
import dev.escriba.nativeml.WhisperEngine
import dev.escriba.shared.voices.SpeakerTurn
import dev.escriba.shared.voices.VoiceMath
import kotlinx.coroutines.sync.withLock

/**
 * Who speaks when (sherpa-onnx): pyannote segmentation + 3D-Speaker embeddings + clustering.
 * For each speaker it also returns an average voiceprint, which is what gets compared with the
 * enrolled voices ("Me", "Ana López"…).
 */
class Diarizer(private val models: ModelStore, private val slot: ModelSlot) {

    data class Result(
        val turns: List<SpeakerTurn>,
        /** Average voiceprint of each detected speaker. */
        val embeddings: Map<Int, FloatArray>,
        /** Speech time of each speaker. */
        val speechMs: Map<Int, Long>,
        val elapsedMs: Long,
    )

    suspend fun diarize(audio: SpeechAudio): Result = slot.mutex.withLock {
        val start = System.currentTimeMillis()
        val threads = WhisperEngine.defaultThreads()
        val segmentation = models.localPathOf(SEGMENTATION_MODEL)
        val embedding = models.localPathOf(EMBEDDING_MODEL)

        val diarization = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentation),
                    numThreads = threads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = embedding, numThreads = threads),
                // Unknown number of speakers: cluster by similarity (lower = more speakers).
                clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            ),
        )
        val segments = try {
            diarization.process(audio.pcm)
        } finally {
            diarization.release()
        }

        val turns = segments.map { s ->
            SpeakerTurn(audio.timeline.wallAt((s.start * 1000).toLong()), audio.timeline.wallAt((s.end * 1000).toLong()), s.speaker)
        }
        val speechMs = segments.groupBy { it.speaker }.mapValues { (_, list) -> list.sumOf { ((it.end - it.start) * 1000).toLong() } }

        // Each speaker's voiceprint from up to 30 s of their speech.
        val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = embedding, numThreads = threads))
        val embeddings = HashMap<Int, FloatArray>()
        try {
            for ((speaker, list) in segments.groupBy { it.speaker }) {
                val samples = ArrayList<FloatArray>()
                var total = 0
                for (s in list.sortedByDescending { it.end - it.start }) {
                    val from = (s.start * 16_000).toInt().coerceIn(0, audio.pcm.size)
                    val to = (s.end * 16_000).toInt().coerceIn(from, audio.pcm.size)
                    if (to - from < 16_000 / 2) continue // less than 0.5 s says little about a voice
                    samples += audio.pcm.copyOfRange(from, to)
                    total += to - from
                    if (total >= 16_000 * 30) break
                }
                if (samples.isEmpty()) continue
                val stream = extractor.createStream()
                try {
                    samples.forEach { stream.acceptWaveform(it, 16_000) }
                    stream.inputFinished()
                    if (extractor.isReady(stream)) embeddings[speaker] = extractor.compute(stream)
                } finally {
                    stream.release()
                }
            }
        } finally {
            extractor.release()
        }

        val clean = VoiceMath.merge(turns, embeddings, speechMs)
        val elapsed = System.currentTimeMillis() - start
        AppLog.i(TAG, "Diarization: ${clean.speechMs.size} speakers (${speechMs.size} before merging) in " +
            "${audio.durationMs / 1000} s of speech, ${elapsed / 1000} s of compute")
        Result(clean.turns, clean.embeddings, clean.speechMs, elapsed)
    }

    companion object {
        private const val TAG = "voices"
        const val SEGMENTATION_MODEL = "pyannote-segmentation-3-0.onnx"
        const val EMBEDDING_MODEL = "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"
        private const val CLUSTER_THRESHOLD = 0.8f
    }
}
