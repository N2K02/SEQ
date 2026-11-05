package com.n2k02.seq.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import java.util.Locale

/** Persisted user-selected files only, with SAF read grants. No media scan or polling. */
data class LocalTrack(val uri: Uri, val title: String) {
  val mediaId: String get() = uri.toString()
}
class LocalMusicLibrary(private val context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("seq_music",Context.MODE_PRIVATE)
  fun items(): List<LocalTrack> = runCatching {
    val entries=JSONArray(prefs.getString("files","[]") ?: "[]")
    (0 until entries.length()).map { i ->
      val item=entries.getJSONObject(i)
      LocalTrack(Uri.parse(item.getString("uri")),item.getString("title"))
    }
  }.getOrDefault(emptyList())
  private fun title(uri:Uri):String {
    val display=runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
        if(c.moveToFirst()) c.getString(0) else null
      }
    }.getOrNull()
    return (display?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "Music").take(110)
  }
  fun add(uris:List<Uri>):List<LocalTrack> {
    val all=items().associateBy { it.mediaId }.toMutableMap()
    for(uri in uris.take(100)) {
      require(uri.scheme=="content") { "Only SAF content URIs are accepted" }
      all[uri.toString()]=LocalTrack(uri,title(uri))
    }
    require(all.size<=500) { "Music library limit is 500 tracks" }
    save(all.values.toList())
    return all.values.toList()
  }
  fun remove(uri:Uri):List<LocalTrack> {
    val all=items().filterNot { it.uri==uri }
    save(all)
    return all
  }
  private fun save(tracks:List<LocalTrack>) {
    val arr=JSONArray()
    tracks.forEach { arr.put(org.json.JSONObject().put("uri",it.uri.toString()).put("title",it.title)) }
    prefs.edit().putString("files",arr.toString()).apply()
  }
  companion object {
    fun supported(filename:String):Boolean {
      val ext=filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
      return ext in setOf("flac","wav","wave","mp3","m4a","aac","ogg","opus")
    }
  }
}
