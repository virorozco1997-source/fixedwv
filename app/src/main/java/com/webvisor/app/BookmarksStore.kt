package com.webvisor.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un marcador guardado por el usuario en la pantalla de inicio. */
data class Bookmark(val title: String, val url: String)

/**
 * Guarda los marcadores del usuario en SharedPreferences, como un simple
 * array JSON. No hace falta una base de datos para esta cantidad de datos
 * (una lista corta de sitios de confianza), así que se evita esa
 * complejidad a propósito.
 */
object BookmarksStore {

    private const val PREFS_NAME = "web_visor_bookmarks"
    private const val KEY_BOOKMARKS = "bookmarks_json"

    fun getAll(context: Context): List<Bookmark> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()

        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                Bookmark(obj.getString("title"), obj.getString("url"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, title: String, url: String) {
        val current = getAll(context).toMutableList()
        current.add(Bookmark(title, url))
        saveAll(context, current)
    }

    fun remove(context: Context, bookmark: Bookmark) {
        val current = getAll(context).toMutableList()
        current.remove(bookmark)
        saveAll(context, current)
    }

    private fun saveAll(context: Context, bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            val obj = JSONObject()
            obj.put("title", bookmark.title)
            obj.put("url", bookmark.url)
            array.put(obj)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOOKMARKS, array.toString())
            .apply()
    }
}
