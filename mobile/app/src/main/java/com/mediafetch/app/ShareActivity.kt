package com.mediafetch.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.mediafetch.app.engine.MediaEngine
import com.mediafetch.app.model.DownloadTask
import com.mediafetch.app.model.QualityOption
import com.mediafetch.app.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

class ShareActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var downloadService: DownloadService? = null
    private var isServiceBound = false
    private var sharedUrl: String = ""
    private var safeTopDp: Int = 24
    private var safeBottomDp: Int = 24

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DownloadService.LocalBinder
            downloadService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
            isServiceBound = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        sharedUrl = extractUrlFromIntent(intent)

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                textZoom = 100
            }
            addJavascriptInterface(ShareBridgeInterface(), "androidBridge")
            loadUrl("file:///android_asset/share_sheet.html")
        }

        setContentView(webView)

        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density
            safeTopDp = (statusBar.top / density).toInt()
            safeBottomDp = (navBar.bottom / density).toInt()
            insets
        }

        bindDownloadService()
    }

    private fun bindDownloadService() {
        val intent = Intent(this, DownloadService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun extractUrlFromIntent(intent: Intent?): String {
        if (intent == null) return ""
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        if (text.isBlank()) return ""
        return MediaEngine.extractUrlFromText(text)
    }

    inner class ShareBridgeInterface {

        @JavascriptInterface
        fun getSharedData(): String {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            val accentColor = prefs.getString("accentColor", "#0A84FF") ?: "#0A84FF"
            val surfaceColor = prefs.getString("surfaceColor", "#121214") ?: "#121214"
            val bgColor = prefs.getString("bgColor", "#000000") ?: "#000000"
            val isDark = prefs.getBoolean("isDark", true)

            val obj = JSONObject()
            obj.put("url", sharedUrl)
            obj.put("safeTop", safeTopDp)
            obj.put("safeBottom", safeBottomDp)
            obj.put("accentColor", accentColor)
            obj.put("surfaceColor", surfaceColor)
            obj.put("bgColor", bgColor)
            obj.put("isDark", isDark)
            return obj.toString()
        }

        @JavascriptInterface
        fun resolveMedia(url: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val mediaInfo = MediaEngine.resolveMedia(url, this@ShareActivity)
                val json = mediaInfo.toJsonObject().toString()
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("window.onMediaResolved($json);", null)
                }
            }
        }

        @JavascriptInterface
        fun checkFileExists(title: String, ext: String): Boolean {
            return MediaEngine.checkFileExistsOnDevice(this@ShareActivity, title, ext)
        }

        @JavascriptInterface
        fun startDownload(
            title: String,
            author: String,
            thumbnailUrl: String,
            sourceUrl: String,
            qualityJson: String,
            totalBytes: Long
        ) {
            try {
                val qObj = JSONObject(qualityJson)
                val quality = QualityOption(
                    id = qObj.optString("id", "1080p"),
                    label = qObj.optString("label", "Full HD (1080p)"),
                    resolution = qObj.optString("resolution", "1920x1080"),
                    format = qObj.optString("format", "MP4"),
                    ext = qObj.optString("ext", "mp4"),
                    estimatedSizeBytes = totalBytes,
                    bitrateKbps = qObj.optInt("bitrateKbps", 4500),
                    isAudioOnly = qObj.optBoolean("isAudioOnly", false),
                    isImage = qObj.optBoolean("isImage", false),
                    fps = qObj.optInt("fps", 30),
                    isHdr = qObj.optBoolean("isHdr", false),
                    directDownloadUrl = if (qObj.has("directDownloadUrl") && !qObj.isNull("directDownloadUrl")) qObj.optString("directDownloadUrl") else null,
                    audioDownloadUrl = if (qObj.has("audioDownloadUrl") && !qObj.isNull("audioDownloadUrl")) qObj.optString("audioDownloadUrl") else null
                )

                val task = DownloadTask(
                    id = UUID.randomUUID().toString(),
                    url = sourceUrl,
                    title = title,
                    author = author,
                    thumbnail = thumbnailUrl,
                    quality = quality,
                    totalBytes = totalBytes
                )

                if (downloadService != null) {
                    downloadService?.enqueueDownload(task)
                } else {
                    DownloadService.enqueuePendingTask(this@ShareActivity, task)
                }

                runOnUiThread {
                    Toast.makeText(
                        this@ShareActivity,
                        "Downloading in background...",
                        Toast.LENGTH_SHORT
                    ).show()

                    vibratePhone()

                    webView.postDelayed({
                        finish()
                        overridePendingTransition(0, R.anim.slide_down)
                    }, 400)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun downloadSlide(slideJson: String, format: String) {
            try {
                val slide = JSONObject(slideJson)
                val slideUrl = slide.optString("url", "")
                val thumbUrl = slide.optString("thumbnail", slideUrl)
                val isVid = slide.optString("mediaType") == "video" || format.lowercase().contains("video") || format.lowercase().contains("1080")
                val slideIdx = slide.optInt("slideIndex", 1)

                val ext = if (isVid) "mp4" else "jpg"
                val quality = QualityOption(
                    id = "slide_${slideIdx}_$ext",
                    label = if (isVid) "Slide $slideIdx Video (HD)" else "Slide $slideIdx Photo",
                    resolution = if (isVid) "HD Video" else "Original Photo",
                    format = if (isVid) "MP4" else "JPG",
                    ext = ext,
                    estimatedSizeBytes = if (isVid) 15 * 1024 * 1024L else 3 * 1024 * 1024L,
                    bitrateKbps = if (isVid) 4000 else 0,
                    isAudioOnly = false,
                    isImage = !isVid,
                    directDownloadUrl = slideUrl
                )

                val task = DownloadTask(
                    id = UUID.randomUUID().toString(),
                    url = slideUrl,
                    title = "Slide $slideIdx",
                    author = "@instagram",
                    thumbnail = thumbUrl,
                    quality = quality,
                    totalBytes = quality.estimatedSizeBytes
                )

                if (downloadService != null) {
                    downloadService?.enqueueDownload(task)
                } else {
                    DownloadService.enqueuePendingTask(this@ShareActivity, task)
                }

                runOnUiThread {
                    Toast.makeText(this@ShareActivity, "Downloading Slide $slideIdx...", Toast.LENGTH_SHORT).show()
                    vibratePhone()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun downloadAllSlides(slidesJson: String, format: String) {
            try {
                val arr = JSONArray(slidesJson)
                for (i in 0 until arr.length()) {
                    val slide = arr.getJSONObject(i)
                    val slideUrl = slide.optString("url", "")
                    val thumbUrl = slide.optString("thumbnail", slideUrl)
                    val isVid = slide.optString("mediaType") == "video" || format.lowercase().contains("video")
                    val slideIdx = slide.optInt("slideIndex", i + 1)
                    val ext = if (isVid) "mp4" else "jpg"

                    val quality = QualityOption(
                        id = "slide_${slideIdx}_$ext",
                        label = if (isVid) "Slide $slideIdx Video (HD)" else "Slide $slideIdx Photo",
                        resolution = if (isVid) "HD Video" else "Original Photo",
                        format = if (isVid) "MP4" else "JPG",
                        ext = ext,
                        estimatedSizeBytes = if (isVid) 15 * 1024 * 1024L else 3 * 1024 * 1024L,
                        bitrateKbps = if (isVid) 4000 else 0,
                        isAudioOnly = false,
                        isImage = !isVid,
                        directDownloadUrl = slideUrl
                    )

                    val task = DownloadTask(
                        id = UUID.randomUUID().toString(),
                        url = slideUrl,
                        title = "Slide $slideIdx",
                        author = "@instagram",
                        thumbnail = thumbUrl,
                        quality = quality,
                        totalBytes = quality.estimatedSizeBytes
                    )

                    if (downloadService != null) {
                        downloadService?.enqueueDownload(task)
                    } else {
                        DownloadService.enqueuePendingTask(this@ShareActivity, task)
                    }
                }

                runOnUiThread {
                    Toast.makeText(this@ShareActivity, "Downloading all ${arr.length()} slides!", Toast.LENGTH_SHORT).show()
                    vibratePhone()
                    webView.postDelayed({
                        finish()
                        overridePendingTransition(0, R.anim.slide_down)
                    }, 500)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun openInStudio(url: String) {
            runOnUiThread {
                val intent = Intent(this@ShareActivity, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, url)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
                finish()
                overridePendingTransition(0, R.anim.slide_down)
            }
        }

        @JavascriptInterface
        fun dismiss() {
            runOnUiThread {
                finish()
                overridePendingTransition(0, R.anim.slide_down)
            }
        }

        @JavascriptInterface
        fun vibrate() {
            vibratePhone()
        }
    }

    private fun vibratePhone() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(0, R.anim.slide_down)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
