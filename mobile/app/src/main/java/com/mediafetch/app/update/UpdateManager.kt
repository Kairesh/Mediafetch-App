package com.mediafetch.app.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.mediafetch.app.MainActivity
import com.mediafetch.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val apkUrl: String,
    val releaseDate: String,
    val releaseNotes: List<String>,
    val isMandatory: Boolean
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("version", version)
            put("versionCode", versionCode)
            put("apkUrl", apkUrl)
            put("releaseDate", releaseDate)
            val notesArr = JSONArray()
            releaseNotes.forEach { notesArr.put(it) }
            put("releaseNotes", notesArr)
            put("isMandatory", isMandatory)
        }
    }
}

data class CheckUpdateResult(
    val updateInfo: UpdateInfo? = null,
    val isLatest: Boolean = false,
    val errorMessage: String? = null
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("hasUpdate", updateInfo != null)
            if (updateInfo != null) {
                put("update", updateInfo.toJsonObject())
            }
            put("isLatest", isLatest)
            if (errorMessage != null) {
                put("error", errorMessage)
            }
        }
    }
}

object UpdateManager {

    private const val UPDATE_URL = "https://raw.githubusercontent.com/Kairesh/Mediafetch-App/main/version.json"
    private const val CHANNEL_UPDATES_ID = "mediafetch_updates"
    private const val PREFS_NAME = "mediafetch_update_prefs"
    private const val KEY_SKIPPED_CODE = "skipped_version_code"
    private const val KEY_REMIND_TIMESTAMP = "remind_later_timestamp"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkForUpdate(context: Context, force: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(UPDATE_URL)
                .header("Cache-Control", "no-cache")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (resp.code == 404) {
                return@withContext CheckUpdateResult(
                    errorMessage = "Update server returned 404. Check that version.json exists on Kairesh/Mediafetch-App."
                )
            }
            if (!resp.isSuccessful) {
                return@withContext CheckUpdateResult(
                    errorMessage = "Update check failed with server status HTTP ${resp.code}"
                )
            }
            val body = resp.body?.string() ?: return@withContext CheckUpdateResult(
                errorMessage = "Received empty response from update server"
            )

            val json = JSONObject(body)
            val mobileObj = json.optJSONObject("mobile") ?: return@withContext CheckUpdateResult(
                errorMessage = "Invalid update schema: 'mobile' key missing in version.json"
            )

            val serverVersion = mobileObj.optString("version", "1.0.0")
            val serverCode = mobileObj.optInt("versionCode", 1)
            val apkUrl = mobileObj.optString("apkUrl", "")
            val releaseDate = mobileObj.optString("releaseDate", "")
            val isMandatory = mobileObj.optBoolean("mandatory", false)

            val notesList = mutableListOf<String>()
            val notesArr = mobileObj.optJSONArray("releaseNotes")
            if (notesArr != null) {
                for (i in 0 until notesArr.length()) {
                    notesList.add(notesArr.optString(i))
                }
            }

            val currentCode = try {
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (_: Exception) {
                1
            }

            if (serverCode <= currentCode) {
                return@withContext CheckUpdateResult(isLatest = true)
            }

            if (!force && !isMandatory) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val skipped = prefs.getInt(KEY_SKIPPED_CODE, -1)
                if (skipped == serverCode) {
                    return@withContext CheckUpdateResult(isLatest = true)
                }
                val remindTime = prefs.getLong(KEY_REMIND_TIMESTAMP, 0L)
                if (System.currentTimeMillis() < remindTime) {
                    return@withContext CheckUpdateResult(isLatest = true)
                }
            }

            val info = UpdateInfo(
                version = serverVersion,
                versionCode = serverCode,
                apkUrl = apkUrl,
                releaseDate = releaseDate,
                releaseNotes = notesList,
                isMandatory = isMandatory
            )

            return@withContext CheckUpdateResult(updateInfo = info)
        } catch (e: Exception) {
            CheckUpdateResult(errorMessage = "Network error: ${e.message}")
        }
    }

    fun showUpdateNotification(context: Context, updateInfo: UpdateInfo) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_UPDATES_ID,
                    "MediaFetch App Updates",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when a new version of MediaFetch is ready to install"
                    setShowBadge(true)
                    enableVibration(true)
                    lightColor = Color.parseColor("#0A84FF")
                }
                manager.createNotificationChannel(channel)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_update_dialog", true)
                putExtra("update_info_json", updateInfo.toJsonObject().toString())
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                2026,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val notesSummary = if (updateInfo.releaseNotes.isNotEmpty()) {
                updateInfo.releaseNotes.take(3).joinToString("\n") { "• $it" }
            } else {
                "Tap to review new features and install update."
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("🚀 MediaFetch v${updateInfo.version} Available")
                .setContentText("New update ready: tap to view new features & install!")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle("🚀 MediaFetch v${updateInfo.version} Available")
                        .bigText("What's new in this release:\n$notesSummary\n\nTap to review changes & update directly.")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            manager.notify(9901, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun snoozeRemindLater(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Snooze for 24 hours
        val nextTime = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
        prefs.edit().putLong(KEY_REMIND_TIMESTAMP, nextTime).apply()
    }

    fun skipVersion(context: Context, versionCode: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SKIPPED_CODE, versionCode).apply()
    }

    suspend fun downloadAndInstall(
        activity: Activity,
        apkUrl: String,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(apkUrl).build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                withContext(Dispatchers.Main) { onError("Download failed with HTTP ${resp.code}") }
                return@withContext
            }

            val body = resp.body ?: run {
                withContext(Dispatchers.Main) { onError("Empty response body") }
                return@withContext
            }

            val totalBytes = body.contentLength()
            val destFile = File(activity.cacheDir, "MediaFetch_Update.apk")
            if (destFile.exists()) destFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastPercent = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    output.flush()
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
                triggerApkInstall(activity, destFile)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError("Update failed: ${e.message}")
            }
        }
    }

    private fun triggerApkInstall(activity: Activity, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
