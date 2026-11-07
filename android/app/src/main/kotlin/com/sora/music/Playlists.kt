package com.sora.music

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class Playlist(val id: String, val name: String, val tracks: MutableList<String>)

internal enum class RepeatMode(val label: String) {
    OFF("顺序播放"), ALL("列表循环"), ONE("单曲循环");

    fun next() = entries[(ordinal + 1) % entries.size]
}

internal fun nextTrack(tracks: List<String>, current: String?, direction: Int, repeat: RepeatMode, automatic: Boolean): String? {
    if (tracks.isEmpty()) return null
    val index = tracks.indexOf(current)
    if (index < 0) return tracks.first()
    if (automatic && repeat == RepeatMode.ONE) return tracks[index]
    val next = index + direction
    return if (repeat == RepeatMode.ALL) tracks[Math.floorMod(next, tracks.size)] else tracks.getOrNull(next)
}

internal class Playlists(private val prefs: SharedPreferences) {
    val lists = mutableListOf<Playlist>()
    private var selected = ""
    var repeat = RepeatMode.ALL
        private set
    val active: Playlist get() = lists.firstOrNull { it.id == selected } ?: lists.first()

    init {
        try {
            val saved = JSONObject(prefs.getString("playlists", "{}").orEmpty())
            val items = saved.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val tracks = item.getJSONArray("tracks")
                lists.add(Playlist(item.getString("id"), item.getString("name"),
                    MutableList(tracks.length()) { tracks.getString(it) }))
            }
            selected = saved.optString("selected")
            repeat = RepeatMode.entries.firstOrNull { it.name == saved.optString("repeat") } ?: RepeatMode.ALL
        } catch (_: org.json.JSONException) {
            lists.clear()
        }
        if (lists.isEmpty()) lists.add(Playlist("default", "我的歌单", mutableListOf()))
    }

    fun create(name: String): Boolean {
        val title = name.trim()
        if (title.isEmpty() || title.length > 40 || lists.size >= 20) return false
        val list = Playlist(UUID.randomUUID().toString(), title, mutableListOf())
        lists.add(list)
        return select(list.id)
    }

    fun select(id: String): Boolean {
        if (lists.none { it.id == id }) return false
        selected = id
        save()
        return true
    }

    fun cycleRepeat() {
        repeat = repeat.next()
        save()
    }

    fun save() {
        val items = JSONArray()
        for (list in lists) items.put(JSONObject().put("id", list.id).put("name", list.name)
            .put("tracks", JSONArray(list.tracks)))
        prefs.edit().putString("playlists", JSONObject().put("selected", active.id)
            .put("repeat", repeat.name).put("items", items).toString()).apply()
    }
}
