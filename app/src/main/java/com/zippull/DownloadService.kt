package com.zippull

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*

class DownloadService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private var started = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    private fun note(text: String, ongoing: Boolean = true): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "dl")
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle("ZipPull")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!State.running) return
            val pct = if (State.totalBytes > 0) State.done.get() * 100 / State.totalBytes else 0
            nm.notify(1, note("$pct% - ${State.filesDone.get()}/${State.filesTotal} files"))
            h.postDelayed(this, 1500)
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startForeground(1, note("Starting..."))
        if (started) return START_NOT_STICKY
        started = true
        h.post(tick)
        Thread {
            val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zippull:dl")
            wl.acquire(6 * 3600 * 1000L)
            try {
                Worker.run(this)
            } catch (t: Throwable) {
                State.status = "Error: ${t.message}"
            } finally {
                if (wl.isHeld) wl.release()
                State.running = false
                h.post {
                    stopForeground(true)
                    nm.notify(2, note(State.status.lineSequence().first(), false))
                    stopSelf()
                }
            }
        }.start()
        return START_NOT_STICKY
    }
}
