package com.cleantube.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cleantube.app.data.DownloadEngine
import com.cleantube.app.data.DownloadTask
import com.cleantube.app.data.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台下载服务：让下载在切后台 / 锁屏后继续，
 * 通知栏实时显示进度，全部完成后自动退出。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var lastSignature = ""

    override fun onCreate() {
        super.onCreate()
        DownloadEngine.init(this)
        HistoryStore.init(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 清掉上一次残留的完成通知
        runCatching { getSystemService(NotificationManager::class.java).cancel(COMPLETION_ID) }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        collectJob?.cancel()
        collectJob = scope.launch {
            DownloadEngine.tasks.collect { onTasksChanged(it) }
        }
        return START_NOT_STICKY
    }

    private fun onTasksChanged(list: List<DownloadTask>) {
        val active = list.filter { it.active }
        val nm = getSystemService(NotificationManager::class.java)
        if (active.isEmpty()) {
            val last = list.lastOrNull()
            if (last != null) {
                runCatching { nm.notify(COMPLETION_ID, buildCompletionNotification(last)) }
            }
            // REMOVE 会在停止前台的同时移除进度通知（DETACH + cancel 在部分系统上会残留）
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            collectJob?.cancel()
            stopSelf()
            return
        }
        // 通知栏展示第一个进行中的任务，节流到秒级百分比
        val task = active.first()
        val percent = (task.progress * 100).toInt()
        val signature = "${task.key}|${task.stage}|$percent"
        if (signature != lastSignature) {
            lastSignature = signature
            runCatching { nm.notify(NOTIFICATION_ID, buildProgressNotification(task)) }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "视频下载进度" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun appPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildProgressNotification(task: DownloadTask?): Notification {
        val percent = ((task?.progress ?: 0f) * 100).toInt()
        val text = when (task?.stage) {
            DownloadTask.Stage.DOWNLOADING -> "下载中 $percent%"
            DownloadTask.Stage.MERGING -> "正在合并音视频…"
            DownloadTask.Stage.SAVING -> "正在保存到媒体库…"
            else -> "准备下载…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(task?.title ?: "CleanDownloader")
            .setContentText(text)
            .setProgress(100, percent, task == null || task.stage == DownloadTask.Stage.DOWNLOADING && task.progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(appPendingIntent())
            .build()
    }

    private fun buildCompletionNotification(task: DownloadTask): Notification {
        val success = task.stage == DownloadTask.Stage.DONE
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(task.title)
            .setContentText(if (success) "《${task.label}》已保存到 ${task.detail?.substringAfter("到 ") ?: "媒体库"}" else "下载失败：${task.detail}")
            .setStyle(NotificationCompat.BigTextStyle())
            .setAutoCancel(true)
            .setContentIntent(appPendingIntent())
            .build()
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "download"
        private const val NOTIFICATION_ID = 1
        private const val COMPLETION_ID = 2
    }
}
