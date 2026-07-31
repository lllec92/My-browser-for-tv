package com.tvbrowser.pro

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var switchAdblock: Switch
    private lateinit var switchHeuristic: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(
            VideoInterceptor.AdBlockSettings.PREFS_NAME,
            Context.MODE_PRIVATE
        )

        switchAdblock = findViewById(R.id.switchAdblock)
        switchHeuristic = findViewById(R.id.switchHeuristic)

        switchAdblock.isChecked = VideoInterceptor.isAdBlockEnabled(prefs)
        switchHeuristic.isChecked = VideoInterceptor.isHeuristicEnabled(prefs)

        val rowAdblock = findViewById<LinearLayout>(R.id.rowAdblock)
        val rowHeuristic = findViewById<LinearLayout>(R.id.rowHeuristic)
        val rowClearTabs = findViewById<LinearLayout>(R.id.rowClearTabs)
        val backButton = findViewById<Button>(R.id.backButton)

        rowAdblock.setOnClickListener { toggleAdblock() }
        rowHeuristic.setOnClickListener { toggleHeuristic() }
        rowClearTabs.setOnClickListener { clearSavedTabs() }
        backButton.setOnClickListener { finish() }

        rowAdblock.setOnKeyListener { _, keyCode, event ->
            if (isSelectKey(keyCode) && event.action == android.view.KeyEvent.ACTION_DOWN) {
                toggleAdblock(); true
            } else false
        }
        rowHeuristic.setOnKeyListener { _, keyCode, event ->
            if (isSelectKey(keyCode) && event.action == android.view.KeyEvent.ACTION_DOWN) {
                toggleHeuristic(); true
            } else false
        }
        rowClearTabs.setOnKeyListener { _, keyCode, event ->
            if (isSelectKey(keyCode) && event.action == android.view.KeyEvent.ACTION_DOWN) {
                clearSavedTabs(); true
            } else false
        }

        rowAdblock.requestFocus()
    }

    private fun isSelectKey(keyCode: Int): Boolean = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun toggleAdblock() {
        val newValue = !switchAdblock.isChecked
        switchAdblock.isChecked = newValue
        prefs.edit().putBoolean(VideoInterceptor.AdBlockSettings.KEY_ADBLOCK_ENABLED, newValue).apply()
    }

    private fun toggleHeuristic() {
        val newValue = !switchHeuristic.isChecked
        switchHeuristic.isChecked = newValue
        prefs.edit().putBoolean(VideoInterceptor.AdBlockSettings.KEY_HEURISTIC_ENABLED, newValue).apply()
    }

    private fun clearSavedTabs() {
        getSharedPreferences("tv_browser_tabs", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
