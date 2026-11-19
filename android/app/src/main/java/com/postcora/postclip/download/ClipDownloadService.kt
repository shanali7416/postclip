package com.postcora.postclip.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import com.postcora.postclip.MainActivity
import com.postcora.postclip.R
import com.postcora.postclip.core.SizeEstimate
import com.postcora.postclip.core.TimeFormat
import java.io.File

/** Foreground (dataSync) service that runs one clip download with an ongoing progress notification. */
@UnstableApi
class ClipDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "postclip_downloads"
        private const val NOTIF_PROGRESS = 41
        private const val NOTIF_DONE = 42
        const val ACTION_START = "com.postcora.postclip.START"
        const val ACTION_CANCEL = "com.postcora.postclip.CANCEL"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CHANNEL_ID, "Clip downloads", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Progress and results of PostClip downloads"
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
        }

        fun start(context: Context, job: ClipJob) {
            DownloadRepository.pendingJob = job
            val i = Intent(context, ClipDownloadService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ClipDownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private var engine: ClipEngine? = null
    private var job: ClipJob? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                engine?.cancel()
                engine = null
                DownloadRepository.update(DownloadState.Cancelled)
                stopEverything()
            }
            ACTION_START -> {
                val j = DownloadRepository.pendingJob
                if (j == null || engine != null) {
                    if (engine == null) stopSelf()
                    return START_NOT_STICKY
                }
                job = j
                createChannel(this)
                ServiceCompat.startForeground(this, NOTIF_PROGRESS, progressNotification(null),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
                acquireLocks()
                begin(j)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PostClip:download").apply {
            setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "PostClip:download").apply { setReferenceCounted(false); acquire() }
    }

    private fun begin(j: ClipJob) {
        val dir = File(cacheDir, "clips").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "clip.mp4")
        DownloadRepository.update(DownloadState.Running(0, 0, j.estimatedBytes, 0.0, null, 1, "Starting"))
        engine = ClipEngine(this, j, out,
            onProgress = { s ->
                DownloadRepository.update(s)
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt > 1000) { lastNotifyAt = now; notify(NOTIF_PROGRESS, progressNotification(s)) }
            },
            onFinished = { result ->
                engine = null
                result.fold(
                    onSuccess = { r ->
                        try {
                            val name = fileName(j)
                            val uri = ClipSaver.save(this, out, name)
                            val size = out.length()
                            out.delete()
                            DownloadRepository.update(DownloadState.Done(uri, "Download/PostClip/$name", size, r.durationMs))
                            notifyDone(uri, name, size, j.fullVideo)
                        } catch (e: Exception) {
                            DownloadRepository.update(DownloadState.Failed("Could not save the file: ${e.message}"))
                        }
                    },
                    onFailure = { e -> DownloadRepository.update(DownloadState.Failed(e.message ?: "Download failed.")) }
                )
                stopEverything()
            })
        engine!!.start()
    }

    private fun fileName(j: ClipJob): String {
        val safe = j.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(40).ifEmpty { j.videoId }
        if (j.fullVideo) return "PostClip_${safe}_full.mp4".replace(' ', '_')
        val range = TimeFormat.format(j.startSec).replace(":", ".") + "-" + TimeFormat.format(j.endSec).replace(":", ".")
        return "PostClip_${safe}_$range.mp4".replace(' ', '_')
    }

    private fun pending(intent: Intent, code: Int, activity: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (activity) PendingIntent.getActivity(this, code, intent, flags)
        else PendingIntent.getService(this, code, intent, flags)
    }

    private fun progressNotification(s: DownloadState.Running?): Notification {
        val cancel = pending(Intent(this, ClipDownloadService::class.java).setAction(ACTION_CANCEL), 1, false)
        val open = pending(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 2, true)
        val text = if (s == null) "Starting…" else buildString {
            append("${SizeEstimate.formatBytes(s.bytesDownloaded)} / ≈${SizeEstimate.formatBytes(s.bytesTotalEstimate)}")
            if (s.speedBps > 0) append(" · ${SizeEstimate.formatSpeed(s.speedBps)}")
            s.etaSeconds?.let { append(" · ${TimeFormat.format(it)} left") }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clip)
            .setContentTitle(if (s == null) "PostClip" else (if (job?.fullVideo == true) "Downloading ${s.percent}%" else "Clipping ${s.percent}%") + if (s.attempt > 1) " (retry)" else "")
            .setContentText(text)
            .setSubText(job?.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, s?.percent ?: 0, s == null)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun notifyDone(uri: android.net.Uri, name: String, size: Long, full: Boolean) {
        val open = pending(ClipSaver.openIntent(uri), 3, true)
        val share = pending(ClipSaver.shareIntent(uri), 4, true)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clip)
            .setContentTitle(if (full) "Video saved" else "Clip saved")
            .setContentText("$name · ${SizeEstimate.formatBytes(size)}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Open", open)
            .addAction(0, "Share", share)
            .build()
        notify(NOTIF_DONE, n)
    }

    private fun notify(id: Int, n: Notification) {
        try { NotificationManagerCompat.from(this).notify(id, n) } catch (_: SecurityException) { /* denied: download still runs */ }
    }

    private fun stopEverything() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
