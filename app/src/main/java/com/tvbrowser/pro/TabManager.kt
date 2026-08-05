package com.tvbrowser.pro

import android.content.Context
import android.content.SharedPreferences
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Owns the list of open tabs, each backed by its own [BrowserView] (so switching
 * tabs is instant and each tab keeps its own WebView history/state), and persists
 * the set of open URLs across app restarts.
 */
class TabManager(
    private val context: Context,
    private val container: FrameLayout,
    private val browserPrefs: SharedPreferences,
    private val listener: BrowserView.Listener
) {

    data class Tab(
        val id: String,
        var title: String,
        val browserView: BrowserView
    )

    companion object {
        private const val PREFS_NAME = "tv_browser_tabs"
        private const val KEY_TAB_URLS = "tab_urls"
        private const val DEFAULT_HOME_URL = "https://www.kinokong.day"
    }

    private val tabsPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val tabs = mutableListOf<Tab>()
    var activeTabId: String? = null
        private set

    fun tabs(): List<Tab> = tabs.toList()

    fun activeTab(): Tab? = tabs.find { it.id == activeTabId }

    /** Restores tabs saved from the previous session, or opens a single default tab. */
    fun restoreOrCreateInitialTabs() {
        val savedUrls = loadSavedUrls()
        if (savedUrls.isEmpty()) {
            openNewTab(DEFAULT_HOME_URL)
        } else {
            savedUrls.forEach { url -> openNewTab(url, makeActive = false) }
            tabs.firstOrNull()?.let { switchToTab(it.id) }
        }
    }

    fun openNewTab(url: String = DEFAULT_HOME_URL, makeActive: Boolean = true): Tab {
        val id = UUID.randomUUID().toString()
        val browserView = BrowserView(context, browserPrefs, id)
        browserView.listener = listener
        val tab = Tab(id = id, title = url, browserView = browserView)
        tabs.add(tab)
        container.addView(
            browserView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        browserView.visibility = android.view.View.GONE
        browserView.loadUrl(url)
        if (makeActive) {
            switchToTab(id)
        }
        persistTabs()
        return tab
    }

    fun switchToTab(tabId: String) {
        val target = tabs.find { it.id == tabId } ?: return
        tabs.forEach { it.browserView.visibility = android.view.View.GONE }
        target.browserView.visibility = android.view.View.VISIBLE
        // requestFocus() right after flipping GONE -> VISIBLE can silently fail
        // because the view hasn't been through a layout pass yet; posting it
        // ensures that's happened first.
        target.browserView.post { target.browserView.webView.requestFocus() }
        activeTabId = tabId
    }

    fun closeTab(tabId: String) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return
        val tab = tabs[index]
        container.removeView(tab.browserView)
        tab.browserView.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            openNewTab(DEFAULT_HOME_URL)
            return
        }

        if (activeTabId == tabId) {
            val newIndex = index.coerceAtMost(tabs.size - 1)
            switchToTab(tabs[newIndex].id)
        }
        persistTabs()
    }

    fun updateTabTitle(tabId: String, title: String?, url: String?) {
        val tab = tabs.find { it.id == tabId } ?: return
        tab.title = when {
            !title.isNullOrBlank() -> title
            !url.isNullOrBlank() -> url
            else -> tab.title
        }
        persistTabs()
    }

    fun persistTabs() {
        val array = JSONArray()
        tabs.forEach { tab ->
            val url = tab.browserView.currentUrl
            if (!url.isNullOrBlank()) {
                val obj = JSONObject()
                obj.put("url", url)
                array.put(obj)
            }
        }
        tabsPrefs.edit().putString(KEY_TAB_URLS, array.toString()).apply()
    }

    private fun loadSavedUrls(): List<String> {
        val raw = tabsPrefs.getString(KEY_TAB_URLS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.optString("url")?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearSavedTabs() {
        tabsPrefs.edit().remove(KEY_TAB_URLS).apply()
    }

    fun destroyAll() {
        tabs.forEach { it.browserView.destroy() }
        tabs.clear()
    }
}
