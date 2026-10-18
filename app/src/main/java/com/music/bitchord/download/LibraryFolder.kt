package com.music.bitchord.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.music.bitchord.data.DebugLog as Log

/**
 * The `Auden` folder in Music, opened for writing through the system's folder
 * picker.
 *
 * Android lets an app add audio to Music through the media store but nothing
 * else: a `.lrc` or a `cover.jpg` beside a song is refused there. A folder the
 * listener has chosen and granted is the one way to put them where other players
 * look. It is optional — without it lyrics stay in the app's own storage and
 * inside the audio file's tags, as before.
 *
 * The grant covers the folder and everything under it, which is why the picker
 * is asked for `Music/Auden` and nothing wider.
 */
object LibraryFolder {
    private const val TAG = "LibraryFolder"
    private const val PREFS = "auden_library_folder"
    private const val KEY_TREE = "tree_uri"

    fun treeUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)

    /** Whether there is a folder to write into and the grant on it still stands. */
    fun hasAccess(context: Context): Boolean {
        val tree = treeUri(context) ?: return false
        return context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }

    /** Keeps the grant across restarts and remembers the folder; call with the picker's result. */
    fun grant(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply()
    }

    fun revoke(context: Context) {
        treeUri(context)?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE).apply()
    }

    /** A short name for the chosen folder, for Settings. */
    fun label(context: Context): String? = treeUri(context)?.let { tree ->
        runCatching { name(context, tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrNull()
    }

    /**
     * Writes [bytes] as [relativePath] (`Artist/Album/Song.lrc`) under the folder, replacing what is
     * there. Returns false, quietly, when there is no folder or the write fails: a sidecar missing is
     * never worth failing a download for.
     */
    fun write(context: Context, relativePath: String, mime: String, bytes: ByteArray, replace: Boolean = true): Boolean {
        if (!hasAccess(context)) return false
        return runCatching {
            val tree = treeUri(context)!!
            var dir = rootDocument(context, tree)
            val parts = relativePath.split('/').filter { it.isNotEmpty() }
            parts.dropLast(1).forEach { dir = childDirectory(context, tree, dir, it) }
            val fileName = parts.last()
            val existing = find(context, tree, dir, fileName)
            if (existing != null) {
                if (!replace) return@runCatching true
                DocumentsContract.deleteDocument(context.contentResolver, documentUri(tree, existing))
            }
            val created = DocumentsContract.createDocument(context.contentResolver, documentUri(tree, dir), mime, fileName)
                ?: error("could not create $fileName")
            context.contentResolver.openOutputStream(created, "wt")!!.use { it.write(bytes) }
            true
        }.onFailure { Log.w(TAG, "could not write $relativePath: ${it.message}") }.getOrDefault(false)
    }

    fun exists(context: Context, relativePath: String): Boolean {
        if (!hasAccess(context)) return false
        return runCatching {
            val tree = treeUri(context)!!
            var dir = rootDocument(context, tree)
            val parts = relativePath.split('/').filter { it.isNotEmpty() }
            parts.dropLast(1).forEach { dir = find(context, tree, dir, it) ?: return@runCatching false }
            find(context, tree, dir, parts.last()) != null
        }.getOrDefault(false)
    }

    /** The `Auden` folder: the one picked, or the `Auden` inside it when `Music` itself was picked. */
    private fun rootDocument(context: Context, tree: Uri): String {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        if (name(context, tree, treeId) == DownloadStore.FOLDER) return treeId
        return childDirectory(context, tree, treeId, DownloadStore.FOLDER)
    }

    private fun documentUri(tree: Uri, documentId: String) = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    private fun name(context: Context, tree: Uri, documentId: String): String? =
        context.contentResolver.query(
            documentUri(tree, documentId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun find(context: Context, tree: Uri, parentId: String, childName: String): String? =
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == childName) return@use cursor.getString(0)
            null
        }

    private fun childDirectory(context: Context, tree: Uri, parentId: String, childName: String): String {
        find(context, tree, parentId, childName)?.let { return it }
        val created = DocumentsContract.createDocument(
            context.contentResolver, documentUri(tree, parentId), DocumentsContract.Document.MIME_TYPE_DIR, childName,
        ) ?: error("could not create folder $childName")
        return DocumentsContract.getDocumentId(created)
    }
}
