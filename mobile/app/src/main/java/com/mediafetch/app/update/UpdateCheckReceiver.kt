package com.mediafetch.app.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UpdateCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action || Intent.ACTION_MY_PACKAGE_REPLACED == intent.action) {
            schedulePeriodicCheck(context)
            return
        }

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val checkResult = UpdateManager.checkForUpdate(context, force = true)
                val info = checkResult.updateInfo
                if (info != null) {
                    UpdateManager.showUpdateNotification(context, info)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 4401

        fun schedulePeriodicCheck(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, UpdateCheckReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                val pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)

                // Check every 2 hours in the background
                val interval = 2 * 60 * 60 * 1000L
                val triggerAt = SystemClock.elapsedRealtime() + (60 * 1000L)

                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    interval,
                    pendingIntent
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
