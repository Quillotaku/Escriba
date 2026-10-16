package dev.escriba.mobile.transcription

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/** Archivo de modelo disponible en la carpeta de modelos. */
data class ModelEntry(val uri: String, val name: String, val sizeBytes: Long) {
    val kind: String get() = when {
        name.startsWith("ggml-") && name.endsWith(".bin") -> "whisper"
        name.endsWith(".gguf") -> "llm"
        else -> "otro"
    }
}

/** Archivo de modelo abierto: mientras no se cierre, [fd] es válido para el motor nativo. */
class OpenModel(val fd: Int, private val pfd: ParcelFileDescriptor) : Closeable {
    override fun close() = pfd.close()
}

/**
 * Carpeta de modelos elegida por el usuario (p. ej. Descargas). La app lista lo que hay dentro y
 * abre los modelos directamente desde ahí: no se copian ni se importan uno a uno. Escriba no tiene
 * acceso a Internet, así que los modelos se descargan fuera y se dejan en esa carpeta.
 */
class ModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("modelos", Context.MODE_PRIVATE)

    private val _folder = MutableStateFlow(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse))
    val folder: StateFlow<Uri?> = _folder

    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    val models: StateFlow<List<ModelEntry>> = _models

    init {
        refresh()
    }

    fun chooseFolder(tree: Uri) {
        appContext.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(KEY_FOLDER, tree.toString()).apply()
        _folder.value = tree
        refresh()
    }

    fun folderName(): String? = _folder.value?.let { DocumentsContract.getTreeDocumentId(it).substringAfter(':').ifEmpty { "/" } }

    /** Vuelve a leer la carpeta (p. ej. tras descargar un modelo nuevo). */
    fun refresh(): List<ModelEntry> {
        val tree = _folder.value ?: return emptyList<ModelEntry>().also { _models.value = it }
        val list = runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            appContext.contentResolver.query(
                children,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (c.getString(3) == Document.MIME_TYPE_DIR) continue
                        if (!(name.endsWith(".bin") || name.endsWith(".gguf") || name.endsWith(".onnx"))) continue
                        add(ModelEntry(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(), name, c.getLong(2)))
                    }
                }
            }.orEmpty().sortedBy { it.name }
        }.getOrDefault(emptyList())
        _models.value = list
        return list
    }

    /**
     * El modelo que corresponde al nombre de un perfil (`whisper: large-v3-turbo-q8_0`): el archivo
     * `ggml-large-v3-turbo-q8_0.bin`, o el primero cuyo nombre lo contenga.
     */
    fun find(name: String): ModelEntry? {
        val models = _models.value.ifEmpty { refresh() }
        val wanted = name.lowercase().removePrefix("ggml-").removeSuffix(".bin")
        return models.firstOrNull { it.name.lowercase().removePrefix("ggml-").removeSuffix(".bin") == wanted }
            ?: models.firstOrNull { wanted in it.name.lowercase() }
    }

    fun open(entry: ModelEntry): OpenModel {
        val pfd = appContext.contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r")
            ?: error("No se pudo abrir ${entry.name}")
        // Un proveedor que solo da tuberías no sirve: el motor necesita poder leer el archivo entero.
        runCatching { Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR) }.onFailure {
            pfd.close()
            error("${entry.name} no se puede leer directamente desde esa carpeta")
        }
        return OpenModel(pfd.fd, pfd)
    }

    private companion object {
        const val KEY_FOLDER = "carpeta"
    }
}
