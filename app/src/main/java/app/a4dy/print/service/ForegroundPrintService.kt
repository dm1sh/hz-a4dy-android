// SPDX-License-Identifier: MIT
package app.a4dy.print.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import app.a4dy.print.MainActivity
import app.a4dy.print.PrintRequest
import app.a4dy.print.R
import app.a4dy.print.runtime
import kotlinx.coroutines.*

class ForegroundPrintService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var requestId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { runtime.cancel(requestId); task?.cancel(); if (task?.isActive != true) stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("id")
        val request = id?.let { runtime.pending.remove(it) }
        if (request == null) { if (task?.isActive != true) stopSelf(); return START_NOT_STICKY }
        if (task?.isActive == true) { runtime.log.add("Duplicate foreground request rejected."); return START_NOT_STICKY }
        requestId = request.id
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Bluetooth printing", NotificationManager.IMPORTANCE_LOW))
        try { startForeground(7, notification("Preparing print job…", 0)) }
        catch (_: Exception) {
            runtime.rejectBeforeStart(request, "Android could not start background printing. Open the app, grant Nearby devices, then retry.")
            stopSelf(); return START_NOT_STICKY
        }
        task = scope.launch {
            var last = 0L
            try {
                runtime.execute(request) { state ->
                    val now = SystemClock.elapsedRealtime()
                    if (!state.running || now - last > 250) {
                        last = now; runCatching { manager.notify(7, notification(state.message, (state.fraction * 100).toInt())) }
                    }
                }
            } catch (_: CancellationException) {
            } catch (_: Exception) { /* Runtime exposes the failure, never auto-retries. */
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun notification(message: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, ForegroundPrintService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_print).setContentTitle("A4DY Print")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0).addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
    }
    override fun onDestroy() { runtime.cancel(requestId); scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "printing"
        private const val CANCEL = "app.a4dy.print.CANCEL"
        fun cancel(context: Context) { context.startService(Intent(context, ForegroundPrintService::class.java).setAction(CANCEL)) }
        fun start(context: Context, request: PrintRequest) {
            context.runtime.pending[request.id] = request
            try { context.startForegroundService(Intent(context, ForegroundPrintService::class.java).putExtra("id", request.id)) }
            catch (e: Exception) { context.runtime.pending.remove(request.id); throw e }
        }
    }
}
