package com.mediafetch.app.widget

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mediafetch.app.MainActivity
import com.mediafetch.app.ShareActivity
import com.mediafetch.app.engine.MediaEngine

class WidgetActionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action ?: ACTION_OPEN_MAIN

        when (action) {
            ACTION_PASTE_DOWNLOAD -> handlePasteAndDownload()
            ACTION_OPEN_INPUT -> handleOpenInput()
            ACTION_OPEN_TRIMMER -> handleOpenTrimmer()
            ACTION_OPEN_DOWNLOADS -> handleOpenDownloads()
            else -> handleOpenMain()
        }

        finish()
        overridePendingTransition(0, 0)
    }

    private fun handlePasteAndDownload() {
        val clipboardText = getClipboardText()
        val extractedUrl = if (clipboardText.isNotBlank()) MediaEngine.extractUrlFromText(clipboardText) else ""

        if (extractedUrl.isNotBlank()) {
            Toast.makeText(this, "⚡ Opening Fast Downloader...", Toast.LENGTH_SHORT).show()
            val shareIntent = Intent(this, ShareActivity::class.java).apply {
                this.action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, extractedUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(shareIntent)
        } else {
            Toast.makeText(this, "📋 No media link copied. Opening MediaFetch...", Toast.LENGTH_SHORT).show()
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("focus_input", true)
            }
            startActivity(mainIntent)
        }
    }

    private fun handleOpenInput() {
        val clipboardText = getClipboardText()
        val extractedUrl = if (clipboardText.isNotBlank()) MediaEngine.extractUrlFromText(clipboardText) else ""

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("focus_input", true)
            if (extractedUrl.isNotBlank()) {
                putExtra("auto_paste_url", extractedUrl)
            }
        }
        startActivity(mainIntent)
    }

    private fun handleOpenTrimmer() {
        val clipboardText = getClipboardText()
        val extractedUrl = if (clipboardText.isNotBlank()) MediaEngine.extractUrlFromText(clipboardText) else ""

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("target_tab", "trimmer")
            if (extractedUrl.isNotBlank()) {
                putExtra("auto_paste_url", extractedUrl)
            }
        }
        startActivity(mainIntent)
    }

    private fun handleOpenDownloads() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_downloads", true)
        }
        startActivity(mainIntent)
    }

    private fun handleOpenMain() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(mainIntent)
    }

    private fun getClipboardText(): String {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0)?.text?.toString()?.trim() ?: ""
                } else ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val ACTION_PASTE_DOWNLOAD = "com.mediafetch.app.widget.ACTION_PASTE_DOWNLOAD"
        const val ACTION_OPEN_INPUT = "com.mediafetch.app.widget.ACTION_OPEN_INPUT"
        const val ACTION_OPEN_TRIMMER = "com.mediafetch.app.widget.ACTION_OPEN_TRIMMER"
        const val ACTION_OPEN_DOWNLOADS = "com.mediafetch.app.widget.ACTION_OPEN_DOWNLOADS"
        const val ACTION_OPEN_MAIN = "com.mediafetch.app.widget.ACTION_OPEN_MAIN"
    }
}
