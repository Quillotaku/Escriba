package dev.escriba.mobile.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Access to the Obsidian vault through the Storage Access Framework: the user picks the folder
 * once and the app can only touch what's inside, without broad storage permission.
 *
 * Paths are relative to the vault and use "/", as in Obsidian (`Daily/2026-10-16.md`).
 */
class VaultAccess(context: Context) {

    data class Entry(val name: String, val isDirectory: Boolean, val lastModified: Long)

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences("vault", Context.MODE_PRIVATE)
    /** Path → document id. Avoids walking the tree on every operation. */
    private val ids = ConcurrentHashMap<String, String>()

    private val _tree = MutableStateFlow(prefs.getString(KEY_TREE, null)?.let(Uri::parse))
    val tree: StateFlow<Uri?> = _tree

    val isConfigured: Boolean get() = _tree.value != null

    fun choose(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        ids.clear()
        _tree.value = uri
    }

    /** Readable name of the chosen folder, e.g. "Documents/Obsidian/Escriba". */
    fun displayPath(): String? = _tree.value?.let { DocumentsContract.getTreeDocumentId(it).substringAfter(':').ifEmpty { "/" } }

    fun exists(path: String): Boolean = find(path) != null

    /** URI of a vault file, e.g. to play its audio. */
    fun uri(path: String): Uri? = runCatching { find(path) }.getOrNull()

    /** The vault's name as Obsidian sees it: the last folder. */
    fun vaultName(): String? = displayPath()?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null }

    fun list(dir: String): List<Entry> {
        val tree = _tree.value ?: return emptyList()
        val parentId = (if (dir.isEmpty()) rootId(tree) else findId(dir)) ?: return emptyList()
        return children(tree, parentId).map { Entry(it.name, it.isDir, it.modified) }
    }

    fun readText(path: String): String? = readBytes(path)?.decodeToString()

    fun readBytes(path: String): ByteArray? = fresh {
        find(path)?.let { uri -> resolver.openInputStream(uri)?.use { it.readBytes() } }
    }

    fun lastModified(path: String): Long? {
        val uri = find(path) ?: return null
        return resolver.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

    /** Creates or overwrites a whole file at once (so Obsidian and sync tools never see half a file). */
    fun writeBytes(path: String, bytes: ByteArray) {
        fresh { writeOnce(path, bytes) }
    }

    /**
     * If someone deleted or moved a file from Obsidian, the stored id is no longer valid: the cache
     * is cleared and it's retried once, walking the tree again.
     */
    private fun <T> fresh(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        ids.clear()
        block()
    }

    private fun writeOnce(path: String, bytes: ByteArray) {
        val tree = requireTree()
        val uri = find(path) ?: run {
            val parent = ensureDir(path.substringBeforeLast('/', ""))
            // octet-stream: so the provider doesn't add or change the name's extension.
            DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), "application/octet-stream", path.substringAfterLast('/'))
                ?.also { ids[path] = DocumentsContract.getDocumentId(it) }
                ?: error("Couldn't create $path")
        }
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Couldn't write $path")
    }

    fun delete(path: String): Boolean {
        val uri = find(path) ?: return false
        ids.remove(path)
        return DocumentsContract.deleteDocument(resolver, uri)
    }

    /** Creates the missing folders and returns the id of the last one. */
    fun ensureDir(dir: String): String {
        val tree = requireTree()
        var currentId = rootId(tree)
        var currentPath = ""
        for (part in dir.split('/').filter { it.isNotEmpty() }) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            val known = ids[currentPath]
            currentId = known ?: children(tree, currentId).firstOrNull { it.name == part && it.isDir }?.id
                ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, currentId), Document.MIME_TYPE_DIR, part)
                    ?.let(DocumentsContract::getDocumentId)
                ?: error("Couldn't create the folder $currentPath")
            ids[currentPath] = currentId
        }
        return currentId
    }

    private fun find(path: String): Uri? {
        val tree = _tree.value ?: return null
        return findId(path)?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
    }

    private fun findId(path: String): String? {
        val tree = _tree.value ?: return null
        ids[path]?.let { return it }
        var currentId = rootId(tree)
        var currentPath = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            currentId = ids[currentPath] ?: children(tree, currentId).firstOrNull { it.name == part }?.id ?: return null
            ids[currentPath] = currentId
        }
        return currentId
    }

    private data class Child(val id: String, val name: String, val isDir: Boolean, val modified: Long)

    private fun children(tree: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(Child(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3)))
            }
        }.orEmpty()
    }

    private fun rootId(tree: Uri) = DocumentsContract.getTreeDocumentId(tree)

    private fun requireTree() = _tree.value ?: error("No vault has been chosen")

    private companion object {
        const val KEY_TREE = "tree"
    }
}
