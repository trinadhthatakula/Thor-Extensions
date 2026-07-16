package com.valhalla.thor.ext.antivirus

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AntivirusScanService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val NOTIFICATION_ID = 1337
    private val CHANNEL_ID = "antivirus_scan_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scanType = intent?.getIntExtra("scan_type", 0) ?: 0
        val pickedUris = intent?.getParcelableArrayListExtra<android.net.Uri>("uris")

        // Start as foreground immediately
        val initialNotification = buildNotification("Preparing scan...", 0, 0)
        startForeground(NOTIFICATION_ID, initialNotification)

        // Perform the scan loop via AntivirusScanManager
        AntivirusScanManager.performScanLoop(
            context = this,
            scanType = scanType,
            pickedUris = pickedUris,
            onProgress = {
                val currentPkg = AntivirusScanManager.currentScannedPackage.value
                val scanned = AntivirusScanManager.scannedCount.value
                val threats = AntivirusScanManager.threatCount.value
                updateNotification("Scanning: $currentPkg", scanned, threats)
            },
            onFinished = {
                val finalThreats = AntivirusScanManager.threatCount.value
                val finalScanned = AntivirusScanManager.scannedCount.value
                stopForeground(true)
                showFinalNotification(finalScanned, finalThreats)
                stopSelf()
            }
        )

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "S.H.I.E.L.D. Active Scan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time antivirus scan status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String, scanned: Int, threats: Int): Notification {
        val intent = Intent(this, ConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (threats > 0) "⚠️ S.H.I.E.L.D. Threats Detected!" else "🛡️ S.H.I.E.L.D. Active Security Scan"
        val subtitle = "Scanned: $scanned | Threats: $threats\n$message"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String, scanned: Int, threats: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(message, scanned, threats))
    }

    private fun showFinalNotification(scanned: Int, threats: Int) {
        val intent = Intent(this, ConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (threats > 0) "⚠️ S.H.I.E.L.D. Threat Alert!" else "🛡️ S.H.I.E.L.D. Scan Completed"
        val content = if (threats > 0) {
            "Scan finished. $threats threats detected in $scanned items."
        } else {
            "No threats found across $scanned verified items."
        }

        val finalNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, finalNotification)
    }
}
