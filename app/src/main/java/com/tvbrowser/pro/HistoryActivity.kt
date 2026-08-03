package com.tvbrowser.pro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shows browsing history as a TV-navigable list, most recent first. Selecting an
 * entry returns its URL to [MainActivity] (via activity result), which opens it in
 * a new tab. Includes a "Clear" action to wipe all history.
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_URL = "selected_url"
    }

    private lateinit var historyManager: HistoryManager
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyStateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyManager = HistoryManager(this)
        listContainer = findViewById(R.id.listContainer)
        emptyStateText = findViewById(R.id.emptyStateText)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.clearHistoryButton).setOnClickListener {
            historyManager.clearAll()
            renderList()
        }

        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val entries = historyManager.getAll()
        emptyStateText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        entries.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_link_row, listContainer, false)
            row.findViewById<TextView>(R.id.rowTitle).text = entry.title
            row.findViewById<TextView>(R.id.rowUrl).text = entry.url
            // History rows don't need an individual remove button; hide it and use
            // "Clear" for the whole list instead.
            row.findViewById<View>(R.id.rowRemoveButton).visibility = View.GONE

            val open = { openEntry(entry.url) }
            row.setOnClickListener { open() }
            row.setOnKeyListener { _, keyCode, event ->
                if (isSelectKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                    open(); true
                } else false
            }

            listContainer.addView(row)
            if (index == 0) row.requestFocus()
        }
    }

    private fun openEntry(url: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_URL, url))
        finish()
    }

    private fun isSelectKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
}
