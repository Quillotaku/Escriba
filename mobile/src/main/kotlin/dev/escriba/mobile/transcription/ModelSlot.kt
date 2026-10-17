package dev.escriba.mobile.transcription

import kotlinx.coroutines.sync.Mutex

/**
 * A single slot for large models. Whisper (≈ 1 GB) and the LLM (2.5–5 GB) take turns: whoever
 * wants to load one waits for the other to be released. With 12 GB of RAM shared with the system
 * and the other apps, having both at once is asking Android to kill Escriba.
 */
class ModelSlot {
    val mutex = Mutex()
}
