package com.mediafetch.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import org.json.JSONObject
import android.net.Uri
import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.mediafetch.app.MainActivity
import com.mediafetch.app.R
import com.mediafetch.app.engine.MediaEngine
import com.mediafetch.app.engine.StreamExtractor
import com.mediafetch.app.model.DownloadStatus
import com.mediafetch.app.model.DownloadTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val tasks = ConcurrentHashMap<String, DownloadTask>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val thumbnailBitmaps = ConcurrentHashMap<String, Bitmap>()

    fun fetchThumbnailBitmapAsync(url: String, onLoaded: (Bitmap) -> Unit) {
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) return
        val existing = thumbnailBitmaps[url]
        if (existing != null && !existing.isRecycled) {
            onLoaded(existing)
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val stream = resp.body?.byteStream()
                        if (stream != null) {
                            val bitmap = BitmapFactory.decodeStream(stream)
                            if (bitmap != null) {
                                thumbnailBitmaps[url] = bitmap
                                withContext(Dispatchers.Main) {
                                    onLoaded(bitmap)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    var onTaskUpdated: ((DownloadTask) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (!taskId.isNullOrBlank()) {
            when (action) {
                ACTION_PAUSE -> pauseDownload(taskId)
                ACTION_RESUME -> resumeDownload(taskId)
                ACTION_CANCEL -> cancelDownload(taskId)
            }
        }

        while (staticPendingTasks.isNotEmpty()) {
            val task = staticPendingTasks.poll()
            if (task != null) {
                enqueueDownload(task)
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 1. Ongoing Downloads (Low priority, non-intrusive)
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                "MediaFetch Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time download progress, speed, and status"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(progressChannel)

            // 2. Completed Downloads (High priority heads-up with cover & sound)
            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED_ID,
                "MediaFetch Completed Downloads",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows notifications when media finishes downloading"
                setShowBadge(true)
                enableVibration(true)
            }
            manager?.createNotificationChannel(completedChannel)
        }
    }

    fun getAllTasks(): List<DownloadTask> = tasks.values.toList()

    fun getMaxConcurrentDownloads(): Int {
        val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("max_concurrent_downloads", 2).coerceIn(1, 5)
    }

    private val concurrencyQueue = ConcurrentLinkedQueue<String>()

    fun enqueueDownload(task: DownloadTask) {
        tasks[task.id] = task
        task.status = DownloadStatus.QUEUED
        onTaskUpdated?.invoke(task)
        concurrencyQueue.offer(task.id)
        processQueue()
    }

    @Synchronized
    private fun processQueue() {
        val runningCount = tasks.values.count { it.status == DownloadStatus.DOWNLOADING }
        val maxAllowed = getMaxConcurrentDownloads()
        if (runningCount >= maxAllowed) return

        val nextId = concurrencyQueue.poll() ?: return
        val task = tasks[nextId] ?: return
        if (task.status != DownloadStatus.QUEUED) {
            processQueue()
            return
        }

        task.status = DownloadStatus.DOWNLOADING
        onTaskUpdated?.invoke(task)

        try {
            startForeground(NOTIFICATION_ID, buildProgressNotification(task))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val job = serviceScope.launch {
            try {
                runDownload(task)
            } finally {
                activeJobs.remove(task.id)
                checkAllTasksCompleted()
                processQueue()
            }
        }
        activeJobs[task.id] = job

        if (tasks.values.count { it.status == DownloadStatus.DOWNLOADING } < getMaxConcurrentDownloads() && concurrencyQueue.isNotEmpty()) {
            processQueue()
        }
    }

    fun isTaskActive(id: String): Boolean {
        val task = tasks[id] ?: return false
        val job = activeJobs[id]
        return task.status == DownloadStatus.DOWNLOADING && (job?.isActive == true)
    }

    private fun checkAllTasksCompleted() {
        val hasRunning = tasks.values.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
        if (!hasRunning) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(NOTIFICATION_ID)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun pauseDownload(id: String) {
        concurrencyQueue.remove(id)
        val job = activeJobs[id]
        tasks[id]?.let {
            it.status = DownloadStatus.PAUSED
            it.speedBytesPerSec = 0
            onTaskUpdated?.invoke(it)
            updateNotification(it)
        }
        job?.cancel()
        activeJobs.remove(id)
        processQueue()
    }

    fun resumeDownload(id: String) {
        tasks[id]?.let {
            if (it.status == DownloadStatus.PAUSED) {
                enqueueDownload(it)
            }
        }
    }

    fun cancelDownload(id: String) {
        concurrencyQueue.remove(id)
        val job = activeJobs[id]
        val task = tasks.remove(id)
        task?.let {
            it.status = DownloadStatus.CANCELLED
            it.speedBytesPerSec = 0
            onTaskUpdated?.invoke(it)
            try {
                it.filePath?.let { p ->
                    val f = File(p)
                    if (f.exists()) f.delete()
                }
            } catch (_: Exception) {}
        }
        job?.cancel()
        activeJobs.remove(id)

        val hasActive = tasks.values.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
        if (!hasActive) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
        }
        processQueue()
    }

    fun removeTask(id: String) {
        concurrencyQueue.remove(id)
        val job = activeJobs[id]
        val task = tasks.remove(id)
        job?.cancel()
        activeJobs.remove(id)
        try {
            task?.filePath?.let {
                val f = File(it)
                if (f.exists() && f.parentFile?.name == "staging") {
                    f.delete()
                }
            }
        } catch (e: Exception) {}
        checkAllTasksCompleted()
        processQueue()
    }

    fun clearAllTasks() {
        concurrencyQueue.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        tasks.values.forEach { task ->
            try {
                task.status = DownloadStatus.CANCELLED
                onTaskUpdated?.invoke(task)
                task.filePath?.let {
                    val f = File(it)
                    if (f.exists() && f.parentFile?.name == "staging") {
                        f.delete()
                    }
                }
            } catch (e: Exception) {}
        }
        tasks.clear()
        try {
            val stagingFolder = getStagingFile("test").parentFile
            stagingFolder?.listFiles()?.forEach { if (it.isFile) it.delete() }
        } catch (e: Exception) {}
        checkAllTasksCompleted()
    }

    private fun getStagingFile(fileName: String): File {
        val folder = File(cacheDir, "staging")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return File(folder, fileName)
    }

    private fun buildOutputFileName(task: DownloadTask, effectiveExt: String): String {
        val prefs = getSharedPreferences("mediafetch_prefs", Context.MODE_PRIVATE)
        val template = prefs.getString("custom_filename_template", "{title} [{quality}]")?.trim().let {
            if (it.isNullOrBlank()) "{title}" else it
        }

        val rawTitle = task.title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .replace(Regex("\\s+"), " ")
            .ifBlank { "media_" + System.currentTimeMillis() }

        val rawAuthor = task.author
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "Creator" }

        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val cleanQuality = task.quality.label.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        val cleanRes = task.quality.resolution.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        val platform = MediaEngine.detectPlatform(task.url)

        var result = template
            .replace("{title}", rawTitle, ignoreCase = true)
            .replace("{author}", rawAuthor, ignoreCase = true)
            .replace("{channel}", rawAuthor, ignoreCase = true)
            .replace("{uploader}", rawAuthor, ignoreCase = true)
            .replace("{quality}", cleanQuality, ignoreCase = true)
            .replace("{resolution}", cleanRes, ignoreCase = true)
            .replace("{date}", dateStr, ignoreCase = true)
            .replace("{platform}", platform, ignoreCase = true)
            .replace("{ext}", effectiveExt, ignoreCase = true)

        if (!task.chapterTitle.isNullOrBlank()) {
            val chClean = task.chapterTitle!!.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            result = if (result.contains("{chapter}", ignoreCase = true)) {
                result.replace("{chapter}", chClean, ignoreCase = true)
            } else {
                "$result - [$chClean]"
            }
        }

        if (task.isTrimmed && !task.title.contains("trimmed", ignoreCase = true)) {
            result += "_trimmed_${task.clipStartSeconds}s_${task.clipEndSeconds}s"
        }

        result = result
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)

        if (!result.endsWith(".$effectiveExt", ignoreCase = true)) {
            result += ".$effectiveExt"
        }
        return result
    }

    private fun downloadSubtitleFile(task: DownloadTask, baseFileName: String) {
        val subUrl = task.subtitleUrl ?: return
        if (subUrl.isBlank()) return
        try {
            val lang = task.subtitleLang ?: "en"
            val subName = baseFileName.substringBeforeLast(".") + ".$lang.srt"
            val subFile = getStagingFile(subName)
            val req = Request.Builder().url(subUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        subFile.writeText(body)
                        val publicFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MediaFetch")
                        if (!publicFolder.exists()) publicFolder.mkdirs()
                        val publicFile = File(publicFolder, subFile.name)
                        subFile.copyTo(publicFile, overwrite = true)
                        MediaScannerConnection.scanFile(
                            applicationContext,
                            arrayOf(publicFile.absolutePath, subFile.absolutePath),
                            arrayOf("application/x-subrip", "text/plain"),
                            null
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun runDownload(task: DownloadTask) {
        withContext(Dispatchers.IO) {
            task.status = DownloadStatus.DOWNLOADING
            onTaskUpdated?.invoke(task)

            val effectiveExt = if (task.quality.isAudioOnly && !task.quality.ext.equals("wav", ignoreCase = true)) "m4a" else task.quality.ext
            val fileName = buildOutputFileName(task, effectiveExt)
            var targetFile = getStagingFile(fileName)

            var isDownloadSuccess = false

            try {
                var directUrl = task.quality.directDownloadUrl ?: (if (task.quality.isImage) task.thumbnail else null)
                var audioUrl = task.quality.audioDownloadUrl

                // 1. If direct stream is missing for video/audio, resolve via MediaEngine
                if (directUrl.isNullOrBlank() && !task.quality.isImage) {
                    try {
                        val mediaItem = MediaEngine.resolveMedia(task.url, this@DownloadService)
                        val match = mediaItem.qualities.find { it.id == task.quality.id }
                            ?: mediaItem.qualities.firstOrNull { it.directDownloadUrl?.isNotBlank() == true }
                            ?: mediaItem.qualities.firstOrNull()
                        if (match?.directDownloadUrl != null && match.directDownloadUrl!!.isNotBlank()) {
                            directUrl = match.directDownloadUrl
                            task.quality.directDownloadUrl = directUrl
                        }
                        if (match?.audioDownloadUrl != null && match.audioDownloadUrl!!.isNotBlank()) {
                            audioUrl = match.audioDownloadUrl
                            task.quality.audioDownloadUrl = audioUrl
                        }
                        if (task.title.startsWith("Threads") || task.title.startsWith("Pinterest") || task.title.startsWith("Spotify") || task.title.startsWith("Instagram") || task.title.startsWith("X / Twitter") || task.title.startsWith("Web Media") || task.title.startsWith("Reddit")) {
                            if (mediaItem.title.isNotBlank() && !mediaItem.title.startsWith("Web Media")) {
                                task.title = mediaItem.title
                            }
                        }
                        if (task.thumbnail.contains("unsplash") || task.thumbnail.contains("t51.2885-19") || task.thumbnail.contains("avatar")) {
                            if (mediaItem.thumbnail.isNotBlank() && !mediaItem.thumbnail.contains("t51.2885-19")) {
                                task.thumbnail = mediaItem.thumbnail
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }

                // 2. Failsafe: If direct stream is STILL missing, launch StreamExtractor directly!
                if (directUrl.isNullOrBlank() && !task.quality.isImage) {
                    try {
                        val snifferResult = StreamExtractor.sniffStream(this@DownloadService, task.url, timeoutMs = 10000L)
                        if (snifferResult.streamUrl.isNotBlank()) {
                            directUrl = snifferResult.streamUrl
                            task.quality.directDownloadUrl = directUrl
                            if (snifferResult.title.isNotBlank() && (task.title.startsWith("Instagram") || task.title.startsWith("X / Twitter") || task.title.startsWith("Web Media") || task.title.startsWith("Reddit"))) {
                                task.title = snifferResult.title
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }

                // 3. Stream real binary bytes from network
                val resolvedUrl = directUrl
                if (!resolvedUrl.isNullOrBlank() && (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://"))) {
                    val isVideo = !task.quality.isAudioOnly && !task.quality.isImage
                    val shouldMux = isVideo && !audioUrl.isNullOrBlank() && audioUrl.startsWith("http") && audioUrl != resolvedUrl

                    if (task.isTrimmed && task.clipEndSeconds > task.clipStartSeconds && isVideo) {
                        // ZERO-WASTE FAST STREAM SLICER: Downloads ONLY the ~5-10 MB segment directly from CDN
                        if (shouldMux) {
                            val tempVideoFile = getStagingFile("temp_slice_video_${System.currentTimeMillis()}.${task.quality.ext}")
                            val tempAudioFile = getStagingFile("temp_slice_audio_${System.currentTimeMillis()}.m4a")

                            try {
                                updateTaskStage(task, 0, "Slicing segment from server (Zero Data Waste)...")
                                val vSuccess = downloadTrimmedStreamToFile(
                                    resolvedUrl,
                                    tempVideoFile,
                                    task,
                                    task.clipStartSeconds,
                                    task.clipEndSeconds,
                                    task.durationSeconds,
                                    progressStart = 0,
                                    progressWeight = 0.85
                                )
                                val aSuccess = if (vSuccess && isTaskActive(task.id)) {
                                    downloadTrimmedStreamToFile(
                                        audioUrl!!,
                                        tempAudioFile,
                                        task,
                                        task.clipStartSeconds,
                                        task.clipEndSeconds,
                                        task.durationSeconds,
                                        progressStart = 85,
                                        progressWeight = 0.12
                                    )
                                } else false

                                if (vSuccess && aSuccess && isTaskActive(task.id)) {
                                    updateTaskStage(task, 97, "Muxing precise clip...")
                                    val muxOk = muxVideoAndAudio(tempVideoFile, tempAudioFile, targetFile, task.clipStartSeconds, task.clipEndSeconds)
                                    if (muxOk && targetFile.exists() && targetFile.length() > 1024) {
                                        isDownloadSuccess = true
                                    } else if (tempVideoFile.exists() && tempVideoFile.length() > 1024) {
                                        val trimOk = trimMediaFile(tempVideoFile, targetFile, task.clipStartSeconds, task.clipEndSeconds)
                                        isDownloadSuccess = trimOk && targetFile.exists() && targetFile.length() > 1024
                                    }
                                }
                            } finally {
                                try { if (tempVideoFile.exists()) tempVideoFile.delete() } catch (_: Exception) {}
                                try { if (tempAudioFile.exists()) tempAudioFile.delete() } catch (_: Exception) {}
                            }
                        } else {
                            val tempStreamFile = getStagingFile("temp_slice_stream_${System.currentTimeMillis()}.${task.quality.ext}")
                            try {
                                val sSuccess = downloadTrimmedStreamToFile(
                                    resolvedUrl,
                                    tempStreamFile,
                                    task,
                                    task.clipStartSeconds,
                                    task.clipEndSeconds,
                                    task.durationSeconds,
                                    progressStart = 0,
                                    progressWeight = 0.95
                                )
                                if (sSuccess && tempStreamFile.exists() && tempStreamFile.length() > 1024) {
                                    updateTaskStage(task, 97, "Trimming precise clip...")
                                    val trimOk = trimMediaFile(tempStreamFile, targetFile, task.clipStartSeconds, task.clipEndSeconds)
                                    isDownloadSuccess = trimOk && targetFile.exists() && targetFile.length() > 1024
                                }
                            } finally {
                                try { if (tempStreamFile.exists()) tempStreamFile.delete() } catch (_: Exception) {}
                            }
                        }
                    }

                    if (!isDownloadSuccess && isTaskActive(task.id)) {
                        if (shouldMux) {
                            if (resolvedUrl.lowercase().contains(".m3u8") || audioUrl?.lowercase()?.contains(".m3u8") == true) {
                                // HLS Stream with separate audio or video playlist
                                val isTrimmed = task.isTrimmed && task.clipEndSeconds > task.clipStartSeconds
                                val hlsOut = if (isTrimmed) getStagingFile("temp_hls_mux_${System.currentTimeMillis()}.mp4") else targetFile
                                try {
                                    val hlsOk = downloadHlsStreamToFile(
                                        m3u8Url = resolvedUrl,
                                        audioM3u8Url = audioUrl,
                                        pageUrl = task.url,
                                        targetFile = hlsOut,
                                        task = task,
                                        progressStart = 0,
                                        progressWeight = if (isTrimmed) 0.90 else 1.0
                                    )
                                    if (hlsOk && hlsOut.exists() && hlsOut.length() > 1024) {
                                        if (isTrimmed) {
                                            val trimOk = trimMediaFile(hlsOut, targetFile, task.clipStartSeconds, task.clipEndSeconds)
                                            isDownloadSuccess = trimOk && targetFile.exists() && targetFile.length() > 1024
                                        } else {
                                            isDownloadSuccess = true
                                        }
                                    }
                                } finally {
                                    if (isTrimmed && hlsOut.exists()) try { hlsOut.delete() } catch (_: Exception) {}
                                }
                            } else {
                                // Adaptive stream fallback: download video track + audio track and mux (YouTube / Reddit DASH)
                                val tempVideoFile = getStagingFile("temp_video_${System.currentTimeMillis()}.${task.quality.ext}")
                                val tempAudioFile = getStagingFile("temp_audio_${System.currentTimeMillis()}.m4a")

                            try {
                                val vSuccess = downloadStreamToFile(resolvedUrl, task.url, tempVideoFile, task, progressStart = 0, progressWeight = 0.85)
                                val videoAlreadyHasAudio = vSuccess && tempVideoFile.exists() && fileHasAudioTrack(tempVideoFile)

                                if (videoAlreadyHasAudio) {
                                    // Video stream already contains a complete audio track (e.g. TikTok, Twitter, Instagram progressive) - avoid redundant audio download & muxing!
                                    if (task.isTrimmed && task.clipEndSeconds > task.clipStartSeconds) {
                                        val trimOk = trimMediaFile(tempVideoFile, targetFile, task.clipStartSeconds, task.clipEndSeconds)
                                        isDownloadSuccess = trimOk && targetFile.exists() && targetFile.length() > 1024
                                    } else {
                                        tempVideoFile.copyTo(targetFile, overwrite = true)
                                        isDownloadSuccess = true
                                    }
                                } else {
                                    val aSuccess = if (vSuccess && isTaskActive(task.id)) {
                                        downloadStreamToFile(audioUrl!!, task.url, tempAudioFile, task, progressStart = 85, progressWeight = 0.12)
                                    } else false

                                    if (vSuccess && aSuccess && isTaskActive(task.id)) {
                                        val startSec = if (task.isTrimmed) task.clipStartSeconds else 0L
                                        val endSec = if (task.isTrimmed) task.clipEndSeconds else 0L
                                        val muxOk = muxVideoAndAudio(tempVideoFile, tempAudioFile, targetFile, startSec, endSec)
                                        if (muxOk && targetFile.exists() && targetFile.length() > 1024) {
                                            isDownloadSuccess = true
                                        } else if (tempVideoFile.exists() && tempVideoFile.length() > 1024) {
                                            if (task.isTrimmed && task.clipEndSeconds > task.clipStartSeconds) {
                                                val trimOk = trimMediaFile(tempVideoFile, targetFile, task.clipStartSeconds, task.clipEndSeconds)
                                                isDownloadSuccess = trimOk && targetFile.exists() && targetFile.length() > 1024
                                            } else {
                                                tempVideoFile.copyTo(targetFile, overwrite = true)
                                                isDownloadSuccess = true
                                            }
                                        }
                                    } else if (vSuccess && tempVideoFile.exists() && tempVideoFile.length() > 1024) {
                                        if (task.isTrimmed && task.clipEndSeconds > task.clipStartSeconds) {
                                            val trimOk = trimMediaFile(tempVideoFile, targetFile, task.clipStartSeconds, task.clipEndSeconds)
                                            isDownloadSuccess = trimOk && targetFile.exists() && targetFile.length() > 1024
                                        } else {
                                            tempVideoFile.copyTo(targetFile, overwrite = true)
                                            isDownloadSuccess = true
                                        }
                                    }
                                }
                            } finally {
                                try { if (tempVideoFile.exists()) tempVideoFile.delete() } catch (_: Exception) {}
                                try { if (tempAudioFile.exists()) tempAudioFile.delete() } catch (_: Exception) {}
                            }
                        }
                    } else if (task.quality.isAudioOnly) {
                            // High-Fidelity Audio Extractor with True Bitrate Transcoding & Audio Studio FX
                            val targetExt = if (task.quality.ext.equals("wav", ignoreCase = true)) "wav" else "m4a"
                            val tempAudioIn = getStagingFile("temp_raw_audio_${System.currentTimeMillis()}.${task.quality.ext}")
                            try {
                                val dlOk = if (task.isTrimmed && task.clipEndSeconds > task.clipStartSeconds) {
                                    downloadTrimmedStreamToFile(resolvedUrl, tempAudioIn, task, task.clipStartSeconds, task.clipEndSeconds, task.durationSeconds, progressStart = 0, progressWeight = 0.60)
                                } else {
                                    downloadStreamToFile(resolvedUrl, task.url, tempAudioIn, task, progressStart = 0, progressWeight = 0.60)
                                }
                                if (dlOk && tempAudioIn.exists() && tempAudioIn.length() > 1024) {
                                    val startSec = if (task.isTrimmed) task.clipStartSeconds else 0L
                                    val endSec = if (task.isTrimmed) task.clipEndSeconds else 0L

                                    val finalTarget = if (!targetFile.name.endsWith(".$targetExt", ignoreCase = true)) {
                                        val base = targetFile.name.substringBeforeLast(".")
                                        File(targetFile.parentFile, "$base.$targetExt")
                                    } else targetFile

                                    updateTaskStage(task, 70, "Processing high-fidelity audio...")
                                    val procOk = processAudioStream(tempAudioIn, finalTarget, task.quality, startSec, endSec)
                                    if (procOk && finalTarget.exists() && finalTarget.length() > 1024) {
                                        if (finalTarget != targetFile && targetFile.exists()) targetFile.delete()
                                        targetFile = finalTarget
                                        updateTaskStage(task, 95, "Finalizing audio track...")
                                        isDownloadSuccess = true
                                    } else {
                                        tempAudioIn.copyTo(targetFile, overwrite = true)
                                        isDownloadSuccess = targetFile.exists() && targetFile.length() > 1024
                                    }
                                }
                            } finally {
                                try { if (tempAudioIn.exists()) tempAudioIn.delete() } catch (_: Exception) {}
                            }
                        } else if (resolvedUrl.lowercase().contains(".m3u8")) {
                            // HLS Stream (.m3u8 playlist) - Download segments and remux to standard MP4
                            val isTrimmed = task.isTrimmed && task.clipEndSeconds > task.clipStartSeconds && !task.quality.isImage
                            val hlsOut = if (isTrimmed) {
                                getStagingFile("temp_hls_mux_${System.currentTimeMillis()}.mp4")
                            } else {
                                targetFile
                            }
                            try {
                                val hlsOk = downloadHlsStreamToFile(
                                    m3u8Url = resolvedUrl,
                                    audioM3u8Url = task.quality.audioDownloadUrl,
                                    pageUrl = task.url,
                                    targetFile = hlsOut,
                                    task = task,
                                    progressStart = 0,
                                    progressWeight = if (isTrimmed) 0.90 else 1.0
                                )
                                if (hlsOk && hlsOut.exists() && hlsOut.length() > 1024) {
                                    if (isTrimmed) {
                                        val trimOk = trimMediaFile(hlsOut, targetFile, task.clipStartSeconds, task.clipEndSeconds)
                                        isDownloadSuccess = trimOk && targetFile.exists() && targetFile.length() > 1024
                                    } else {
                                        isDownloadSuccess = true
                                    }
                                }
                            } finally {
                                if (isTrimmed) {
                                    try { if (hlsOut.exists()) hlsOut.delete() } catch (_: Exception) {}
                                }
                            }
                        } else {
                            // Progressive stream (direct video+audio, audio-only, or image)
                            if (task.isTrimmed && task.clipEndSeconds > task.clipStartSeconds && !task.quality.isImage) {
                                val tempStreamFile = getStagingFile("temp_stream_${System.currentTimeMillis()}.${task.quality.ext}")
                                try {
                                    val dlOk = downloadStreamToFile(resolvedUrl, task.url, tempStreamFile, task, progressStart = 0, progressWeight = 0.95)
                                    if (dlOk && tempStreamFile.exists() && tempStreamFile.length() > 1024) {
                                        val trimOk = trimMediaFile(tempStreamFile, targetFile, task.clipStartSeconds, task.clipEndSeconds)
                                        if (trimOk && targetFile.exists() && targetFile.length() > 1024) {
                                            isDownloadSuccess = true
                                        } else {
                                            tempStreamFile.copyTo(targetFile, overwrite = true)
                                            isDownloadSuccess = targetFile.exists() && targetFile.length() > 1024
                                        }
                                    }
                                } finally {
                                    try { if (tempStreamFile.exists()) tempStreamFile.delete() } catch (_: Exception) {}
                                }
                            } else {
                                var dlSuccess = downloadStreamToFile(resolvedUrl, task.url, targetFile, task, progressStart = 0, progressWeight = 1.0)
                                if (!dlSuccess && !task.quality.fallbackUrl.isNullOrBlank() && task.quality.fallbackUrl != resolvedUrl) {
                                    updateTaskStage(task, 0, "Retrying with primary stream...")
                                    dlSuccess = downloadStreamToFile(task.quality.fallbackUrl!!, task.url, targetFile, task, progressStart = 0, progressWeight = 1.0)
                                }
                                if (!dlSuccess && task.quality.isImage && resolvedUrl.contains("maxresdefault.jpg")) {
                                    val fallbackUrl = resolvedUrl.replace("maxresdefault.jpg", "hq720.jpg")
                                    dlSuccess = downloadStreamToFile(fallbackUrl, task.url, targetFile, task, progressStart = 0, progressWeight = 1.0)
                                    if (!dlSuccess) {
                                        val fallbackUrl2 = resolvedUrl.replace("maxresdefault.jpg", "hqdefault.jpg")
                                        dlSuccess = downloadStreamToFile(fallbackUrl2, task.url, targetFile, task, progressStart = 0, progressWeight = 1.0)
                                    }
                                }
                                isDownloadSuccess = dlSuccess
                            }
                        }
                    }
                }

                val isVideo = !task.quality.isAudioOnly && !task.quality.isImage
                if (isActive && targetFile.exists() && isValidMediaFile(targetFile, isVideo)) {
                    isDownloadSuccess = true
                } else if (targetFile.exists() && !isValidMediaFile(targetFile, isVideo)) {
                    isDownloadSuccess = false
                    try { targetFile.delete() } catch (_: Exception) {}
                }
            } catch (e: CancellationException) {
                return@withContext
            } catch (e: Exception) {
                task.status = DownloadStatus.FAILED
                task.errorMessage = e.message ?: "Download failed"
                onTaskUpdated?.invoke(task)
                return@withContext
            }

            val isVideo = !task.quality.isAudioOnly && !task.quality.isImage
            if (isDownloadSuccess && targetFile.exists() && isValidMediaFile(targetFile, isVideo)) {
                task.status = DownloadStatus.COMPLETED
                task.downloadedBytes = targetFile.length()
                task.totalBytes = targetFile.length()
                task.filePath = targetFile.absolutePath
                task.speedBytesPerSec = 0
                task.etaSeconds = 0
                onTaskUpdated?.invoke(task)

                // Export & Index in MediaStore & Gallery
                try {
                    exportToPublicMediaStore(targetFile, task)
                    downloadSubtitleFile(task, targetFile.name)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Show notification
                try {
                    showCompletedNotification(targetFile, task)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                task.status = DownloadStatus.FAILED
                task.errorMessage = if (targetFile.exists() && !isValidMediaFile(targetFile, isVideo)) {
                    "Downloaded stream was invalid or expired. Try again."
                } else {
                    "Stream unavailable. Check network and try again."
                }
                try { if (targetFile.exists()) targetFile.delete() } catch (_: Exception) {}
                onTaskUpdated?.invoke(task)
            }
        }
    }

    private fun fileHasAudioTrack(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            var hasAudio = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    hasAudio = true
                    break
                }
            }
            hasAudio
        } catch (_: Exception) {
            false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun remuxToMp4(inputFile: File, outputFile: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
            val trackCount = extractor.trackCount
            if (trackCount <= 0) return false

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    try {
                        val muxerTrack = muxer.addTrack(format)
                        trackMap[i] = muxerTrack
                    } catch (_: Exception) {}
                }
            }

            if (trackMap.isEmpty()) return false
            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            val lastPtsMap = mutableMapOf<Int, Long>()
            val basePtsMap = mutableMapOf<Int, Long>()
            val offsetMap = mutableMapOf<Int, Long>()

            while (true) {
                val sampleTrackIndex = extractor.sampleTrackIndex
                if (sampleTrackIndex < 0) break

                val muxerTrackIndex = trackMap[sampleTrackIndex]
                if (muxerTrackIndex != null) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0) break

                    val baseTime = basePtsMap.getOrPut(sampleTrackIndex) { sampleTime }
                    var pts = (sampleTime - baseTime).coerceAtLeast(0L)
                    val lastPts = lastPtsMap[sampleTrackIndex] ?: -1L

                    // Handle HLS segment timestamp resets (> 500ms jump backwards)
                    if (lastPts > 0 && pts + 500_000L < lastPts) {
                        val curOffset = offsetMap.getOrPut(sampleTrackIndex) { 0L }
                        offsetMap[sampleTrackIndex] = curOffset + (lastPts - pts) + 33_333L
                    }
                    pts += (offsetMap[sampleTrackIndex] ?: 0L)
                    pts = pts.coerceAtLeast(0L)
                    lastPtsMap[sampleTrackIndex] = maxOf(lastPts, pts)

                    bufferInfo.offset = 0
                    val readBytes = extractor.readSampleData(buffer, 0)
                    if (readBytes < 0) break

                    bufferInfo.size = readBytes
                    bufferInfo.presentationTimeUs = pts
                    bufferInfo.flags = extractor.sampleFlags
                    if (pts == 0L || (extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        bufferInfo.flags = bufferInfo.flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    }
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                }
                extractor.advance()
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private suspend fun downloadHlsStreamToFile(
        m3u8Url: String,
        audioM3u8Url: String? = null,
        pageUrl: String,
        targetFile: File,
        task: DownloadTask,
        progressStart: Int = 0,
        progressWeight: Double = 1.0
    ): Boolean = withContext(Dispatchers.IO) {
        var tempVideoFile: File? = null
        var tempAudioFile: File? = null
        try {
            updateTaskStage(task, progressStart + 2, "Connecting to HLS stream...")

            fun resolveUri(base: String, relative: String): String {
                return try {
                    if (relative.startsWith("http://") || relative.startsWith("https://")) {
                        relative
                    } else {
                        val baseUri = java.net.URI(base)
                        baseUri.resolve(relative).toString()
                    }
                } catch (_: Exception) {
                    if (relative.startsWith("/")) {
                        val proto = if (base.startsWith("https://")) "https://" else "http://"
                        val host = base.substringAfter("://").substringBefore("/")
                        "$proto$host$relative"
                    } else {
                        val lastSlash = base.lastIndexOf('/')
                        if (lastSlash != -1) base.substring(0, lastSlash + 1) + relative else relative
                    }
                }
            }

            suspend fun fetchPlaylist(url: String): String {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Referer", if (pageUrl.isNotBlank()) pageUrl else "https://x.com/")
                    .build()
                return try {
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() ?: "" else ""
                    }
                } catch (_: Exception) { "" }
            }

            var initialContent = fetchPlaylist(m3u8Url)
            if (!initialContent.startsWith("#EXTM3U") && !initialContent.contains("#EXT-X-STREAM-INF")) return@withContext false

            var videoMediaUrl = m3u8Url
            var videoPlaylistContent = initialContent
            var effectiveAudioUrl: String? = audioM3u8Url

            // 1. If Master Playlist, select matching video resolution variant & audio track
            if (initialContent.contains("#EXT-X-STREAM-INF")) {
                val lines = initialContent.lines()
                var selectedVariantUri = ""
                var bestResDiff = Int.MAX_VALUE
                var bestBandwidth = -1L

                val targetRes = when {
                    task.quality.id.contains("4k") || task.quality.id.contains("2160") -> 2160
                    task.quality.id.contains("1440") || task.quality.id.contains("2k") -> 1440
                    task.quality.id.contains("1080") -> 1080
                    task.quality.id.contains("720") -> 720
                    task.quality.id.contains("480") -> 480
                    task.quality.id.contains("360") -> 360
                    else -> 1080
                }

                // Check for audio track in master playlist
                for (l in lines) {
                    val trimmed = l.trim()
                    if (trimmed.startsWith("#EXT-X-MEDIA", ignoreCase = true) && trimmed.contains("TYPE=AUDIO", ignoreCase = true)) {
                        val uriMatch = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(trimmed)
                        if (uriMatch != null && effectiveAudioUrl.isNullOrBlank()) {
                            effectiveAudioUrl = resolveUri(m3u8Url, uriMatch.groupValues[1])
                        }
                    }
                }

                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        val resMatch = Regex("""RESOLUTION\s*=\s*(\d+)\s*x\s*(\d+)""", RegexOption.IGNORE_CASE).find(line)
                        val bwMatch = Regex("""BANDWIDTH\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(line)
                        val w = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val h = resMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
                        val videoRes = if (w > 0 && h > 0) minOf(w, h) else maxOf(w, h)
                        val bw = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                        for (j in (i + 1) until lines.size) {
                            val nextLine = lines[j].trim()
                            if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                                if (videoRes > 0) {
                                    val diff = if (videoRes <= targetRes) {
                                        targetRes - videoRes
                                    } else {
                                        (videoRes - targetRes) * 3
                                    }
                                    if (diff < bestResDiff || (diff == bestResDiff && bw > bestBandwidth)) {
                                        bestResDiff = diff
                                        bestBandwidth = bw
                                        selectedVariantUri = nextLine
                                    }
                                } else if (selectedVariantUri.isEmpty()) {
                                    selectedVariantUri = nextLine
                                }
                                break
                            }
                        }
                    }
                }

                if (selectedVariantUri.isNotBlank()) {
                    videoMediaUrl = resolveUri(m3u8Url, selectedVariantUri)
                    if (videoMediaUrl.contains(".mp4") && !videoMediaUrl.contains(".m3u8")) {
                        return@withContext downloadStreamToFile(videoMediaUrl, pageUrl, targetFile, task, progressStart, progressWeight)
                    }
                    videoPlaylistContent = fetchPlaylist(videoMediaUrl)
                    if (videoPlaylistContent.isBlank()) return@withContext false
                }
            }

            suspend fun downloadSegmentsToFile(
                playlistContent: String,
                mediaBaseUrl: String,
                isAudio: Boolean,
                pStart: Int,
                pWeight: Double
            ): Pair<File?, Boolean> {
                val segmentUrls = mutableListOf<String>()
                var initSegmentUrl: String? = null

                for (line in playlistContent.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-MAP:")) {
                        val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                        if (uriMatch != null) {
                            initSegmentUrl = resolveUri(mediaBaseUrl, uriMatch.groupValues[1])
                        }
                    } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        segmentUrls.add(resolveUri(mediaBaseUrl, trimmed))
                    }
                }

                if (segmentUrls.isEmpty()) return Pair(null, false)

                val isFmp4 = initSegmentUrl != null || playlistContent.contains("#EXT-X-MAP:") ||
                    segmentUrls.any { it.contains(".m4s") || it.contains(".mp4") }

                val fileExt = if (isFmp4) (if (isAudio) "m4a" else "mp4") else "ts"
                val outFile = getStagingFile("temp_hls_${if (isAudio) "a" else "v"}_${System.currentTimeMillis()}.$fileExt")

                FileOutputStream(outFile, false).use { outStream ->
                    if (initSegmentUrl != null) {
                        val initReq = Request.Builder()
                            .url(initSegmentUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                            .header("Referer", if (pageUrl.isNotBlank()) pageUrl else "https://x.com/")
                            .build()
                        httpClient.newCall(initReq).execute().use { resp ->
                            if (resp.isSuccessful) {
                                resp.body?.byteStream()?.copyTo(outStream)
                            }
                        }
                    }

                    val totalSegments = segmentUrls.size
                    for (idx in segmentUrls.indices) {
                        if (!isTaskActive(task.id)) return Pair(null, isFmp4)
                        val segUrl = segmentUrls[idx]
                        val segReq = Request.Builder()
                            .url(segUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                            .header("Referer", if (pageUrl.isNotBlank()) pageUrl else "https://x.com/")
                            .build()

                        httpClient.newCall(segReq).execute().use { resp ->
                            if (resp.isSuccessful) {
                                resp.body?.byteStream()?.copyTo(outStream)
                            }
                        }

                        val segProgress = pStart + ((idx + 1).toDouble() / totalSegments * pWeight).toInt()
                        val pct = ((idx + 1).toDouble() / totalSegments * 100).toInt()
                        val label = if (isAudio) "Downloading audio: $pct%" else "Downloading video: $pct%"
                        updateTaskStage(task, segProgress.coerceIn(0, 95), label)
                    }
                }

                return if (outFile.exists() && outFile.length() > 1024) Pair(outFile, isFmp4) else Pair(null, isFmp4)
            }

            // Determine progress budget
            val hasSeparateAudio = !effectiveAudioUrl.isNullOrBlank()
            val videoWeight = if (hasSeparateAudio) progressWeight * 0.75 else progressWeight * 0.90
            val audioWeight = if (hasSeparateAudio) progressWeight * 0.18 else 0.0

            val (vFile, isVideoFmp4) = downloadSegmentsToFile(
                videoPlaylistContent,
                videoMediaUrl,
                isAudio = false,
                pStart = progressStart,
                pWeight = videoWeight
            )
            tempVideoFile = vFile
            if (tempVideoFile == null || !tempVideoFile.exists() || tempVideoFile.length() < 1024) return@withContext false

            // Download audio track if available
            if (hasSeparateAudio && isTaskActive(task.id)) {
                val nonNullAudioUrl = effectiveAudioUrl!!
                var audioContent = fetchPlaylist(nonNullAudioUrl)
                var audioMediaUrl = nonNullAudioUrl
                if (audioContent.contains("#EXT-X-STREAM-INF")) {
                    val aLines = audioContent.lines()
                    for (al in aLines) {
                        val t = al.trim()
                        if (t.isNotEmpty() && !t.startsWith("#")) {
                            audioMediaUrl = resolveUri(nonNullAudioUrl, t)
                            audioContent = fetchPlaylist(audioMediaUrl)
                            break
                        }
                    }
                }
                if (audioContent.isNotBlank()) {
                    val (aFile, _) = downloadSegmentsToFile(
                        audioContent,
                        audioMediaUrl,
                        isAudio = true,
                        pStart = progressStart + (videoWeight).toInt(),
                        pWeight = audioWeight
                    )
                    tempAudioFile = aFile
                }
            }

            if (!isTaskActive(task.id)) return@withContext false

            updateTaskStage(task, (progressStart + progressWeight * 93).toInt(), "Muxing final MP4...")

            // 1. If both video and audio tracks were downloaded separately, mux with MediaMuxer!
            if (tempAudioFile != null && tempAudioFile.exists() && tempAudioFile.length() > 1024) {
                val muxOk = muxVideoAndAudio(tempVideoFile, tempAudioFile, targetFile, 0L, 0L)
                if (muxOk && targetFile.exists() && isValidMediaFile(targetFile, true)) {
                    return@withContext true
                }
            }

            // 2. If single video file (or TS with multiplexed audio), remux to progressive MP4!
            val remuxOk = remuxToMp4(tempVideoFile, targetFile)
            if (remuxOk && targetFile.exists() && isValidMediaFile(targetFile, true)) {
                return@withContext true
            }

            // 3. Fallback: if tempVideoFile is already a valid progressive MP4
            if (isValidMediaFile(tempVideoFile, true)) {
                tempVideoFile.copyTo(targetFile, overwrite = true)
                return@withContext true
            }

            return@withContext false
        } catch (_: Exception) {
            return@withContext false
        } finally {
            try { tempVideoFile?.let { if (it.exists()) it.delete() } } catch (_: Exception) {}
            try { tempAudioFile?.let { if (it.exists()) it.delete() } } catch (_: Exception) {}
        }
    }

    private fun isValidMediaFile(file: File, isVideo: Boolean): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        return try {
            val header = ByteArray(16)
            FileInputStream(file).use { it.read(header) }
            val headerStr = String(header, 0, 16, Charsets.US_ASCII).lowercase()

            // 1. If HTML error page or HLS playlist text file, reject!
            if (headerStr.startsWith("<!doc") || headerStr.startsWith("<html") || headerStr.startsWith("<?xml") ||
                headerStr.startsWith("#extm3u") || headerStr.startsWith("#ext-x") || headerStr.startsWith("#ext") ||
                headerStr.startsWith("{") || headerStr.startsWith("[") || headerStr.startsWith("error")
            ) {
                return false
            }

            if (isVideo) {
                // 2. If JPEG image (FF D8 FF), reject!
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                    return false
                }
                // 3. If PNG image (89 50 4E 47), reject!
                if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte()) {
                    return false
                }
                // 4. If GIF image (47 49 46), reject!
                if (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte()) {
                    return false
                }
                // 5. If WebP image (RIFF....WEBP), reject!
                if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()) {
                    val typeStr = String(header, 8, 4, Charsets.US_ASCII)
                    if (typeStr == "WEBP") return false
                }

                // 6. MP4/MOV check: contains 'ftyp' or 'moov' or 'mdat' in first 16 bytes
                val isMp4 = (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() && header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) ||
                            (header[4] == 'm'.code.toByte() && header[5] == 'o'.code.toByte() && header[6] == 'o'.code.toByte() && header[7] == 'v'.code.toByte()) ||
                            (header[4] == 'm'.code.toByte() && header[5] == 'd'.code.toByte() && header[6] == 'a'.code.toByte() && header[7] == 't'.code.toByte())

                // 7. Matroska / WebM check: 1A 45 DF A3
                val isMatroska = (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte())

                // 8. MPEG-TS sync byte: 0x47
                val isTs = header[0] == 0x47.toByte()

                if (file.name.endsWith(".mp4", ignoreCase = true)) {
                    isMp4
                } else {
                    isMp4 || isMatroska || isTs || file.length() > 500 * 1024
                }
            } else {
                true
            }
        } catch (_: Exception) {
            file.exists() && file.length() > 1024
        }
    }

    private suspend fun downloadStreamToFile(
        streamUrl: String,
        referer: String,
        destFile: File,
        task: DownloadTask,
        progressStart: Int,
        progressWeight: Double
    ): Boolean {
        return try {
            val isInstagram = task.url.contains("instagram.com") || streamUrl.contains("cdninstagram.com") || streamUrl.contains("fbcdn.net")
            val isFacebook = task.url.contains("facebook.com") || task.url.contains("fb.watch")
            val effectiveReferer = when {
                streamUrl.contains("googlevideo.com") -> "https://www.youtube.com/"
                isInstagram -> "https://www.instagram.com/"
                isFacebook -> "https://www.facebook.com/"
                task.url.contains("threads.net") || task.url.contains("threads.com") -> "https://www.threads.net/"
                task.url.contains("pinterest.com") || task.url.contains("pin.it") -> "https://www.pinterest.com/"
                task.url.contains("spotify.com") -> "https://open.spotify.com/"
                task.url.contains("tiktok.com") -> "https://www.tiktok.com/"
                task.url.contains("twitter.com") || task.url.contains("x.com") -> "https://x.com/"
                else -> referer
            }

            // 1. Query total length via HEAD request, with Range probe fallback
            var totalLength = 0L
            try {
                val headReq = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", effectiveReferer)
                    .head()
                    .build()
                val headResp = httpClient.newCall(headReq).execute()
                val clen = headResp.header("Content-Length")?.toLongOrNull()
                if (clen != null && clen > 0) totalLength = clen
                headResp.close()
            } catch (_: Exception) {}

            // If HEAD didn't yield total length (common on YouTube/GoogleVideo), probe with Range: bytes=0-0
            if (totalLength <= 0L) {
                try {
                    val probeReq = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Range", "bytes=0-0")
                        .header("Referer", effectiveReferer)
                        .build()
                    val probeResp = httpClient.newCall(probeReq).execute()
                    if (probeResp.isSuccessful) {
                        val crange = probeResp.header("Content-Range")
                        if (!crange.isNullOrBlank() && crange.contains("/")) {
                            val totalStr = crange.substringAfter("/").trim()
                            totalLength = totalStr.toLongOrNull() ?: 0L
                        }
                    }
                    probeResp.close()
                } catch (_: Exception) {}
            }

            val isYouTube = streamUrl.contains("googlevideo.com")

            // 2. Direct high-speed streaming for non-YouTube platforms (Instagram, TikTok, Twitter/X, Reddit, Facebook, Pinterest)
            // Bypasses Range-chunking connection overhead, eliminates CDN chunking corruption/stutter, and delivers pure, uncorrupted MP4 files at maximum network speed.
            if (!isYouTube) {
                try {
                    val directReq = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Accept", "*/*")
                        .header("Referer", effectiveReferer)
                        .build()

                    val directResp = httpClient.newCall(directReq).execute()
                    if (directResp.isSuccessful && directResp.body != null) {
                        val body = directResp.body!!
                        val len = body.contentLength()
                        val streamTotal = if (len > 0L) len else (if (totalLength > 0L) totalLength else task.totalBytes)
                        val buffer = ByteArray(64 * 1024)
                        var streamDownloaded = 0L
                        var lastUpdate = System.currentTimeMillis()
                        var bytesSinceLastUpdate = 0L

                        FileOutputStream(destFile).use { output ->
                            body.byteStream().use { input ->
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1 && isTaskActive(task.id)) {
                                    output.write(buffer, 0, read)
                                    streamDownloaded += read
                                    bytesSinceLastUpdate += read

                                    val now = System.currentTimeMillis()
                                    val delta = now - lastUpdate
                                    if (delta >= 200) {
                                        task.speedBytesPerSec = ((bytesSinceLastUpdate.toDouble() / delta.toDouble()) * 1000.0).toLong()
                                        val ratio = if (streamTotal > 0L) (streamDownloaded.toDouble() / streamTotal.toDouble()).coerceIn(0.0, 1.0) else 0.5
                                        val overallPct = (progressStart + (ratio * progressWeight * 100.0)).toInt().coerceIn(0, 99)
                                        task.downloadedBytes = (task.totalBytes * (overallPct / 100.0)).toLong()

                                        val rem = (streamTotal - streamDownloaded).coerceAtLeast(0)
                                        task.etaSeconds = if (task.speedBytesPerSec > 0L) (rem / task.speedBytesPerSec) else 0L

                                        bytesSinceLastUpdate = 0L
                                        lastUpdate = now
                                        updateNotification(task)
                                        onTaskUpdated?.invoke(task)
                                    }
                                }
                            }
                        }

                        if (destFile.exists() && destFile.length() > 1024 && isTaskActive(task.id)) {
                            return true
                        }
                    }
                } catch (_: Exception) {
                    try { if (destFile.exists()) destFile.delete() } catch (_: Exception) {}
                }
            }

            // 3. YouTube & CDN Range Chunking (prevents GoogleVideo TCP throttling)
            val chunkSize = 512L * 1024L // 512 KB chunks
            if (isYouTube && totalLength > 128L * 1024L) {
                var downloaded = 0L
                var lastUpdate = System.currentTimeMillis()
                var bytesSinceLastUpdate = 0L
                val buffer = ByteArray(64 * 1024)
                var rangeSuccess = true

                FileOutputStream(destFile).use { fileOut ->
                    while (downloaded < totalLength && isTaskActive(task.id)) {
                        val endByte = (downloaded + chunkSize - 1L).coerceAtMost(totalLength - 1L)
                        val rangeReq = Request.Builder()
                            .url(streamUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .header("Range", "bytes=$downloaded-$endByte")
                            .header("Referer", effectiveReferer)
                            .build()

                        val rangeResp = httpClient.newCall(rangeReq).execute()
                        if (!rangeResp.isSuccessful || rangeResp.body == null) {
                            rangeSuccess = false
                            break
                        }

                        val rangeBody = rangeResp.body!!
                        rangeBody.byteStream().use { streamIn ->
                            var read: Int
                            while (streamIn.read(buffer).also { read = it } != -1 && isTaskActive(task.id)) {
                                fileOut.write(buffer, 0, read)
                                downloaded += read
                                bytesSinceLastUpdate += read

                                val now = System.currentTimeMillis()
                                val delta = now - lastUpdate
                                if (delta >= 250) {
                                    task.speedBytesPerSec = ((bytesSinceLastUpdate.toDouble() / delta.toDouble()) * 1000.0).toLong()
                                    val ratio = (downloaded.toDouble() / totalLength.toDouble()).coerceIn(0.0, 1.0)
                                    val overallPct = (progressStart + (ratio * progressWeight * 100.0)).toInt().coerceIn(0, 99)
                                    task.downloadedBytes = (task.totalBytes * (overallPct / 100.0)).toLong()

                                    val rem = (totalLength - downloaded).coerceAtLeast(0)
                                    task.etaSeconds = if (task.speedBytesPerSec > 0L) (rem / task.speedBytesPerSec) else 0L

                                    bytesSinceLastUpdate = 0L
                                    lastUpdate = now
                                    updateNotification(task)
                                    onTaskUpdated?.invoke(task)
                                }
                            }
                        }
                    }
                }

                if (rangeSuccess && destFile.exists() && destFile.length() >= (totalLength * 0.95).toLong()) {
                    return true
                }
                try { if (destFile.exists()) destFile.delete() } catch (_: Exception) {}
            }

            // 4. Fallback: single stream with Range header
            val req = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "*/*")
                .header("Range", "bytes=0-")
                .header("Referer", effectiveReferer)
                .build()

            val response = httpClient.newCall(req).execute()
            if (!response.isSuccessful || response.body == null) return false

            val body = response.body!!
            val length = body.contentLength()
            val streamTotal = if (length > 0) length else (if (totalLength > 0) totalLength else 1024 * 1024 * 5)
            val buffer = ByteArray(64 * 1024)
            var streamDownloaded = 0L
            var lastUpdate = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1 && isTaskActive(task.id)) {
                        output.write(buffer, 0, read)
                        streamDownloaded += read
                        bytesSinceLastUpdate += read

                        val now = System.currentTimeMillis()
                        val delta = now - lastUpdate
                        if (delta >= 250) {
                            task.speedBytesPerSec = ((bytesSinceLastUpdate.toDouble() / delta.toDouble()) * 1000.0).toLong()
                            val ratio = (streamDownloaded.toDouble() / streamTotal.toDouble()).coerceIn(0.0, 1.0)
                            val overallPct = (progressStart + (ratio * progressWeight * 100.0)).toInt().coerceIn(0, 99)
                            task.downloadedBytes = (task.totalBytes * (overallPct / 100.0)).toLong()

                            val rem = (streamTotal - streamDownloaded).coerceAtLeast(0)
                            task.etaSeconds = if (task.speedBytesPerSec > 0L) (rem / task.speedBytesPerSec) else 0L

                            bytesSinceLastUpdate = 0L
                            lastUpdate = now
                            updateNotification(task)
                            onTaskUpdated?.invoke(task)
                        }
                    }
                }
            }
            destFile.exists() && destFile.length() > 1024
        } catch (e: Exception) {
            destFile.exists() && destFile.length() > 1024
        }
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || source.size < target.size) return -1
        val limit = source.size - target.size
        for (i in 0..limit) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun readUint32(buf: ByteArray, offset: Int): Long {
        if (offset + 4 > buf.size) return 0L
        return ((buf[offset].toLong() and 0xFF) shl 24) or
               ((buf[offset + 1].toLong() and 0xFF) shl 16) or
               ((buf[offset + 2].toLong() and 0xFF) shl 8) or
               (buf[offset + 3].toLong() and 0xFF)
    }

    private fun readUint16(buf: ByteArray, offset: Int): Int {
        if (offset + 2 > buf.size) return 0
        return ((buf[offset].toInt() and 0xFF) shl 8) or
               (buf[offset + 1].toInt() and 0xFF)
    }

    private fun readUint64(buf: ByteArray, offset: Int): Long {
        if (offset + 8 > buf.size) return 0L
        val high = readUint32(buf, offset)
        val low = readUint32(buf, offset + 4)
        return (high shl 32) or (low and 0xFFFFFFFFL)
    }

    private fun downloadTrimmedStreamToFile(
        streamUrl: String,
        destFile: File,
        task: DownloadTask,
        startSec: Long,
        endSec: Long,
        totalDurationSec: Long,
        progressStart: Int,
        progressWeight: Double
    ): Boolean {
        return try {
            val referer = if (streamUrl.contains("googlevideo.com")) "https://www.youtube.com/" else task.url

            // 1. Probe first 128 KB to find sidx
            val probeReq = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Range", "bytes=0-131071")
                .header("Referer", referer)
                .build()

            val probeResp = httpClient.newCall(probeReq).execute()
            if (!probeResp.isSuccessful || probeResp.body == null) return false
            val headerBytes = probeResp.body!!.bytes()

            val sidxPattern = byteArrayOf('s'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(), 'x'.code.toByte())
            val sidxPos = indexOfBytes(headerBytes, sidxPattern)
            if (sidxPos < 4) return false

            val boxStart = sidxPos - 4
            val boxSize = readUint32(headerBytes, boxStart).toInt()

            val fullHeader: ByteArray = if (boxStart + boxSize > headerBytes.size && boxSize in 1..(1024 * 1024)) {
                val fullReq = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Range", "bytes=0-${boxStart + boxSize - 1}")
                    .header("Referer", referer)
                    .build()
                val fullResp = httpClient.newCall(fullReq).execute()
                if (fullResp.isSuccessful && fullResp.body != null) fullResp.body!!.bytes() else headerBytes
            } else {
                headerBytes
            }

            val version = fullHeader[sidxPos + 4].toInt() and 0xFF
            val timescale = readUint32(fullHeader, sidxPos + 12)
            if (timescale <= 0L) return false

            val earliestPt: Long
            val firstOffset: Long
            var offset: Int
            if (version == 0) {
                earliestPt = readUint32(fullHeader, sidxPos + 16)
                firstOffset = readUint32(fullHeader, sidxPos + 20)
                offset = sidxPos + 24
            } else {
                earliestPt = readUint64(fullHeader, sidxPos + 16)
                firstOffset = readUint64(fullHeader, sidxPos + 24)
                offset = sidxPos + 32
            }
            offset += 2 // skip reserved
            val refCount = readUint16(fullHeader, offset)
            offset += 2
            if (refCount <= 0) return false

            var currTime = earliestPt.toDouble() / timescale.toDouble()
            var currByte = (boxStart.toLong() + boxSize.toLong() + firstOffset)
            var sliceStartByte = -1L
            var sliceEndByte = -1L

            // Ensure keyframe buffer of 4 seconds before startSec and 2 seconds after endSec
            val searchStart = (startSec.toDouble() - 4.0).coerceAtLeast(0.0)
            val searchEnd = endSec.toDouble() + 2.0

            for (i in 0 until refCount) {
                if (offset + 12 > fullHeader.size) break
                val b0 = readUint32(fullHeader, offset)
                val b1 = readUint32(fullHeader, offset + 4)
                offset += 12

                val segSize = b0 and 0x7FFFFFFFL
                val segDur = b1.toDouble() / timescale.toDouble()
                val segStart = currTime
                val segEnd = currTime + segDur

                if (segEnd >= searchStart && segStart <= searchEnd) {
                    if (sliceStartByte == -1L) {
                        sliceStartByte = currByte
                    }
                    sliceEndByte = currByte + segSize - 1L
                }

                currTime += segDur
                currByte += segSize
            }

            if (sliceStartByte == -1L || sliceEndByte < sliceStartByte) return false

            val totalSliceBytes = sliceEndByte - sliceStartByte + 1L

            // Set task.totalBytes immediately so UI shows actual ~8.6 MB, not full 366 MB!
            if (progressStart == 0 && totalSliceBytes > 0L) {
                task.totalBytes = (totalSliceBytes / progressWeight).toLong().coerceAtLeast(totalSliceBytes)
                updateNotification(task)
                onTaskUpdated?.invoke(task)
            }

            // Write initRange (bytes from 0 up to boxStart - 1)
            val initEnd = (boxStart - 1).coerceAtLeast(0)
            FileOutputStream(destFile).use { fos ->
                fos.write(fullHeader, 0, initEnd + 1)

                val chunkSize = 512L * 1024L // 512 KB chunks to prevent throttling
                var downloaded = 0L
                var lastUpdate = System.currentTimeMillis()
                var bytesSinceLastUpdate = 0L
                val buffer = ByteArray(64 * 1024)

                while (downloaded < totalSliceBytes && isTaskActive(task.id)) {
                    val reqStart = sliceStartByte + downloaded
                    val reqEnd = (reqStart + chunkSize - 1L).coerceAtMost(sliceEndByte)

                    val rangeReq = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Range", "bytes=$reqStart-$reqEnd")
                        .header("Referer", referer)
                        .build()

                    val rangeResp = httpClient.newCall(rangeReq).execute()
                    if (!rangeResp.isSuccessful || rangeResp.body == null) {
                        break
                    }

                    rangeResp.body!!.byteStream().use { streamIn ->
                        var read: Int
                        while (streamIn.read(buffer).also { read = it } != -1 && isTaskActive(task.id)) {
                            fos.write(buffer, 0, read)
                            downloaded += read
                            bytesSinceLastUpdate += read

                            val now = System.currentTimeMillis()
                            val delta = now - lastUpdate
                            if (delta >= 200) {
                                task.speedBytesPerSec = ((bytesSinceLastUpdate.toDouble() / delta.toDouble()) * 1000.0).toLong()
                                val ratio = (downloaded.toDouble() / totalSliceBytes.toDouble()).coerceIn(0.0, 1.0)
                                val overallPct = (progressStart + (ratio * progressWeight * 100.0)).toInt().coerceIn(0, 99)
                                task.downloadedBytes = (task.totalBytes * (overallPct / 100.0)).toLong()

                                val rem = (totalSliceBytes - downloaded).coerceAtLeast(0)
                                task.etaSeconds = if (task.speedBytesPerSec > 0L) (rem / task.speedBytesPerSec) else 0L

                                bytesSinceLastUpdate = 0L
                                lastUpdate = now
                                updateNotification(task)
                                onTaskUpdated?.invoke(task)
                            }
                        }
                    }
                }
            }

            destFile.exists() && destFile.length() > 1024 && isTaskActive(task.id)
        } catch (e: Exception) {
            false
        }
    }

    private fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        startSec: Long = 0L,
        endSec: Long = 0L
    ): Boolean {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            var isVp9 = false
            var videoFormat: MediaFormat? = null
            var videoTrackIdx = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoExtractor.selectTrack(i)
                    videoFormat = format
                    videoTrackIdx = i
                    if (mime.contains("vp9") || mime.contains("vp8") || mime.contains("webm")) {
                        isVp9 = true
                    }
                    break
                }
            }

            if (videoFormat == null || videoTrackIdx == -1) return false

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false

            // Attempt 1: Standard MP4 muxer (Universal compatibility across Android 8+, supports VP9 and AAC)
            try {
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                videoTrackIndex = muxer.addTrack(videoFormat)
                for (i in 0 until audioExtractor.trackCount) {
                    val format = audioExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioExtractor.selectTrack(i)
                        try {
                            audioTrackIndex = muxer.addTrack(format)
                        } catch (_: Exception) {}
                        break
                    }
                }
                muxer.start()
                muxerStarted = true
            } catch (_: Exception) {
                try { muxer?.release() } catch (_: Exception) {}
                muxer = null
                videoTrackIndex = -1
                audioTrackIndex = -1
            }

            // Attempt 2: Fallback (if MP4 failed, try WebM if VP9 or MP4 without audio)
            if (!muxerStarted) {
                try {
                    val fallbackFormat = if (isVp9) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    muxer = MediaMuxer(outputFile.absolutePath, fallbackFormat)
                    videoTrackIndex = muxer.addTrack(videoFormat)
                    for (i in 0 until audioExtractor.trackCount) {
                        val format = audioExtractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            audioExtractor.selectTrack(i)
                            try {
                                audioTrackIndex = muxer.addTrack(format)
                            } catch (_: Exception) {}
                            break
                        }
                    }
                    muxer.start()
                    muxerStarted = true
                } catch (_: Exception) {
                    return false
                }
            }

            val activeMuxer = muxer ?: return false

            val maxBufferSize = 1024 * 1024
            val videoBuffer = ByteBuffer.allocate(maxBufferSize)
            val audioBuffer = ByteBuffer.allocate(maxBufferSize)
            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()

            val isTrimming = (endSec > startSec) && (startSec >= 0L)
            val startUs = if (isTrimming) startSec * 1_000_000L else 0L
            val clipDurationUs = if (isTrimming) (endSec - startSec) * 1_000_000L else Long.MAX_VALUE

            if (isTrimming && startUs > 0) {
                videoExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val initialVideoTime = videoExtractor.sampleTime
            val videoBaseUs = if (isTrimming) {
                if (initialVideoTime >= 0L) initialVideoTime else startUs
            } else 0L

            if (audioTrackIndex != -1 && isTrimming && videoBaseUs > 0) {
                audioExtractor.seekTo(videoBaseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            // In non-trimming mode, align origins so both start at time 0
            val vStartUs = if (isTrimming) videoBaseUs else (if (initialVideoTime >= 0L) initialVideoTime else 0L)
            val initialAudioTime = if (audioTrackIndex != -1) audioExtractor.sampleTime else -1L
            val aStartUs = if (isTrimming) videoBaseUs else (if (initialAudioTime >= 0L) initialAudioTime else 0L)

            var videoDone = false
            var audioDone = (audioTrackIndex == -1)
            var lastVideoPts = -1L
            var lastAudioPts = -1L
            var videoMaxPts = 0L

            val audioDurationLimitUs = if (isTrimming) clipDurationUs + 200_000L else Long.MAX_VALUE

            // CHRONOLOGICALLY INTERLEAVED MUXING LOOP:
            // Interleaves video and audio samples chronologically to create an optimized, 
            // lag-free MP4 that streams smoothly on any mobile hardware without seeking overhead.
            while (!videoDone || !audioDone) {
                val vTime = if (!videoDone) videoExtractor.sampleTime else -1L
                val aTime = if (!audioDone) audioExtractor.sampleTime else -1L

                if (vTime < 0L && !videoDone) videoDone = true
                if (aTime < 0L && !audioDone) audioDone = true

                if (videoDone && audioDone) break

                val writeVideo = when {
                    videoDone -> false
                    audioDone -> true
                    vTime >= 0L && aTime >= 0L -> vTime <= aTime
                    vTime >= 0L -> true
                    else -> false
                }

                if (writeVideo) {
                    videoInfo.size = videoExtractor.readSampleData(videoBuffer, 0)
                    if (videoInfo.size < 0) {
                        videoDone = true
                    } else {
                        val sampleTime = videoExtractor.sampleTime
                        var pts = (sampleTime - vStartUs).coerceAtLeast(0L)
                        if (isTrimming && pts >= clipDurationUs) {
                            videoDone = true
                        } else {
                            if (pts <= lastVideoPts) pts = lastVideoPts + 1L
                            lastVideoPts = pts
                            videoMaxPts = pts

                            videoInfo.presentationTimeUs = pts
                            videoInfo.flags = videoExtractor.sampleFlags
                            if (pts == 0L || (videoExtractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                videoInfo.flags = videoInfo.flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                            }
                            activeMuxer.writeSampleData(videoTrackIndex, videoBuffer, videoInfo)
                        }
                    }
                    if (!videoDone) videoExtractor.advance()
                } else {
                    val sampleTime = audioExtractor.sampleTime
                    // Skip pre-roll audio before video start when trimming
                    if (isTrimming && vStartUs > 0 && sampleTime < vStartUs - 25_000L) {
                        audioExtractor.advance()
                        continue
                    }

                    audioInfo.size = audioExtractor.readSampleData(audioBuffer, 0)
                    if (audioInfo.size < 0) {
                        audioDone = true
                    } else {
                        var pts = (sampleTime - aStartUs).coerceAtLeast(0L)
                        val effectiveAudioLimit = if (isTrimming && videoMaxPts > 0L) (videoMaxPts + 200_000L).coerceAtMost(audioDurationLimitUs) else audioDurationLimitUs
                        if (pts >= effectiveAudioLimit) {
                            audioDone = true
                        } else {
                            if (pts <= lastAudioPts) pts = lastAudioPts + 1L
                            lastAudioPts = pts

                            audioInfo.presentationTimeUs = pts
                            audioInfo.flags = audioExtractor.sampleFlags
                            activeMuxer.writeSampleData(audioTrackIndex, audioBuffer, audioInfo)
                        }
                    }
                    if (!audioDone) audioExtractor.advance()
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { muxer?.stop() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
            try { videoExtractor?.release() } catch (e: Exception) {}
            try { audioExtractor?.release() } catch (e: Exception) {}
        }
    }

    private fun trimMediaFile(
        inputFile: File,
        outputFile: File,
        startSec: Long,
        endSec: Long
    ): Boolean {
        if (endSec <= startSec) return false
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
            val numTracks = extractor.trackCount
            if (numTracks <= 0) return false

            var isVp9 = false
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && (mime.contains("vp9") || mime.contains("vp8") || mime.contains("webm"))) {
                    isVp9 = true
                    break
                }
            }

            val muxerFormat = if (isVp9) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)
            val trackMap = mutableMapOf<Int, Int>()

            var videoTrackIdx = -1
            var audioTrackIdx = -1

            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIdx == -1) {
                    videoTrackIdx = i
                    trackMap[i] = muxer.addTrack(format)
                } else if (mime.startsWith("audio/") && audioTrackIdx == -1) {
                    audioTrackIdx = i
                    trackMap[i] = muxer.addTrack(format)
                }
            }

            if (trackMap.isEmpty()) return false
            muxer.start()

            // Select both tracks for natural chronological interleaving
            if (videoTrackIdx != -1) extractor.selectTrack(videoTrackIdx)
            if (audioTrackIdx != -1) extractor.selectTrack(audioTrackIdx)

            val startUs = startSec * 1_000_000L
            val clipDurationUs = (endSec - startSec) * 1_000_000L
            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoBaseUs = -1L
            var audioBaseUs = -1L
            var lastVideoPts = -1L
            var lastAudioPts = -1L
            var videoMaxPts = 0L
            var videoFinished = (videoTrackIdx == -1)
            var audioFinished = (audioTrackIdx == -1)

            // Single unified loop: MediaExtractor yields samples across both tracks chronologically,
            // producing a 100% interleaved, buttery smooth MP4 output.
            while (!videoFinished || !audioFinished) {
                val trackIdx = extractor.sampleTrackIndex
                if (trackIdx < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break

                if (trackIdx == videoTrackIdx) {
                    if (videoFinished) {
                        extractor.advance()
                        continue
                    }
                    if (videoBaseUs < 0L) {
                        videoBaseUs = sampleTime
                    }
                    var pts = (sampleTime - videoBaseUs).coerceAtLeast(0L)
                    if (pts >= clipDurationUs) {
                        videoFinished = true
                        extractor.unselectTrack(videoTrackIdx)
                        continue
                    }

                    if (pts <= lastVideoPts) pts = lastVideoPts + 1L
                    lastVideoPts = pts
                    videoMaxPts = pts

                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        videoFinished = true
                        extractor.unselectTrack(videoTrackIdx)
                        continue
                    }
                    bufferInfo.presentationTimeUs = pts
                    bufferInfo.flags = extractor.sampleFlags
                    if (pts == 0L || (extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        bufferInfo.flags = bufferInfo.flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    }
                    muxer.writeSampleData(trackMap[videoTrackIdx]!!, buffer, bufferInfo)
                    extractor.advance()
                } else if (trackIdx == audioTrackIdx) {
                    if (audioFinished) {
                        extractor.advance()
                        continue
                    }
                    val syncBase = if (videoBaseUs >= 0L) videoBaseUs else startUs
                    if (syncBase > 0 && sampleTime < syncBase - 25_000L) {
                        extractor.advance()
                        continue
                    }
                    if (audioBaseUs < 0L) {
                        audioBaseUs = if (syncBase >= 0L) syncBase else sampleTime
                    }
                    var pts = (sampleTime - audioBaseUs).coerceAtLeast(0L)
                    val audioLimit = if (videoMaxPts > 0L) videoMaxPts + 200_000L else clipDurationUs
                    if (pts >= audioLimit) {
                        audioFinished = true
                        extractor.unselectTrack(audioTrackIdx)
                        continue
                    }

                    if (pts <= lastAudioPts) pts = lastAudioPts + 1L
                    lastAudioPts = pts

                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        audioFinished = true
                        extractor.unselectTrack(audioTrackIdx)
                        continue
                    }
                    bufferInfo.presentationTimeUs = pts
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(trackMap[audioTrackIdx]!!, buffer, bufferInfo)
                    extractor.advance()
                } else {
                    extractor.advance()
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { muxer?.stop() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
            try { extractor?.release() } catch (e: Exception) {}
        }
    }

    private fun updateTaskStage(task: DownloadTask, pct: Int, stage: String) {
        val bounded = pct.coerceIn(0, 99)
        task.downloadedBytes = (task.totalBytes * (bounded / 100.0)).toLong()
        task.speedBytesPerSec = 0
        task.etaSeconds = 0
        updateNotification(task)
        onTaskUpdated?.invoke(task)
    }

    private fun exportToPublicMediaStore(sourceFile: File, task: DownloadTask) {
        try {
            val isAudio = task.quality.isAudioOnly
            val isImage = task.quality.isImage
            val ext = sourceFile.extension.lowercase()
            val mimeType = when {
                isImage -> when (ext) {
                    "gif" -> "image/gif"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                }
                isAudio -> when (ext) {
                    "wav" -> "audio/wav"
                    "mp3" -> "audio/mpeg"
                    else -> "audio/mp4"
                }
                else -> "video/mp4"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collectionUri = when {
                    isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    isAudio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val relativeDir = when {
                    isImage -> Environment.DIRECTORY_PICTURES + "/MediaFetch"
                    isAudio -> Environment.DIRECTORY_MUSIC + "/MediaFetch"
                    else -> Environment.DIRECTORY_MOVIES + "/MediaFetch"
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val itemUri = contentResolver.insert(collectionUri, values)
                if (itemUri != null) {
                    contentResolver.openOutputStream(itemUri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(itemUri, values, null, null)
                }
            } else {
                val publicFolder = when {
                    isImage -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MediaFetch")
                    isAudio -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "MediaFetch")
                    else -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MediaFetch")
                }
                if (!publicFolder.exists()) publicFolder.mkdirs()
                val publicFile = File(publicFolder, sourceFile.name)
                sourceFile.copyTo(publicFile, overwrite = true)
                MediaScannerConnection.scanFile(applicationContext, arrayOf(publicFile.absolutePath), arrayOf(mimeType), null)
            }

            MediaScannerConnection.scanFile(
                applicationContext,
                arrayOf(sourceFile.absolutePath),
                arrayOf(mimeType)
            ) { _, _ -> }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processAudioStream(
        sourceFile: File,
        destFile: File,
        quality: com.mediafetch.app.model.QualityOption,
        startSec: Long = 0L,
        endSec: Long = 0L
    ): Boolean {
        val isWav = quality.ext.equals("wav", ignoreCase = true)
        val targetBitrate = if (quality.bitrateKbps > 0) quality.bitrateKbps.toInt() * 1000 else 256_000

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        val tempPcmFile = getStagingFile("temp_pcm_${System.currentTimeMillis()}.raw")

        try {
            extractor = MediaExtractor().apply { setDataSource(sourceFile.absolutePath) }
            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    audioFormat = format
                    extractor.selectTrack(i)
                    break
                }
            }

            if (audioTrackIdx == -1 || audioFormat == null) {
                return false
            }

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return false
            var sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            if (sampleRate <= 0) sampleRate = 44100
            var channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            if (channels <= 0) channels = 2

            val isTrimming = (endSec > startSec) && (startSec >= 0L)
            val startUs = if (isTrimming) startSec * 1_000_000L else 0L
            val endUs = if (isTrimming) endSec * 1_000_000L else Long.MAX_VALUE

            if (isTrimming && startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            val fos = FileOutputStream(tempPcmFile)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var totalPcmBytes = 0L

            while (!outputEos) {
                if (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        inBuf?.clear()
                        val sampleSize = inBuf?.let { extractor.readSampleData(it, 0) } ?: -1
                        val sampleTime = extractor.sampleTime

                        if (sampleSize < 0 || (isTrimming && sampleTime > endUs)) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val presentationTime = bufferInfo.presentationTimeUs
                        if (!isTrimming || presentationTime >= startUs) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)

                            val chunkBytes = ByteArray(bufferInfo.size)
                            outBuf.get(chunkBytes)
                            fos.write(chunkBytes)
                            totalPcmBytes += chunkBytes.size
                        }
                    }

                    decoder.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true
                    }
                }
            }
            fos.flush()
            fos.close()

            if (tempPcmFile.exists() && totalPcmBytes > 1024) {
                if (isWav) {
                    val wavFos = FileOutputStream(destFile)
                    val byteRate = (sampleRate * channels * 16 / 8).toLong()
                    writeWavHeader(wavFos, totalPcmBytes, totalPcmBytes + 36, sampleRate.toLong(), channels, byteRate)
                    val pcmFis = FileInputStream(tempPcmFile)
                    val pcmBuf = ByteArray(32 * 1024)
                    var pRead: Int
                    while (pcmFis.read(pcmBuf).also { pRead = it } != -1) {
                        wavFos.write(pcmBuf, 0, pRead)
                    }
                    pcmFis.close()
                    wavFos.flush()
                    wavFos.close()
                    return destFile.exists() && destFile.length() > 1024
                } else {
                    val encoded = encodePcmToAac(tempPcmFile, destFile, sampleRate, channels, targetBitrate)
                    if (encoded && destFile.exists() && destFile.length() > 1024) {
                        return true
                    }
                }
            }

            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { if (tempPcmFile.exists()) tempPcmFile.delete() } catch (_: Exception) {}
        }
    }

    private fun writeWavHeader(
        out: java.io.OutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Long,
        channels: Int,
        byteRate: Long
    ) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private fun encodePcmToAac(
        pcmFile: File,
        outputFile: File,
        sampleRate: Int,
        channelCount: Int,
        bitrate: Int
    ): Boolean {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)
            var audioTrackIdx = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val fis = FileInputStream(pcmFile)
            val pcmChunk = ByteArray(8192)
            var isEos = false

            while (true) {
                if (!isEos) {
                    val inputIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inputIdx >= 0) {
                        val inputBuf = encoder.getInputBuffer(inputIdx)
                        inputBuf?.clear()
                        val read = fis.read(pcmChunk)
                        if (read <= 0) {
                            encoder.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            inputBuf?.put(pcmChunk, 0, read)
                            encoder.queueInputBuffer(inputIdx, 0, read, 0L, 0)
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    audioTrackIdx = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIdx >= 0) {
                    val outBuf = encoder.getOutputBuffer(outIdx)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted && outBuf != null) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(audioTrackIdx, outBuf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (isEos && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }
            }
            fis.close()
            return outputFile.exists() && outputFile.length() > 1024
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
        }
    }

    private fun buildProgressNotification(task: DownloadTask): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPaused = task.status == DownloadStatus.PAUSED
        val toggleIntent = Intent(this, DownloadActionReceiver::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            putExtra(EXTRA_TASK_ID, task.id)
        }
        val togglePending = PendingIntent.getBroadcast(
            this,
            (task.id + if (isPaused) "_resume" else "_pause").hashCode(),
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, DownloadActionReceiver::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TASK_ID, task.id)
        }
        val cancelPending = PendingIntent.getBroadcast(
            this,
            (task.id + "_cancel").hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (isPaused) "Paused: ${task.title}" else task.title)
            .setSubText("MediaFetch • ${task.quality.label}")
            .setContentText(if (isPaused) "Download paused • Tap Resume to continue" else "${task.getProgressPercent()}% • ${task.getFormattedSpeed()} • ${task.getFormattedDownloaded()}")
            .setProgress(100, task.getProgressPercent(), false)
            .setContentIntent(pendingIntent)
            .setOngoing(!isPaused)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                togglePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPending
            )

        val appIconBitmap = try {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) { null }

        val thumbBitmap = thumbnailBitmaps[task.thumbnail]
        if (thumbBitmap != null && !thumbBitmap.isRecycled) {
            builder.setLargeIcon(thumbBitmap)
        } else if (appIconBitmap != null) {
            builder.setLargeIcon(appIconBitmap)
        }

        if (task.thumbnail.isNotBlank() && (thumbBitmap == null || thumbBitmap.isRecycled)) {
            fetchThumbnailBitmapAsync(task.thumbnail) {
                updateNotification(task)
            }
        }

        return builder.build()
    }

    private fun updateNotification(task: DownloadTask) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildProgressNotification(task))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showCompletedNotification(file: File, task: DownloadTask) {
        try {
            val contentUri = try {
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                null
            }

            val mime = when {
                task.quality.isImage -> "image/*"
                task.quality.isAudioOnly -> "audio/*"
                else -> "video/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (contentUri != null) {
                    setDataAndType(contentUri, mime)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                task.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Share action intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TEXT, task.title)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            val chooserIntent = Intent.createChooser(shareIntent, "Share Media via")
            val sharePendingIntent = PendingIntent.getActivity(
                this,
                (task.id + "_share").hashCode(),
                chooserIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val appIconBitmap = try {
                BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            } catch (_: Exception) { null }

            val thumbBitmap = thumbnailBitmaps[task.thumbnail]

            val builder = NotificationCompat.Builder(this, CHANNEL_COMPLETED_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("Download Complete 🎉")
                .setContentText("${task.title} (${task.quality.label})")
                .setSubText("MediaFetch")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .addAction(
                    android.R.drawable.ic_menu_view,
                    "Open",
                    pendingIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_share,
                    "Share",
                    sharePendingIntent
                )

            if (thumbBitmap != null && !thumbBitmap.isRecycled) {
                builder.setLargeIcon(thumbBitmap)
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbBitmap)
                        .setSummaryText("${task.quality.label} • Saved to Downloads")
                )
            } else if (appIconBitmap != null) {
                builder.setLargeIcon(appIconBitmap)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(task.id.hashCode(), builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "mediafetch_downloads_channel"
        const val CHANNEL_COMPLETED_ID = "mediafetch_downloads_completed"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: DownloadService? = null

        const val ACTION_PAUSE = "com.mediafetch.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.mediafetch.app.ACTION_RESUME"
        const val ACTION_CANCEL = "com.mediafetch.app.ACTION_CANCEL"
        const val EXTRA_TASK_ID = "extra_task_id"

        private val staticPendingTasks = ConcurrentLinkedQueue<DownloadTask>()

        fun enqueuePendingTask(context: Context, task: DownloadTask) {
            staticPendingTasks.offer(task)
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}