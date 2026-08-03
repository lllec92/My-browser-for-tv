package com.tvbrowser.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple persisted bookmarks store, keyed by URL (adding the same URL twice just
 * updates its title/order instead of duplicating it).
 */
class BookmarksManager(context: Context) {

    data class Bookmark(val title: String, val url: String)

    companion object {
        private const val PREFS_NAME = "tv_browser_bookmarks"
        private const val KEY_BOOKMARKS = "bookmarks"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Bookmark(title = obj.optString("title", url), url = url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isBookmarked(url: String): Boolean = getAll().any { it.url == url }

    fun add(title: String, url: String) {
        val current = getAll().filterNot { it.url == url }.toMutableList()
        current.add(0, Bookmark(title = title.ifBlank { url }, url = url))
        persist(current)
    }

    fun remove(url: String) {
        persist(getAll().filterNot { it.url == url })
    }

    fun toggle(title: String, url: String): Boolean {
        return if (isBookmarked(url)) {
            remove(url)
            false
        } else {
            add(title, url)
            true
        }
    }

    fun clearAll() {
        prefs.edit().remove(KEY_BOOKMARKS).apply()
    }

    private fun persist(list: List<Bookmark>) {
        val array = JSONArray()
        list.forEach { bookmark ->
            val obj = JSONObject()
            obj.put("title", bookmark.title)
            obj.put("url", bookmark.url)
            array.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }
}
