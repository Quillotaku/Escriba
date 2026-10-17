package dev.escriba.mobile.transcription

import kotlinx.coroutines.sync.Mutex

/**
 * Hueco único para modelos grandes. Whisper (≈ 1 GB) y el LLM (2,5–5 GB) se turnan: quien quiera
 * cargar uno espera a que el otro se libere. Con 12 GB de RAM compartidos con el sistema y el
 * resto de apps, tener los dos a la vez es buscar que Android cierre Escriba.
 */
class ModelSlot {
    val mutex = Mutex()
}
