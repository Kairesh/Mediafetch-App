package com.mediafetch.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mediafetch.app.R

class MediaFetchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, MediaFetchWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_mediafetch)

            // 1. Paste & Fast Download Action (Triggers ShareActivity popup or MainActivity)
            val pasteIntent = Intent(context, WidgetActionActivity::class.java).apply {
                action = WidgetActionActivity.ACTION_PASTE_DOWNLOAD
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pastePending = PendingIntent.getActivity(
                context,
                1001,
                pasteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_paste_download, pastePending)

            // 2. Open Input Field
            val inputIntent = Intent(context, WidgetActionActivity::class.java).apply {
                action = WidgetActionActivity.ACTION_OPEN_INPUT
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val inputPending = PendingIntent.getActivity(
                context,
                1002,
                inputIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_open_app, inputPending)

            // 3. Trimmer Studio
            val trimmerIntent = Intent(context, WidgetActionActivity::class.java).apply {
                action = WidgetActionActivity.ACTION_OPEN_TRIMMER
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val trimmerPending = PendingIntent.getActivity(
                context,
                1003,
                trimmerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_trimmer, trimmerPending)

            // 4. Downloads / History
            val historyIntent = Intent(context, WidgetActionActivity::class.java).apply {
                action = WidgetActionActivity.ACTION_OPEN_DOWNLOADS
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val historyPending = PendingIntent.getActivity(
                context,
                1004,
                historyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_downloads, historyPending)

            // 5. Header / Main Studio
            val mainIntent = Intent(context, WidgetActionActivity::class.java).apply {
                action = WidgetActionActivity.ACTION_OPEN_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mainPending = PendingIntent.getActivity(
                context,
                1005,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_header, mainPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
