package com.mediafetch.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val taskId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID) ?: return

        // 1. Instant in-memory dispatch if service is active
        val serviceInstance = DownloadService.instance
        if (serviceInstance != null) {
            when (action) {
                DownloadService.ACTION_PAUSE -> serviceInstance.pauseDownload(taskId)
                DownloadService.ACTION_RESUME -> serviceInstance.resumeDownload(taskId)
                DownloadService.ACTION_CANCEL -> serviceInstance.cancelDownload(taskId)
            }
            return
        }

        // 2. Service start fallback
        val serviceIntent = Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_TASK_ID, taskId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
