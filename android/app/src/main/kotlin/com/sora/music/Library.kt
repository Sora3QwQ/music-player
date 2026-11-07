package com.sora.music

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

internal object Library {
    data class Song(val uri: Uri, val title: String) {
        val id: String = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    private val audioExtension = Regex(".*\\.(mp3|flac|m4a|aac|wav|ogg|opus)$")

    fun scan(context: Context, tree: Uri): List<Song> {
        val songs = mutableListOf<Song>()
        val visited = mutableSetOf<String>()
        val folders = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val columns = arrayOf("document_id", "_display_name", "mime_type")
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            if (!visited.add(folder)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
            val cursor = checkNotNull(context.contentResolver.query(children, columns, null, null, null)) {
                "无法读取曲库"
            }
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1)
                    val mime = it.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        folders.addLast(id)
                    } else if (name != null && (mime?.startsWith("audio/") == true ||
                            audioExtension.matches(name.lowercase(Locale.ROOT)))) {
                        songs.add(Song(DocumentsContract.buildDocumentUriUsingTree(tree, id), name))
                        check(songs.size <= 10000) { "曲库最多支持 10000 首" }
                    }
                }
            }
        }
        return songs.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun catalog(songs: List<Song>): JSONObject {
        val tracks = JSONArray()
        for (song in songs) tracks.put(JSONObject().put("id", song.id).put("title", song.title))
        return JSONObject().put("type", "catalog").put("tracks", tracks)
    }
}
