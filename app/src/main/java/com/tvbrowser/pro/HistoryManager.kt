package com.tvbrowser.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted browsing history: most-recently-visited first, de-duplicated by URL,
 * capped at [MAX_ENTRIES] so it never grows unbounded.
 */
class HistoryManager(context: Context) {

    data class HistoryEntry(val title: String, val url: String, val visitedAt: Long)

    companion object {
        private const val PREFS_NAME = "tv_browser_history"
        private const val KEY_HISTORY = "history"
        private const val MAX_ENTRIES = 200
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                HistoryEntry(
                    title = obj.optString("title", url),
                    url = url,
                    visitedAt = obj.optLong("visitedAt", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun recordVisit(title: String?, url: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val current = getAll().filterNot { it.url == url }.toMutableList()
        current.add(0, HistoryEntry(title = title?.takeIf { it.isNotBlank() } ?: url, url = url, visitedAt = System.currentTimeMillis()))
        persist(current.take(MAX_ENTRIES))
    }

    fun clearAll() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun persist(list: List<HistoryEntry>) {
        val array = JSONArray()
        list.forEach { entry ->
            val obj = JSONObject()
            obj.put("title", entry.title)
            obj.put("url", entry.url)
            obj.put("visitedAt", entry.visitedAt)
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }
}
