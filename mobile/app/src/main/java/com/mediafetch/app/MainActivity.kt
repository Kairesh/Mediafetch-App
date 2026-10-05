package com.mediafetch.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.core.graphics.ColorUtils
import android.os.IBinder
import android.view.View
import android.view.WindowInsets
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mediafetch.app.engine.MediaEngine
import com.mediafetch.app.engine.MediaSearchEngine
import com.mediafetch.app.model.DownloadStatus
import com.mediafetch.app.model.DownloadTask
import com.mediafetch.app.model.MediaItem
import com.mediafetch.app.model.QualityOption
import com.mediafetch.app.service.DownloadService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var downloadService: DownloadService? = null
    private var isServiceBound = false

    private var isPopupShareMode = false
    private var currentMediaItem: MediaItem? = null
    private var activeToast: Toast? = null

    fun showAppToast(msg: String) {
        runOnUiThread {
            try {
                activeToast?.cancel()
                activeToast = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT)
                activeToast?.show()
            } catch (_: Exception) {}
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DownloadService.LocalBinder
            downloadService = binder.getService()
            isServiceBound = true

            downloadService?.onTaskUpdated = { task ->
                runOnUiThread {
                    notifyTaskUpdatedToJs(task)
                }
            }

            refreshTasksToJs()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
            isServiceBound = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge layout with proper insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // Load saved cookies into MediaEngine
        try {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            val savedCookies = prefs.getString("account_cookies", "") ?: ""
            if (savedCookies.isNotBlank()) {
                MediaEngine.applyCustomCookies(savedCookies)
            }
        } catch (_: Exception) {}

        // Check if launched via share action
        isPopupShareMode = (Intent.ACTION_SEND == intent.action)

        // Inflate native XML layout hierarchy
        setContentView(R.layout.activity_main)

        webView = findViewById<WebView>(R.id.appWebView).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.textZoom = 100
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(if (isPopupShareMode) 0x00000000 else 0xFF000000.toInt())
            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    passWindowInsetsToJs()
                    handleIncomingIntent(intent)
                    refreshTasksToJs()

                    // Check for updates on app launch
                    activityScope.launch {
                        kotlinx.coroutines.delay(1000L)
                        val checkResult = com.mediafetch.app.update.UpdateManager.checkForUpdate(this@MainActivity, force = true)
                        val info = checkResult.updateInfo
                        if (info != null) {
                            val prefs = getSharedPreferences("mediafetch_update_prefs", Context.MODE_PRIVATE)
                            val skipped = prefs.getInt("skipped_version_code", -1)
                            if (skipped != info.versionCode) {
                                withContext(Dispatchers.Main) {
                                    val jsonStr = info.toJsonObject().toString()
                                    val b64 = android.util.Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                                    webView.evaluateJavascript("window.onUpdateAvailableBase64 ? window.onUpdateAvailableBase64('$b64') : (window.onUpdateAvailable && window.onUpdateAvailable(JSON.parse(decodeURIComponent(escape(atob('$b64'))))));", null)
                                }
                                com.mediafetch.app.update.UpdateManager.showUpdateNotification(this@MainActivity, info)
                            }
                        }
                    }
                }
            }
        }

        // Setup Native Bottom Navigation Bar
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)
        if (isPopupShareMode) {
            bottomNav?.visibility = View.GONE
        } else {
            bottomNav?.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_downloader -> {
                        webView.evaluateJavascript("window.toggleAppMode && window.toggleAppMode('fetch');", null)
                        true
                    }
                    R.id.nav_search_studio -> {
                        webView.evaluateJavascript("window.toggleAppMode && window.toggleAppMode('search');", null)
                        true
                    }
                    R.id.nav_history -> {
                        webView.evaluateJavascript("window.toggleAppMode && window.toggleAppMode('history');", null)
                        true
                    }
                    R.id.nav_settings -> {
                        webView.evaluateJavascript("window.toggleAppMode && window.toggleAppMode('settings');", null)
                        true
                    }
                    else -> false
                }
            }
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            val savedAccent = prefs.getString("accentColor", "#0A84FF") ?: "#0A84FF"
            val savedSurface = prefs.getString("surfaceColor", "#121214") ?: "#121214"
            val savedBg = prefs.getString("bgColor", "#000000") ?: "#000000"
            val savedIsDark = prefs.getBoolean("isDark", true)
            applyNativeThemeColors(savedAccent, savedSurface, savedBg, savedIsDark)
            val syncLauncherIcon = prefs.getBoolean("syncLauncherIcon", false)
            if (syncLauncherIcon) {
                updateAppLauncherIcon(savedAccent)
            }
        }

        // Listen for status bar / navigation bar insets and forward to WebView
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRootLayout)) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val density = resources.displayMetrics.density
            val topDp = (statusBarHeight / density).toInt().coerceAtLeast(28)
            val bottomDp = (navBarHeight / density).toInt().coerceAtLeast(24)

            webView.evaluateJavascript("window.setSystemInsets && window.setSystemInsets($topDp, $bottomDp);", null)
            insets
        }

        webView.loadUrl("file:///android_asset/index.html")

        // Auto purge app cache on launch to keep storage footprint at absolute minimum
        autoPurgeAppCache(forceAll = false)

        // Setup modern onBackPressedDispatcher callback for gesture & button navigation
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.handleBackPress ? window.handleBackPress() : false;") { result ->
                    if (result != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        requestRequiredPermissions()
        bindDownloadService()
        com.mediafetch.app.update.UpdateCheckReceiver.schedulePeriodicCheck(this)
    }

    private fun passWindowInsetsToJs() {
        val density = resources.displayMetrics.density
        var statusBarHeight = 36
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            statusBarHeight = (resources.getDimensionPixelSize(resourceId) / density).toInt()
        }
        val topDp = statusBarHeight.coerceAtLeast(32)
        webView.evaluateJavascript("window.setSystemInsets && window.setSystemInsets($topDp, 28);", null)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun handleIncomingIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val cleanUrl = MediaEngine.extractUrlFromText(sharedText)
                sendSharedUrlToJs(cleanUrl, MediaEngine.detectPlatform(cleanUrl), true)
            }
        } else if (Intent.ACTION_VIEW == action) {
            intent.dataString?.let { uriStr ->
                val cleanUrl = MediaEngine.extractUrlFromText(uriStr)
                sendSharedUrlToJs(cleanUrl, MediaEngine.detectPlatform(cleanUrl), false)
            }
        }

        // Widget & Shortcut actions
        if (intent.getBooleanExtra("open_downloads", false)) {
            webView.postDelayed({
                webView.evaluateJavascript("window.openDownloadsDrawer && window.openDownloadsDrawer();", null)
            }, 300)
        }

        if (intent.getBooleanExtra("open_update_dialog", false) || intent.action == "com.mediafetch.app.ACTION_OPEN_UPDATE") {
            val updateJson = intent.getStringExtra("update_info_json")
            if (!updateJson.isNullOrBlank()) {
                val b64 = android.util.Base64.encodeToString(updateJson.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                webView.postDelayed({
                    webView.evaluateJavascript("window.onUpdateAvailableBase64 ? window.onUpdateAvailableBase64('$b64') : (window.onUpdateAvailable && window.onUpdateAvailable(JSON.parse(decodeURIComponent(escape(atob('$b64'))))));", null)
                }, 350)
            }
        }

        val targetTab = intent.getStringExtra("target_tab")
        if (targetTab == "trimmer") {
            webView.postDelayed({
                webView.evaluateJavascript("window.openTrimmer && window.openTrimmer();", null)
            }, 300)
        }

        val autoPasteUrl = intent.getStringExtra("auto_paste_url")
        if (!autoPasteUrl.isNullOrBlank()) {
            val cleanUrl = MediaEngine.extractUrlFromText(autoPasteUrl)
            webView.postDelayed({
                sendSharedUrlToJs(cleanUrl, MediaEngine.detectPlatform(cleanUrl), true)
            }, 300)
        }

        if (intent.getBooleanExtra("focus_input", false)) {
            webView.postDelayed({
                webView.evaluateJavascript("window.focusUrlInput && window.focusUrlInput();", null)
            }, 300)
        }
    }

    private fun bindDownloadService() {
        val intent = Intent(this, DownloadService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        }
    }

    private fun sendSharedUrlToJs(url: String, platform: String, isShareSheet: Boolean) {
        val safeUrl = JSONObject.quote(url)
        val safePlatform = JSONObject.quote(platform)
        webView.evaluateJavascript("window.onSharedUrlReceived && window.onSharedUrlReceived($safeUrl, $safePlatform, $isShareSheet);", null)
    }

    private fun notifyTaskUpdatedToJs(task: DownloadTask) {
        val jsonStr = task.toJsonObject().toString()
        webView.evaluateJavascript("window.onDownloadTaskUpdated && window.onDownloadTaskUpdated($jsonStr);", null)
    }

    private fun refreshTasksToJs() {
        val tasks = downloadService?.getAllTasks() ?: emptyList()
        val array = JSONArray()
        tasks.forEach { array.put(it.toJsonObject()) }
        webView.evaluateJavascript("window.onTaskListLoaded && window.onTaskListLoaded(${array});", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBackPressedDispatcher.onBackPressed()
    }

    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun fetchMediaInfo(url: String) {
            activityScope.launch {
                try {
                    val mediaItem = MediaEngine.resolveMedia(url, context)
                    currentMediaItem = mediaItem
                    val jsonStr = mediaItem.toJsonObject().toString()
                    webView.evaluateJavascript("window.onMediaInfoLoaded && window.onMediaInfoLoaded($jsonStr);", null)
                } catch (e: Exception) {
                    val errSafe = JSONObject.quote(e.message ?: "Failed to resolve media")
                    webView.evaluateJavascript("window.onMediaInfoError && window.onMediaInfoError($errSafe);", null)
                }
            }
        }

        @JavascriptInterface
        fun startDownload(
            qualityId: String,
            isTrimmed: Boolean,
            clipStartSec: Long,
            clipEndSec: Long
        ) {
            startDownload(qualityId, isTrimmed, clipStartSec, clipEndSec, "")
        }

        @JavascriptInterface
        fun startDownload(
            qualityId: String,
            isTrimmed: Boolean,
            clipStartSec: Long,
            clipEndSec: Long,
            customTitle: String,
            audioFxJson: String = "{}"
        ) {
            val media = currentMediaItem ?: return
            var quality = media.qualities.find { it.id == qualityId }
            if (quality == null && (qualityId.startsWith("img_") || qualityId.contains("photo") || qualityId.contains("image") || qualityId.contains("gif") || qualityId.contains("thumb"))) {
                var directImg = media.imageUrls.firstOrNull() ?: media.thumbnail
                if (directImg.contains("i.ytimg.com") && directImg.contains("hq720.jpg")) {
                    directImg = directImg.replace("hq720.jpg", "maxresdefault.jpg")
                }
                if (directImg.isBlank() && media.url.isNotBlank()) {
                    val m = java.util.regex.Pattern.compile("(?:v=|shorts/|youtu\\.be/|embed/)([a-zA-Z0-9_-]{11})").matcher(media.url)
                    if (m.find()) {
                        val vid = m.group(1) ?: ""
                        directImg = "https://i.ytimg.com/vi/$vid/maxresdefault.jpg"
                    }
                }
                val isGif = qualityId == "img_gif" || qualityId.contains("gif") || directImg.lowercase().contains(".gif")
                val ext = when {
                    isGif -> "gif"
                    qualityId == "img_webp" -> "webp"
                    qualityId == "img_png" -> "png"
                    else -> "jpg"
                }
                quality = QualityOption(
                    id = qualityId,
                    label = when {
                        isGif -> "Animated GIF (Original Motion)"
                        qualityId == "img_png" -> "PNG Lossless"
                        qualityId == "img_webp" -> "WebP High Efficiency"
                        qualityId == "img_1080p" -> "Full HD Thumbnail (1080p)"
                        else -> "Original Thumbnail (Ultra HD)"
                    },
                    resolution = if (isGif) "GIF Animation • Infinite Loop" else "Original Resolution • 100% Quality",
                    format = if (isGif) "Animation • GIF" else "Image",
                    ext = ext,
                    estimatedSizeBytes = if (isGif) 3 * 1024 * 1024 else 4 * 1024 * 1024,
                    isImage = true,
                    directDownloadUrl = directImg
                )
            }

            if (quality == null) {
                showAppToast("Please select a quality format")
                return
            }

            val task = DownloadTask(
                id = System.currentTimeMillis().toString(),
                url = media.url,
                title = if (customTitle.isNotBlank()) customTitle else media.title,
                author = media.author,
                thumbnail = media.thumbnail,
                quality = quality,
                totalBytes = quality.estimatedSizeBytes,
                isTrimmed = isTrimmed,
                clipStartSeconds = clipStartSec,
                clipEndSeconds = clipEndSec,
                durationSeconds = media.durationSeconds,
                audioFxJson = audioFxJson
            )

            downloadService?.enqueueDownload(task)
            showAppToast("Downloading: ${quality.label}")
        }

        @JavascriptInterface
        fun startBatchDownload(qualityId: String, selectedIndicesJson: String) {
            startBatchDownloadWithOptions(qualityId, selectedIndicesJson, false, "")
        }

        @JavascriptInterface
        fun startBatchDownloadWithOptions(qualityId: String, selectedIndicesJson: String, asZip: Boolean, customFolder: String) {
            val media = currentMediaItem ?: return
            if (!media.isPlaylist) return

            val indices = JSONArray(selectedIndicesJson)
            if (indices.length() == 0) return

            val isAudioReq = qualityId.contains("audio", ignoreCase = true) ||
                             qualityId.contains("320") ||
                             qualityId.contains("192") ||
                             qualityId.contains("128") ||
                             qualityId.contains("wav", ignoreCase = true) ||
                             qualityId.contains("m4a", ignoreCase = true) ||
                             qualityId.contains("mp3", ignoreCase = true)
            
            val quality = media.qualities.find { it.id == qualityId }
                ?: if (isAudioReq) {
                    media.qualities.find { it.isAudioOnly } ?: QualityOption(
                        id = "audio_mp3_320",
                        label = "Studio Master (320 kbps)",
                        resolution = "320 kbps • Ultra Fidelity",
                        format = "Audio • AAC / M4A",
                        ext = "m4a",
                        estimatedSizeBytes = 7 * 1024 * 1024,
                        isAudioOnly = true
                    )
                } else {
                    media.qualities.find { it.id.contains("1080") } ?: media.qualities.firstOrNull { !it.isAudioOnly } ?: media.qualities.firstOrNull()
                } ?: return

            val batchId = "batch_" + System.currentTimeMillis()
            val totalCount = indices.length()
            val safeZipName = (media.title.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(40)) + "_Bundle.zip"

            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            val savedPath = prefs.getString("custom_download_folder_path", "") ?: ""
            val resolvedCustomFolder = when {
                customFolder.isNotBlank() && File(customFolder).isAbsolute -> customFolder
                savedPath.isNotBlank() -> savedPath
                customFolder.isNotBlank() -> File(Environment.getExternalStorageDirectory(), customFolder).absolutePath
                else -> ""
            }

            if (asZip) {
                downloadService?.registerBatchForZip(batchId, totalCount, safeZipName, resolvedCustomFolder.ifBlank { null })
            }

            for (i in 0 until indices.length()) {
                val index = indices.getInt(i)
                if (index in media.playlistItems.indices) {
                    val item = media.playlistItems[index]
                    val cleanExt = item.title.substringAfterLast(".", "").lowercase()
                    val isItemImage = item.isImage || cleanExt in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "tiff")
                    val isItemAudio = !isItemImage && (cleanExt in listOf("mp3", "wav", "m4a", "flac", "aac", "ogg") || isAudioReq)

                    val taskQuality = when {
                        isItemImage -> {
                            val effExt = if (cleanExt in listOf("jpg", "jpeg", "png", "webp", "gif")) cleanExt else "jpg"
                            QualityOption(
                                id = "img_${effExt}_$index",
                                label = "Original Photo (${effExt.uppercase()})",
                                resolution = "Original Photo",
                                format = effExt.uppercase(),
                                ext = effExt,
                                estimatedSizeBytes = 3 * 1024 * 1024L,
                                isAudioOnly = false,
                                isImage = true,
                                directDownloadUrl = item.url
                            )
                        }
                        isItemAudio -> {
                            val effExt = if (cleanExt in listOf("mp3", "wav", "m4a", "flac", "aac", "ogg")) cleanExt else "mp3"
                            QualityOption(
                                id = "audio_${effExt}_$index",
                                label = "Audio (${effExt.uppercase()})",
                                resolution = "Audio Stream",
                                format = effExt.uppercase(),
                                ext = effExt,
                                estimatedSizeBytes = 5 * 1024 * 1024L,
                                isAudioOnly = true,
                                isImage = false,
                                directDownloadUrl = item.url
                            )
                        }
                        else -> {
                            val effExt = if (cleanExt in listOf("mp4", "mov", "mkv", "webm", "avi")) cleanExt else quality.ext
                            quality.copy(
                                ext = effExt,
                                format = effExt.uppercase(),
                                isAudioOnly = false,
                                isImage = false,
                                directDownloadUrl = item.url
                            )
                        }
                    }

                    val task = DownloadTask(
                        id = "${batchId}_$i",
                        url = item.url,
                        title = item.title,
                        author = item.author,
                        thumbnail = item.thumbnail,
                        quality = taskQuality,
                        totalBytes = taskQuality.estimatedSizeBytes,
                        batchId = batchId,
                        asZip = asZip,
                        zipName = safeZipName,
                        customFolder = resolvedCustomFolder.ifBlank { null },
                        subFolderName = item.subFolderName
                    )
                    downloadService?.enqueueDownload(task)
                }
            }

            val modeMsg = if (asZip) "as single .ZIP archive" else "as separate files"
            showAppToast("Enqueued $totalCount items ($modeMsg)!")
        }

        @JavascriptInterface
        fun startCarouselDownloadWithOptions(selectedSlidesJson: String, asZip: Boolean, customFolder: String) {
            val media = currentMediaItem ?: return
            val slidesArray = JSONArray(selectedSlidesJson)
            if (slidesArray.length() == 0) return

            val batchId = "carousel_" + System.currentTimeMillis()
            val totalCount = slidesArray.length()
            val safeZipName = (media.title.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(40)) + "_Carousel.zip"

            if (asZip) {
                downloadService?.registerBatchForZip(batchId, totalCount, safeZipName, customFolder.ifBlank { null })
            }

            for (i in 0 until slidesArray.length()) {
                val slideObj = slidesArray.getJSONObject(i)
                val sUrl = slideObj.optString("url", "")
                val sIndex = slideObj.optInt("slideIndex", i + 1)
                val sType = slideObj.optString("mediaType", "image")
                val isVid = sType == "video"
                val sThumb = slideObj.optString("thumbnail", sUrl)

                val quality = if (isVid) {
                    media.qualities.find { !it.isAudioOnly && !it.isImage } ?: QualityOption(
                        id = "carousel_vid_$sIndex",
                        label = "Slide $sIndex (1080p)",
                        resolution = "1080p",
                        format = "Video • MP4",
                        ext = "mp4",
                        directDownloadUrl = sUrl,
                        estimatedSizeBytes = 12 * 1024 * 1024
                    )
                } else {
                    QualityOption(
                        id = "carousel_img_$sIndex",
                        label = "Slide $sIndex (Photo)",
                        resolution = "High Res",
                        format = "Image • JPG",
                        ext = "jpg",
                        isImage = true,
                        directDownloadUrl = sUrl,
                        estimatedSizeBytes = 2 * 1024 * 1024
                    )
                }

                val task = DownloadTask(
                    id = "${batchId}_$sIndex",
                    url = sUrl,
                    title = "${media.title} - Slide $sIndex",
                    author = media.author,
                    thumbnail = sThumb,
                    quality = quality,
                    totalBytes = quality.estimatedSizeBytes,
                    batchId = batchId,
                    asZip = asZip,
                    zipName = safeZipName,
                    customFolder = customFolder.ifBlank { null }
                )
                downloadService?.enqueueDownload(task)
            }

            val modeMsg = if (asZip) "as single .ZIP archive" else "as separate files"
            showAppToast("Enqueued $totalCount slides ($modeMsg)!")
        }

        @JavascriptInterface
        fun pickCustomFolder() {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER)
            } catch (e: Exception) {
                showAppToast("Folder picker unavailable: ${e.message}")
            }
        }

        @JavascriptInterface
        fun getCustomFolder(): String {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            return prefs.getString("custom_download_folder_name", "Download/MediaFetch") ?: "Download/MediaFetch"
        }

        @JavascriptInterface
        fun checkAlreadyDownloaded(url: String): Boolean {
            val s = downloadService ?: return false
            val clean = url.trim().lowercase()
            return s.getAllTasks().any {
                it.status == com.mediafetch.app.model.DownloadStatus.COMPLETED &&
                (it.url.lowercase() == clean ||
                 (it.quality.directDownloadUrl != null && it.quality.directDownloadUrl!!.lowercase() == clean) ||
                 (it.quality.fallbackUrl != null && it.quality.fallbackUrl!!.lowercase() == clean))
            }
        }

        @JavascriptInterface
        fun pauseDownload(taskId: String) {
            downloadService?.pauseDownload(taskId)
        }

        @JavascriptInterface
        fun resumeDownload(taskId: String) {
            downloadService?.resumeDownload(taskId)
        }

        @JavascriptInterface
        fun cancelDownload(taskId: String) {
            downloadService?.cancelDownload(taskId)
        }

        @JavascriptInterface
        fun removeTask(taskId: String) {
            downloadService?.removeTask(taskId)
        }

        @JavascriptInterface
        fun startDownloadWithExtra(
            qualityId: String,
            isTrimmed: Boolean,
            clipStartSec: Long,
            clipEndSec: Long,
            customTitle: String,
            audioFxJson: String,
            subtitleUrl: String,
            subtitleLang: String,
            chapterTitle: String
        ) {
            val media = currentMediaItem ?: return
            if (media.isPlaylist && media.playlistItems.isNotEmpty()) {
                val allIndices = JSONArray((0 until media.playlistItems.size).toList())
                startBatchDownloadWithOptions(qualityId, allIndices.toString(), false, "")
                return
            }
            val quality = media.qualities.find { it.id == qualityId } ?: media.qualities.firstOrNull() ?: return

            val task = DownloadTask(
                id = System.currentTimeMillis().toString(),
                url = media.url,
                title = if (customTitle.isNotBlank()) customTitle else media.title,
                author = media.author,
                thumbnail = media.thumbnail,
                quality = quality,
                totalBytes = quality.estimatedSizeBytes,
                isTrimmed = isTrimmed,
                clipStartSeconds = clipStartSec,
                clipEndSeconds = clipEndSec,
                durationSeconds = media.durationSeconds,
                audioFxJson = if (audioFxJson.isNotBlank()) audioFxJson else "{}",
                subtitleUrl = subtitleUrl.ifBlank { null },
                subtitleLang = subtitleLang.ifBlank { null },
                chapterTitle = chapterTitle.ifBlank { null }
            )

            downloadService?.enqueueDownload(task)
            showAppToast("Downloading: ${quality.label}")
        }

        // --- In-App Update Bridge ---
        @JavascriptInterface
        fun checkForUpdate(force: Boolean) {
            activityScope.launch {
                val checkResult = com.mediafetch.app.update.UpdateManager.checkForUpdate(this@MainActivity, force)
                val info = checkResult.updateInfo
                if (info != null) {
                    com.mediafetch.app.update.UpdateManager.showUpdateNotification(this@MainActivity, info)
                }
                withContext(Dispatchers.Main) {
                    val jsonStr = checkResult.toJsonObject().toString()
                    val b64 = android.util.Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                    webView.evaluateJavascript("window.onUpdateCheckResultBase64 ? window.onUpdateCheckResultBase64('$b64') : (window.onUpdateCheckResult && window.onUpdateCheckResult(JSON.parse(decodeURIComponent(escape(atob('$b64'))))));", null)
                }
            }
        }

        @JavascriptInterface
        fun downloadAndInstallUpdate(apkUrl: String) {
            downloadAndInstallUpdateWithVersion(apkUrl, "1.0.6")
        }

        @JavascriptInterface
        fun downloadAndInstallUpdateWithVersion(apkUrl: String, version: String) {
            activityScope.launch {
                com.mediafetch.app.update.UpdateManager.downloadAndInstall(
                    activity = this@MainActivity,
                    apkUrl = apkUrl,
                    targetVersion = if (version.isNotBlank()) version else "1.0.6",
                    onProgress = { percent, path ->
                        val safePath = JSONObject.quote(path)
                        webView.evaluateJavascript("window.onUpdateDownloadProgress && window.onUpdateDownloadProgress($percent, $safePath);", null)
                    },
                    onError = { err ->
                        val errSafe = JSONObject.quote(err)
                        webView.evaluateJavascript("window.onUpdateDownloadError && window.onUpdateDownloadError($errSafe);", null)
                    }
                )
            }
        }

        @JavascriptInterface
        fun snoozeUpdate() {
            com.mediafetch.app.update.UpdateManager.snoozeRemindLater(this@MainActivity)
        }

        @JavascriptInterface
        fun skipUpdate(versionCode: Int) {
            com.mediafetch.app.update.UpdateManager.skipVersion(this@MainActivity, versionCode)
        }

        // --- Account Cookies Bridge ---
        @JavascriptInterface
        fun saveCustomCookies(rawCookies: String) {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("account_cookies", rawCookies.trim()).apply()
            MediaEngine.applyCustomCookies(rawCookies)
            try {
                val cm = android.webkit.CookieManager.getInstance()
                cm.setCookie("https://youtube.com", rawCookies)
                cm.setCookie("https://instagram.com", rawCookies)
                cm.flush()
            } catch (_: Exception) {}
            val count = MediaEngine.getCookiesCount()
            showAppToast("Cookies applied ($count loaded)")
        }

        @JavascriptInterface
        fun getCustomCookies(): String {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            return prefs.getString("account_cookies", "") ?: ""
        }

        @JavascriptInterface
        fun clearCustomCookies() {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove("account_cookies").apply()
            MediaEngine.clearCustomCookies()
            try {
                val cm = android.webkit.CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
            } catch (_: Exception) {}
            showAppToast("Cookies cleared")
        }

        @JavascriptInterface
        fun getCookiesCount(): Int = MediaEngine.getCookiesCount()

        // --- Custom Filename Template Bridge ---
        @JavascriptInterface
        fun saveFilenameTemplate(template: String) {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_filename_template", template.trim()).apply()
        }

        @JavascriptInterface
        fun getFilenameTemplate(): String {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            return prefs.getString("custom_filename_template", "{title} [{quality}]") ?: "{title} [{quality}]"
        }

        // --- Max Concurrent Downloads Bridge ---
        @JavascriptInterface
        fun saveMaxConcurrentDownloads(count: Int) {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("max_concurrent_downloads", count.coerceIn(1, 5)).apply()
        }

        @JavascriptInterface
        fun getMaxConcurrentDownloads(): Int {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            return prefs.getInt("max_concurrent_downloads", 2).coerceIn(1, 5)
        }

        @JavascriptInterface
        fun clearAllTasks() {
            downloadService?.clearAllTasks()
        }



        @JavascriptInterface
        fun getAppCacheSize(): String {
            return try {
                var sizeBytes = 0L
                context.cacheDir?.walkTopDown()?.forEach { if (it.isFile) sizeBytes += it.length() }
                context.externalCacheDir?.walkTopDown()?.forEach { if (it.isFile) sizeBytes += it.length() }
                context.codeCacheDir?.walkTopDown()?.forEach { if (it.isFile) sizeBytes += it.length() }

                try {
                    val dataDir = context.applicationInfo.dataDir
                    if (dataDir != null) {
                        val appWebviewDir = File(dataDir, "app_webview")
                        if (appWebviewDir.exists()) {
                            listOf(
                                File(appWebviewDir, "Default/HTTP Cache"),
                                File(appWebviewDir, "Default/GPUCache"),
                                File(appWebviewDir, "Default/Code Cache"),
                                File(appWebviewDir, "Default/Service Worker/CacheStorage"),
                                File(appWebviewDir, "ShaderCache"),
                                File(appWebviewDir, "GrShaderCache")
                            ).forEach { dir ->
                                if (dir.exists()) {
                                    dir.walkTopDown().forEach { if (it.isFile) sizeBytes += it.length() }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                val mb = sizeBytes.toDouble() / (1024 * 1024)
                if (mb < 0.1) "0.0 MB" else String.format(java.util.Locale.US, "%.1f MB", mb)
            } catch (e: Exception) {
                "0.0 MB"
            }
        }

        @JavascriptInterface
        fun clearAppCache(): String {
            try {
                autoPurgeAppCache(forceAll = true)
                context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                context.codeCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                try {
                    val dataDir = context.applicationInfo.dataDir
                    if (dataDir != null) {
                        val appWebviewDir = File(dataDir, "app_webview")
                        if (appWebviewDir.exists()) {
                            listOf(
                                File(appWebviewDir, "Default/HTTP Cache"),
                                File(appWebviewDir, "Default/GPUCache"),
                                File(appWebviewDir, "Default/Code Cache"),
                                File(appWebviewDir, "Default/Service Worker/CacheStorage"),
                                File(appWebviewDir, "ShaderCache"),
                                File(appWebviewDir, "GrShaderCache")
                            ).forEach { dir ->
                                if (dir.exists()) dir.deleteRecursively()
                            }
                        }
                    }
                } catch (_: Exception) {}
                runOnUiThread {
                    webView.clearCache(true)
                }
            } catch (_: Exception) {}
            return getAppCacheSize()
        }

        @JavascriptInterface
        fun getDownloadDirectoryPath(): String {
            return try {
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath + "/MediaFetch"
            } catch (e: Exception) {
                "Downloads/MediaFetch"
            }
        }

        @JavascriptInterface
        fun getSearchSuggestions(query: String) {
            activityScope.launch {
                try {
                    val suggestions = MediaSearchEngine.getSearchSuggestions(query)
                    val jsonArr = JSONArray(suggestions).toString()
                    webView.evaluateJavascript("window.onSearchSuggestionsLoaded && window.onSearchSuggestionsLoaded($jsonArr);", null)
                } catch (e: Exception) {
                    webView.evaluateJavascript("window.onSearchSuggestionsLoaded && window.onSearchSuggestionsLoaded([]);", null)
                }
            }
        }

        @JavascriptInterface
        fun performMediaSearch(query: String, platformsJson: String, page: Int) {
            activityScope.launch {
                try {
                    val platList = mutableListOf<String>()
                    val arr = JSONArray(platformsJson)
                    for (i in 0 until arr.length()) {
                        platList.add(arr.getString(i).lowercase())
                    }
                    val results = MediaSearchEngine.searchAll(query, platList, page)
                    val resultsArr = JSONArray()
                    for (item in results) {
                        resultsArr.put(item.toJson())
                    }
                    val resString = resultsArr.toString()
                    webView.evaluateJavascript("window.onSearchResultsLoaded && window.onSearchResultsLoaded($resString);", null)
                } catch (e: Exception) {
                    val errSafe = JSONObject.quote(e.message ?: "Search failed")
                    webView.evaluateJavascript("window.onSearchResultsError && window.onSearchResultsError($errSafe);", null)
                }
            }
        }

        @JavascriptInterface
        fun downloadSearchResult(itemJsonStr: String) {
            downloadSearchResultWithFormat(itemJsonStr, "best")
        }

        @JavascriptInterface
        fun downloadSearchResultWithFormat(itemJsonStr: String, requestedFormat: String) {
            activityScope.launch {
                try {
                    val obj = JSONObject(itemJsonStr)
                    val directUrl = obj.optString("directUrl", "")
                    val pageUrl = obj.optString("pageUrl", "")
                    val dlUrl = obj.optString("downloadUrl", directUrl)
                    val title = obj.optString("title", "Media Item")
                    val author = obj.optString("author", "Media Creator")
                    val thumb = obj.optString("thumbnail", "")
                    val mediaType = obj.optString("mediaType", "video")

                    val targetUrl = if (directUrl.isNotBlank()) directUrl else (if (dlUrl.isNotBlank()) dlUrl else pageUrl)

                    // Check if it's already a direct CDN media asset or file link
                    val isDirectCdn = targetUrl.contains("cdninstagram.com", ignoreCase = true) ||
                            targetUrl.contains("fbcdn.net", ignoreCase = true) ||
                            targetUrl.contains("pinimg.com", ignoreCase = true) ||
                            targetUrl.contains("twimg.com", ignoreCase = true) ||
                            targetUrl.contains("tiktokcdn.com", ignoreCase = true) ||
                            targetUrl.contains("byteoversea.com", ignoreCase = true) ||
                            mediaType == "image" ||
                            targetUrl.contains(".jpg", ignoreCase = true) ||
                            targetUrl.contains(".png", ignoreCase = true) ||
                            targetUrl.contains(".webp", ignoreCase = true) ||
                            targetUrl.contains(".gif", ignoreCase = true) ||
                            (targetUrl.contains(".mp4", ignoreCase = true) && !targetUrl.contains("youtube.com") && !targetUrl.contains("tiktok.com"))

                    // Check if it's a social web page (YouTube, TikTok, Instagram, Twitter) needing stream resolution
                    val isSocialOrYt = !isDirectCdn && (
                            targetUrl.contains("youtube.com", ignoreCase = true) ||
                            targetUrl.contains("youtu.be", ignoreCase = true) ||
                            targetUrl.contains("tiktok.com", ignoreCase = true) ||
                            targetUrl.contains("instagram.com", ignoreCase = true) ||
                            targetUrl.contains("twitter.com", ignoreCase = true) ||
                            targetUrl.contains("x.com", ignoreCase = true)
                    )

                    if (isSocialOrYt) {
                        showAppToast("Resolving $title stream...")

                        val resolved = MediaEngine.resolveMedia(targetUrl, this@MainActivity)
                        val chosenQuality = when (requestedFormat.lowercase()) {
                            "audio", "mp3" -> resolved.qualities.firstOrNull { it.isAudioOnly } ?: resolved.qualities.firstOrNull()
                            "1080p", "1080" -> resolved.qualities.firstOrNull { it.resolution.contains("1080") } ?: resolved.qualities.firstOrNull { !it.isAudioOnly }
                            "720p", "720" -> resolved.qualities.firstOrNull { it.resolution.contains("720") } ?: resolved.qualities.firstOrNull { !it.isAudioOnly }
                            "480p", "360p", "sd" -> resolved.qualities.firstOrNull { it.resolution.contains("480") || it.resolution.contains("360") } ?: resolved.qualities.lastOrNull { !it.isAudioOnly }
                            else -> resolved.qualities.firstOrNull { !it.isAudioOnly } ?: resolved.qualities.firstOrNull()
                        } ?: throw Exception("No downloadable media stream found")

                        val task = DownloadTask(
                            id = "task_" + System.currentTimeMillis(),
                            url = targetUrl,
                            title = resolved.title.ifBlank { title },
                            author = resolved.author.ifBlank { author },
                            thumbnail = resolved.thumbnail.ifBlank { thumb },
                            quality = chosenQuality,
                            totalBytes = chosenQuality.estimatedSizeBytes
                        )

                        downloadService?.enqueueDownload(task)
                        notifyTaskUpdatedToJs(task)

                        showAppToast("Downloading: ${task.title} (${chosenQuality.label})")
                    } else {
                        // Direct media URL (Wikimedia, Openverse, Wallhaven, Imgflip, FreeSound)
                        val ext = when {
                            requestedFormat.equals("audio", ignoreCase = true) || requestedFormat.equals("mp3", ignoreCase = true) -> "mp3"
                            requestedFormat.equals("gif", ignoreCase = true) || targetUrl.contains(".gif", ignoreCase = true) -> "gif"
                            mediaType == "audio" -> "mp3"
                            mediaType == "image" || mediaType == "gif" -> when {
                                targetUrl.contains(".png", ignoreCase = true) -> "png"
                                targetUrl.contains(".webp", ignoreCase = true) -> "webp"
                                targetUrl.contains(".gif", ignoreCase = true) -> "gif"
                                else -> "jpg"
                            }
                            targetUrl.contains(".webm", ignoreCase = true) -> "webm"
                            targetUrl.contains(".ogg", ignoreCase = true) -> "ogg"
                            else -> "mp4"
                        }
                        val isAudio = ext == "mp3" || ext == "ogg" || mediaType == "audio"
                        val isImage = ext == "jpg" || ext == "png" || ext == "webp" || ext == "gif" || mediaType == "image" || mediaType == "gif"

                        val quality = com.mediafetch.app.model.QualityOption(
                            id = "search_dl_" + System.currentTimeMillis(),
                            label = if (isAudio) "HQ Audio" else (if (ext == "gif") "Animated GIF" else if (isImage) "Full Image" else "HD Media"),
                            resolution = if (isAudio) "Audio Only" else (if (ext == "gif") "GIF Animation" else if (isImage) "High Res" else "Original"),
                            format = if (ext == "gif") "GIF" else ext.uppercase(),
                            ext = ext,
                            estimatedSizeBytes = 6 * 1024 * 1024L,
                            isAudioOnly = isAudio,
                            isImage = isImage,
                            directDownloadUrl = targetUrl
                        )

                        val task = DownloadTask(
                            id = "task_" + System.currentTimeMillis(),
                            url = targetUrl,
                            title = title,
                            author = author,
                            thumbnail = thumb,
                            quality = quality,
                            totalBytes = quality.estimatedSizeBytes
                        )

                        downloadService?.enqueueDownload(task)
                        notifyTaskUpdatedToJs(task)

                        showAppToast("Downloading: $title")
                    }
                } catch (e: Exception) {
                    showAppToast("Download failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun openFile(filePath: String) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    showAppToast("File does not exist")
                    return
                }

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val isAudio = filePath.endsWith(".mp3") || filePath.endsWith(".wav") || filePath.endsWith(".m4a")
                val isImage = filePath.endsWith(".jpg") || filePath.endsWith(".png") || filePath.endsWith(".webp")
                val mime = when {
                    isAudio -> "audio/*"
                    isImage -> "image/*"
                    else -> "video/*"
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                showAppToast("Cannot open file: ${e.message}")
            }
        }

        @JavascriptInterface
        fun shareFile(filePath: String) {
            try {
                val file = File(filePath)
                if (!file.exists()) return
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val isAudio = filePath.endsWith(".mp3") || filePath.endsWith(".wav") || filePath.endsWith(".m4a")
                val isImage = filePath.endsWith(".jpg") || filePath.endsWith(".png") || filePath.endsWith(".webp")
                val mime = when {
                    isAudio -> "audio/*"
                    isImage -> "image/*"
                    else -> "video/*"
                }

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(Intent.createChooser(intent, "Share Media"))
            } catch (e: Exception) {
                showAppToast("Cannot share file: ${e.message}")
            }
        }

        @JavascriptInterface
        fun getAppVersionName(): String {
            return try {
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                pInfo.versionName ?: "1.0.5"
            } catch (_: Exception) {
                "1.0.5"
            }
        }

        @JavascriptInterface
        fun checkFileExists(title: String, ext: String): Boolean {
            return MediaEngine.checkFileExistsOnDevice(context, title, ext)
        }

        @JavascriptInterface
        fun closeApp() {
            finish()
        }

        @JavascriptInterface
        fun showToast(msg: String) {
            showAppToast(msg)
        }

        @JavascriptInterface
        fun updateNativeTheme(accentColorHex: String, surfaceColorHex: String, bgColorHex: String) {
            updateNativeTheme(accentColorHex, surfaceColorHex, bgColorHex, true)
        }

        @JavascriptInterface
        fun setLauncherIconSync(enabled: Boolean) {
            try {
                val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("syncLauncherIcon", enabled).apply()
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun updateAppIconToTheme(accentColorHex: String) {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            val syncLauncherIcon = prefs.getBoolean("syncLauncherIcon", false)
            if (syncLauncherIcon) {
                updateAppLauncherIcon(accentColorHex)
            }
        }

        @JavascriptInterface
        fun updateNativeTheme(accentColorHex: String, surfaceColorHex: String, bgColorHex: String, isDark: Boolean, syncLauncherIcon: Boolean) {
            try {
                val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("accentColor", accentColorHex)
                    .putString("surfaceColor", surfaceColorHex)
                    .putString("bgColor", bgColorHex)
                    .putBoolean("isDark", isDark)
                    .putBoolean("syncLauncherIcon", syncLauncherIcon)
                    .apply()
            } catch (_: Exception) {}
            applyNativeThemeColors(accentColorHex, surfaceColorHex, bgColorHex, isDark)
            if (syncLauncherIcon) {
                updateAppLauncherIcon(accentColorHex)
            }
        }

        @JavascriptInterface
        fun updateNativeTheme(accentColorHex: String, surfaceColorHex: String, bgColorHex: String, isDark: Boolean) {
            val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
            val syncLauncherIcon = prefs.getBoolean("syncLauncherIcon", false)
            updateNativeTheme(accentColorHex, surfaceColorHex, bgColorHex, isDark, syncLauncherIcon)
        }

        @JavascriptInterface
        fun onAppModeChanged(mode: String) {
            runOnUiThread {
                try {
                    val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)
                    val targetId = when (mode) {
                        "fetch" -> R.id.nav_downloader
                        "search" -> R.id.nav_search_studio
                        "history" -> R.id.nav_history
                        "settings" -> R.id.nav_settings
                        else -> null
                    }
                    if (targetId != null && bottomNav?.selectedItemId != targetId) {
                        bottomNav?.selectedItemId = targetId
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun updateAppLauncherIcon(accentColorHex: String) {
        try {
            val color = Color.parseColor(accentColorHex.trim())
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            val hue = hsv[0] // 0..360
            val sat = hsv[1]

            val colorSuffix = if (sat < 0.2f) {
                "Default"
            } else {
                when {
                    hue in 15f..45f -> "Orange"
                    hue in 45f..70f -> "Gold"
                    hue in 70f..165f -> "Green"
                    hue in 250f..315f -> "Purple"
                    (hue >= 335f || hue < 15f) -> "Red"
                    else -> "Default" // Blue/Cyan
                }
            }

            val targetMainAlias = "MainActivity$colorSuffix"
            val targetShareAlias = "ShareActivity$colorSuffix"

            val suffixes = listOf("Default", "Orange", "Green", "Purple", "Red", "Gold")
            val pm = packageManager
            val pkg = packageName

            for (suffix in suffixes) {
                // Update Main Launcher Alias
                val mainAlias = "MainActivity$suffix"
                val shouldMain = (mainAlias == targetMainAlias)
                val curMain = pm.getComponentEnabledSetting(ComponentName(pkg, "$pkg.$mainAlias"))
                val targetMain = if (shouldMain) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (curMain != targetMain) {
                    pm.setComponentEnabledSetting(ComponentName(pkg, "$pkg.$mainAlias"), targetMain, PackageManager.DONT_KILL_APP)
                }

                // Update Share Sheet Alias
                val shareAlias = "ShareActivity$suffix"
                val shouldShare = (shareAlias == targetShareAlias)
                val curShare = pm.getComponentEnabledSetting(ComponentName(pkg, "$pkg.$shareAlias"))
                val targetShare = if (shouldShare) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (curShare != targetShare) {
                    pm.setComponentEnabledSetting(ComponentName(pkg, "$pkg.$shareAlias"), targetShare, PackageManager.DONT_KILL_APP)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun applyNativeThemeColors(accentColorHex: String, surfaceColorHex: String, bgColorHex: String, isDark: Boolean = true) {
        runOnUiThread {
            try {
                val accentColor = Color.parseColor(accentColorHex)
                val surfaceColor = if (isDark) Color.parseColor(surfaceColorHex) else Color.parseColor("#FFFFFF")
                val bgColor = if (isDark) Color.parseColor(bgColorHex) else Color.parseColor("#F4F6F9")

                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark

                // Dynamic TaskDescription so Android Recents (Tabs / App Switcher) displays sleek header without blue container
                try {
                    val taskHeaderColor = if (isDark) Color.parseColor("#121214") else Color.parseColor("#FFFFFF")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val taskDesc = android.app.ActivityManager.TaskDescription.Builder()
                            .setPrimaryColor(taskHeaderColor)
                            .setLabel("MediaFetch")
                            .build()
                        setTaskDescription(taskDesc)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        @Suppress("DEPRECATION")
                        setTaskDescription(android.app.ActivityManager.TaskDescription("MediaFetch", null, taskHeaderColor))
                    }
                } catch (_: Exception) {}

                val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)
                bottomNav?.let { nav ->
                    nav.setBackgroundColor(surfaceColor)
                    val states = arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(-android.R.attr.state_checked)
                    )
                    val colors = intArrayOf(
                        accentColor,
                        if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#64748B")
                    )
                    val colorStateList = ColorStateList(states, colors)
                    nav.itemIconTintList = colorStateList
                    nav.itemTextColor = colorStateList

                    try {
                        nav.itemActiveIndicatorColor = ColorStateList.valueOf(
                            ColorUtils.setAlphaComponent(accentColor, if (isDark) 45 else 35)
                        )
                    } catch (_: Exception) {}
                }

                window.navigationBarColor = surfaceColor
                window.statusBarColor = bgColor
                findViewById<View>(R.id.mainRootLayout)?.setBackgroundColor(bgColor)
                findViewById<View>(R.id.webViewContainer)?.setBackgroundColor(bgColor)
                webView.setBackgroundColor(bgColor)
            } catch (_: Exception) {}
        }
    }

    fun autoPurgeAppCache(forceAll: Boolean = false) {
        activityScope.launch(Dispatchers.IO) {
            try {
                // 1. Purge legacy staging directory if any exists
                val legacyDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "staging")
                if (legacyDir.exists()) {
                    legacyDir.deleteRecursively()
                }

                // 2. Purge stale update APKs from getExternalFilesDir
                try {
                    val extFiles = getExternalFilesDir(null)
                    extFiles?.listFiles()?.forEach { f ->
                        if (f.isFile && (f.name.endsWith(".apk") || f.name.endsWith(".tmp") || f.name.startsWith("temp_") || f.name.startsWith("MediaFetch_Update"))) {
                            f.delete()
                        }
                    }
                } catch (_: Exception) {}

                // 3. Purge staging files that are not being actively downloaded
                val hasActiveDownloads = downloadService?.getAllTasks()?.any {
                    it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
                } ?: false

                val cacheStaging = File(cacheDir, "staging")
                if (cacheStaging.exists()) {
                    if (forceAll || !hasActiveDownloads) {
                        cacheStaging.deleteRecursively()
                        cacheStaging.mkdirs()
                    } else {
                        val now = System.currentTimeMillis()
                        cacheStaging.listFiles()?.forEach { file ->
                            if (file.isFile && (file.name.startsWith("temp_") || (now - file.lastModified() > 60_000L))) {
                                file.delete()
                            }
                        }
                    }
                }

                // 4. Purge general cache files (preserve staging dir itself)
                cacheDir?.listFiles()?.forEach { f ->
                    if (f.name != "staging") {
                        f.deleteRecursively()
                    }
                }

                // 5. Purge external cache
                externalCacheDir?.listFiles()?.forEach { f ->
                    f.deleteRecursively()
                }

                // 6. Purge codeCacheDir if forced
                if (forceAll) {
                    codeCacheDir?.listFiles()?.forEach { f ->
                        f.deleteRecursively()
                    }
                }

                // 7. Purge Chromium WebView disk caches in dataDir/app_webview
                try {
                    val dataDir = applicationInfo.dataDir
                    if (dataDir != null) {
                        val appWebviewDir = File(dataDir, "app_webview")
                        if (appWebviewDir.exists()) {
                            listOf(
                                File(appWebviewDir, "Default/HTTP Cache"),
                                File(appWebviewDir, "Default/GPUCache"),
                                File(appWebviewDir, "Default/Code Cache"),
                                File(appWebviewDir, "Default/Service Worker/CacheStorage"),
                                File(appWebviewDir, "ShaderCache"),
                                File(appWebviewDir, "GrShaderCache")
                            ).forEach { dir ->
                                if (dir.exists()) dir.deleteRecursively()
                            }
                        }
                    }
                } catch (_: Exception) {}

                // 8. Clear WebView disk cache
                withContext(Dispatchers.Main) {
                    try {
                        webView.clearCache(true)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    override fun onStop() {
        super.onStop()
        autoPurgeAppCache(forceAll = false)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_MODERATE || level >= TRIM_MEMORY_UI_HIDDEN) {
            autoPurgeAppCache(forceAll = true)
            try {
                System.gc()
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        autoPurgeAppCache(forceAll = true)
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        try {
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Exception) {}
        activityScope.cancel()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_FOLDER && resultCode == RESULT_OK) {
            val treeUri = data?.data
            if (treeUri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                val path = treeUri.path ?: ""
                val folderName = if (path.contains(":")) path.substringAfterLast(":") else "Selected Folder"
                val resolvedDir = if (folderName.isNotBlank() && folderName != "Selected Folder") {
                    File(Environment.getExternalStorageDirectory(), folderName)
                } else {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MediaFetch")
                }
                val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("custom_download_folder_uri", treeUri.toString())
                    .putString("custom_download_folder_name", folderName)
                    .putString("custom_download_folder_path", resolvedDir.absolutePath)
                    .apply()

                val safeName = JSONObject.quote(folderName)
                webView.evaluateJavascript("window.onCustomFolderSelected && window.onCustomFolderSelected($safeName);", null)
                showAppToast("Save location set to: $folderName")
            }
        }
    }

    companion object {
        const val REQUEST_CODE_PICK_FOLDER = 9182
    }
}
