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

/** A model file available in the models folder. */
data class ModelEntry(val uri: String, val name: String, val sizeBytes: Long) {
    val kind: String get() = when {
        name.startsWith("ggml-") && name.endsWith(".bin") -> "whisper"
        name.endsWith(".gguf") -> "llm"
        name.startsWith("parakeet") -> "parakeet"
        name.endsWith(".onnx") -> "voice"
        else -> "other"
    }
}

/** An open model file: until it's closed, [fd] is valid for the native engine. */
class OpenModel(val fd: Int, private val pfd: ParcelFileDescriptor) : Closeable {
    override fun close() = pfd.close()
}

/**
 * Models folder chosen by the user (e.g. Downloads). The app lists what's inside and opens the
 * models straight from there: they aren't copied or imported one by one. Escriba has no Internet
 * access, so the models are downloaded elsewhere and left in that folder.
 */
class ModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("models", Context.MODE_PRIVATE)

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

    /** Reads the folder again (e.g. after downloading a new model). */
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
                        if (!(name.endsWith(".bin") || name.endsWith(".gguf") || name.endsWith(".onnx") || (name.endsWith("tokens.txt")))) continue
                        add(ModelEntry(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(), name, c.getLong(2)))
                    }
                }
            }.orEmpty().sortedBy { it.name }
        }.getOrDefault(emptyList())
        _models.value = list
        return list
    }

    /**
     * The model matching a profile's name (`whisper: large-v3-turbo-q8_0`): the file
     * `ggml-large-v3-turbo-q8_0.bin`, or the first one whose name contains it.
     */
    fun find(name: String): ModelEntry? {
        val models = _models.value.ifEmpty { refresh() }
        val wanted = name.lowercase().removePrefix("ggml-").removeSuffix(".bin")
        return models.firstOrNull { it.name.lowercase().removePrefix("ggml-").removeSuffix(".bin") == wanted }
            ?: models.firstOrNull { wanted in it.name.lowercase() }
    }

    fun open(entry: ModelEntry): OpenModel {
        val pfd = appContext.contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r")
            ?: error("Couldn't open ${entry.name}")
        // A provider that only gives pipes won't do: the engine needs to read the whole file.
        runCatching { Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR) }.onFailure {
            pfd.close()
            error("${entry.name} can't be read directly from that folder")
        }
        return OpenModel(pfd.fd, pfd)
    }

    /**
     * Local path of a model, copying it into the app the first time. sherpa-onnx only opens models
     * by path, and Downloads paths require broad storage permission (which Escriba doesn't ask
     * for); its private storage can be read by path. The copy is made once and only redone if the
     * original changes size.
     */
    fun localPath(entry: ModelEntry, onProgress: (copiedMb: Long, totalMb: Long) -> Unit = { _, _ -> }): String {
        val dir = java.io.File(appContext.filesDir, "models").apply { mkdirs() }
        val target = java.io.File(dir, entry.name)
        if (target.exists() && target.length() == entry.sizeBytes) return target.absolutePath
        val free = dir.usableSpace
        check(free > entry.sizeBytes + 200L * 1024 * 1024) { "There's no room to copy ${entry.name} (${entry.sizeBytes / 1_048_576} MB)" }
        val tmp = java.io.File(dir, entry.name + ".part")
        appContext.contentResolver.openInputStream(Uri.parse(entry.uri))?.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (copied - lastReport > 64L * 1024 * 1024) {
                        onProgress(copied / 1_048_576, entry.sizeBytes / 1_048_576)
                        lastReport = copied
                    }
                }
                output.fd.sync()
            }
        } ?: error("Couldn't read ${entry.name}")
        check(tmp.renameTo(target)) { "Couldn't save ${entry.name}" }
        return target.absolutePath
    }

    /** Local path of the model named exactly [name] (or a clear error if it's missing). */
    fun localPathOf(name: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val entry = (_models.value.ifEmpty { refresh() }).firstOrNull { it.name == name }
            ?: error("$name is missing from the models folder")
        return localPath(entry, onProgress)
    }

    private companion object {
        const val KEY_FOLDER = "folder"
    }
}
