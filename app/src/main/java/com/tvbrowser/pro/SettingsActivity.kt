package com.tvbrowser.pro

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(
            VideoInterceptor.AdBlockSettings.PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val switchAdblock = findViewById<Switch>(R.id.switchAdblock)
        val switchHeuristic = findViewById<Switch>(R.id.switchHeuristic)
        val switchDesktopMode = findViewById<Switch>(R.id.switchDesktopMode)

        switchAdblock.isChecked = VideoInterceptor.isAdBlockEnabled(prefs)
        switchHeuristic.isChecked = VideoInterceptor.isHeuristicEnabled(prefs)
        switchDesktopMode.isChecked = VideoInterceptor.isDesktopModeEnabled(prefs)

        bindToggleRow(R.id.rowAdblock, switchAdblock, VideoInterceptor.AdBlockSettings.KEY_ADBLOCK_ENABLED)
        bindToggleRow(R.id.rowHeuristic, switchHeuristic, VideoInterceptor.AdBlockSettings.KEY_HEURISTIC_ENABLED)
        bindToggleRow(R.id.rowDesktopMode, switchDesktopMode, VideoInterceptor.AdBlockSettings.KEY_DESKTOP_MODE_ENABLED)

        bindActionRow(R.id.rowClearTabs) {
            getSharedPreferences("tv_browser_tabs", Context.MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, R.string.settings_clear_tabs_title, Toast.LENGTH_SHORT).show()
        }
        bindActionRow(R.id.rowClearHistory) {
            HistoryManager(this).clearAll()
            Toast.makeText(this, R.string.settings_clear_history_title, Toast.LENGTH_SHORT).show()
        }
        bindActionRow(R.id.rowClearBookmarks) {
            BookmarksManager(this).clearAll()
            Toast.makeText(this, R.string.settings_clear_bookmarks_title, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.rowAdblock).requestFocus()
    }

    private fun isSelectKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** Wires a row so clicking it (or pressing OK while it's focused) flips [switchView]
     *  and persists the new value under [prefKey]. */
    private fun bindToggleRow(rowId: Int, switchView: Switch, prefKey: String) {
        val row = findViewById<LinearLayout>(rowId)
        val toggle = {
            val newValue = !switchView.isChecked
            switchView.isChecked = newValue
            prefs.edit().putBoolean(prefKey, newValue).apply()
        }
        row.setOnClickListener { toggle() }
        row.setOnKeyListener { _, keyCode, event ->
            if (isSelectKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                toggle(); true
            } else false
        }
    }

    /** Wires a row so clicking it (or pressing OK while it's focused) runs [action]. */
    private fun bindActionRow(rowId: Int, action: () -> Unit) {
        val row = findViewById<LinearLayout>(rowId)
        row.setOnClickListener { action() }
        row.setOnKeyListener { _, keyCode, event ->
            if (isSelectKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                action(); true
            } else false
        }
    }
}
