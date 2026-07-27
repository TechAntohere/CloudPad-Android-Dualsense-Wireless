// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.pylux.stream.R

class ControllerBtForegroundService : Service()
{
    companion object
    {
        private const val ACTION_START = "com.metallic.chiaki.ACTION_CONTROLLER_BT_START"
        private const val ACTION_STOP = "com.metallic.chiaki.ACTION_CONTROLLER_BT_STOP"
        private const val CHANNEL_ID = "controller_bt_live"
        private const val NOTIFICATION_ID = 8843

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context)
        {
            val appContext = context.applicationContext
            val intent = Intent(appContext, ControllerBtForegroundService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(appContext, intent)
            }
        }

        fun stop(context: Context)
        {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, ControllerBtForegroundService::class.java))
            }
        }
    }

    private var btWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate()
    {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        if(intent?.action == ACTION_STOP)
        {
            stopSelf()
            return START_NOT_STICKY
        }

		val notification = buildNotification()
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			startForeground(
				NOTIFICATION_ID,
				notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
			)
		else
			startForeground(NOTIFICATION_ID, notification)
        isRunning = true
        // Keep CPU alive so BT speaker thread runs with screen off
        if(btWakeLock == null)
        {
            btWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chiaki:ControllerBtFeedback")
                .also { it.acquire() }
        }
        // Start BT feedback singleton. onResume() is idempotent — safe to call even when already running
        DualSenseBtStreamFeedback.acquireShared(this).onResume()
		return START_STICKY
	}

    override fun onDestroy()
    {
        DualSenseBtStreamFeedback.shared?.onPause()
        DualSenseBtStreamFeedback.releaseShared()
        btWakeLock?.let { if(it.isHeld) it.release() }
        btWakeLock = null
        isRunning = false
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_REMOVE)
        else
            @Suppress("DEPRECATION")
            stopForeground(true)
        super.onDestroy()
    }

    private fun createNotificationChannel()
    {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return

        val manager = getSystemService(NotificationManager::class.java)
        if(manager.getNotificationChannel(CHANNEL_ID) != null)
            return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Controller Bluetooth",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps DualSense Bluetooth feedback active during streaming"
            }
        )
    }

    private fun buildNotification(): Notification
    {
        val stopIntent = Intent(this, ControllerBtForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_rumble)
                .setContentTitle("Controller Bluetooth Active")
                .setContentText("Maintaining DualSense feedback timing")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
                .build()
        }
        else
        {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_rumble)
                .setContentTitle("Controller Bluetooth Active")
                .setContentText("Maintaining DualSense feedback timing")
                .setOngoing(true)
                .build()
        }
    }
}
