package com.personal.unifiedrecorder.adapter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.personal.unifiedrecorder.core.port.ByteSink
import com.personal.unifiedrecorder.core.port.DocumentStore
import com.personal.unifiedrecorder.core.port.StoredDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * [DocumentStore] implemented over the Storage Access Framework using [DocumentFile] and
 * `contentResolver.takePersistableUriPermission` (Requirements 6.1, 6.5, 6.8, 6.9, 9.2, 9.3).
 *
 * All reads/writes are confined to the bound Target_Directory tree: every operation resolves
 * documents relative to the persisted tree URI and never touches content outside it (Req 6.8).
 * The persisted tree URI is remembered in SharedPreferences so the exact directory rebinds on
 * relaunch/reinstall (Req 9.3). All I/O runs on [Dispatchers.IO].
 *
 * Device-only / manual verification: SAF persistence, uninstall survival, and reinstall rebind
 * can only be confirmed on a device.
 */
class SafDocumentStore(
    context: Context
) : DocumentStore {

    private val appContext = context.applicationContext
    private val contentResolver get() = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var treeUri: Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    override suspend fun isBound(): Boolean = withContext(Dispatchers.IO) {
        val uri = treeUri ?: return@withContext false
        treeRoot(uri)?.let { it.isDirectory && it.canWrite() } ?: false
    }

    override suspend fun bind(treeUri: String): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext false
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess
        if (!persisted) return@withContext false

        val root = treeRoot(uri)
        if (root == null || !root.isDirectory) return@withContext false

        this@SafDocumentStore.treeUri = uri
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        true
    }

    override suspend fun listRecordings(): List<StoredDocument> = withContext(Dispatchers.IO) {
        val root = boundRoot() ?: return@withContext emptyList()
        root.listFiles()
            .filter { it.isFile }
            .map {
                StoredDocument(
                    name = it.name.orEmpty(),
                    mimeType = it.type ?: "application/octet-stream",
                    sizeBytes = it.length(),
                    lastModifiedMillis = it.lastModified()
                )
            }
    }

    override suspend fun freeBytes(): Long = withContext(Dispatchers.IO) {
        // Best-effort: SAF does not expose free space for arbitrary providers. Use StatFs on the
        // primary external storage as an approximation. Device-only / manual verification.
        runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            stat.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    override suspend fun createDocument(name: String, mime: String): ByteSink = withContext(Dispatchers.IO) {
        val root = boundRoot() ?: throw IllegalStateException("No Target_Directory bound")
        // Replace any pre-existing document with the same name to keep names unique within the tree.
        root.findFile(name)?.takeIf { it.isFile }?.delete()
        val doc = root.createFile(mime, name)
            ?: throw IllegalStateException("Failed to create document '$name' in the bound directory")
        val output = contentResolver.openOutputStream(doc.uri, "w")
            ?: throw IllegalStateException("Failed to open output stream for '$name'")
        SafByteSink(output, doc)
    }

    override suspend fun readText(name: String): String? = withContext(Dispatchers.IO) {
        val root = boundRoot() ?: return@withContext null
        val doc = root.findFile(name)?.takeIf { it.isFile } ?: return@withContext null
        runCatching {
            contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    override suspend fun writeText(name: String, content: String) {
        withContext(Dispatchers.IO) {
            val root = boundRoot() ?: throw IllegalStateException("No Target_Directory bound")
            val doc = root.findFile(name)?.takeIf { it.isFile }
                ?: root.createFile(TEXT_MIME, name)
                ?: throw IllegalStateException("Failed to create document '$name'")
            // "wt" truncates existing content before writing.
            contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw IllegalStateException("Failed to open output stream for '$name'")
        }
    }

    override suspend fun existingNames(): Set<String> = withContext(Dispatchers.IO) {
        val root = boundRoot() ?: return@withContext emptySet()
        root.listFiles().mapNotNull { it.name }.toSet()
    }

    private fun boundRoot(): DocumentFile? = treeUri?.let(::treeRoot)?.takeIf { it.isDirectory }

    private fun treeRoot(uri: Uri): DocumentFile? = DocumentFile.fromTreeUri(appContext, uri)

    /**
     * [ByteSink] that streams bytes into a SAF-backed [OutputStream]. [abort] deletes the
     * partially written document so a failed write leaves no corrupt file behind.
     */
    private class SafByteSink(
        private val output: OutputStream,
        private val document: DocumentFile
    ) : ByteSink {

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            withContext(Dispatchers.IO) { output.write(bytes, offset, length) }
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) {
                runCatching { output.flush() }
                output.close()
            }
        }

        override suspend fun abort() {
            withContext(Dispatchers.IO) {
                runCatching { output.close() }
                runCatching { document.delete() }
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "saf_document_store"
        const val KEY_TREE_URI = "tree_uri"
        const val TEXT_MIME = "application/json"
    }
}
