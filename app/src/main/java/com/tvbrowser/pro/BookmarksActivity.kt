package com.tvbrowser.pro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shows saved bookmarks as a TV-navigable list. Selecting one returns its URL to
 * [MainActivity] (via activity result), which opens it in a new tab. Each row also
 * has a remove button.
 */
class BookmarksActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_URL = "selected_url"
    }

    private lateinit var bookmarksManager: BookmarksManager
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyStateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)

        bookmarksManager = BookmarksManager(this)
        listContainer = findViewById(R.id.listContainer)
        emptyStateText = findViewById(R.id.emptyStateText)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val bookmarks = bookmarksManager.getAll()
        emptyStateText.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        bookmarks.forEachIndexed { index, bookmark ->
            val row = inflater.inflate(R.layout.item_link_row, listContainer, false)
            row.findViewById<TextView>(R.id.rowTitle).text = bookmark.title
            row.findViewById<TextView>(R.id.rowUrl).text = bookmark.url

            val open = { openBookmark(bookmark.url) }
            row.setOnClickListener { open() }
            row.setOnKeyListener { _, keyCode, event ->
                if (isSelectKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                    open(); true
                } else false
            }

            val removeButton = row.findViewById<ImageButton>(R.id.rowRemoveButton)
            removeButton.setOnClickListener {
                bookmarksManager.remove(bookmark.url)
                renderList()
            }
            removeButton.setOnKeyListener { _, keyCode, event ->
                if (isSelectKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                    bookmarksManager.remove(bookmark.url)
                    renderList()
                    true
                } else false
            }

            listContainer.addView(row)
            if (index == 0) row.requestFocus()
        }
    }

    private fun openBookmark(url: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_URL, url))
        finish()
    }

    private fun isSelectKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
}
