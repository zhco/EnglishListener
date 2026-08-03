package com.englishlistener.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.englishlistener.MainActivity

/**
 * 前台服务 —— 保障后台播放
 * 通知栏显示当前电台名称和播放/暂停按钮
 */
class RadioPlayerService : Service() {

    companion object {
        const val CHANNEL_ID = "radio_playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.englishlistener.STOP"
    }

    lateinit var player: RadioPlayer

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        player = RadioPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val stationName = intent?.getStringExtra("station_name") ?: "EnglishListener"
                val streamUrl = intent?.getStringExtra("stream_url") ?: return START_NOT_STICKY

                startForeground(NOTIFICATION_ID, buildNotification(stationName))
                player.play(stationName, streamUrl)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "电台播放",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "EnglishListener 后台播放通知"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(stationName: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stopIntent = Intent(this, RadioPlayerService::class.java).apply {
            action = ACTION_STOP
        }.let {
            PendingIntent.getService(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EnglishListener")
            .setContentText("正在播放：$stationName")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
