package com.example.llmserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

object ServerState {
    val running = MutableStateFlow(false)
    val error = MutableStateFlow("")
}

/** Держит HTTP-сервер и модель в памяти (foreground-служба). */
class ServerService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> ServerLog.add("сбой: $e") }
    )
    private var http: HttpServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "LLM-сервер", NotificationManager.IMPORTANCE_LOW))
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification("запуск…"), type)

        scope.launch {
            combine(Llm.status, Queue.changed, ServerState.running) { s, _, r -> Triple(s, r, 0) }.collect { (s, r, _) ->
                val q = Queue.snapshot().size + if (Queue.running != null) 1 else 0
                val text = (if (r) "порт ${Cfg.port(this@ServerService)}" else "остановлен") +
                    " · модель: $s · в очереди: $q"
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_DOWNLOAD -> scope.launch { Llm.download(applicationContext) }
            ACTION_RELOAD -> scope.launch {
                Llm.close()
                runCatching { Llm.init(applicationContext) }
            }
        }
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (http?.running == true) return
        try {
            val s = HttpServer(Cfg.port(this), Cfg.lan(this)) { rq, rs -> Api.handle(applicationContext, rq, rs) }
            s.start()
            http = s
            ServerState.running.value = true
            ServerState.error.value = ""
            ServerLog.add("сервер запущен, порт ${Cfg.port(this)}" + if (Cfg.lan(this)) " (доступ из сети)" else "")
        } catch (e: Throwable) {
            ServerState.error.value = "не удалось открыть порт: ${e.message}"
            ServerLog.add("не удалось запустить сервер: ${e.message}")
            stopSelf()
            return
        }
        Llm.startWorker()
        if (Cfg.lan(this)) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "llmserver:cpu").apply { setReferenceCounted(false); acquire() }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "llmserver:wifi").apply { setReferenceCounted(false); acquire() }
        }
        if (!Llm.ready && Cfg.modelFile(this).exists()) {
            scope.launch { runCatching { Llm.init(applicationContext) } }
        } else if (!Cfg.modelFile(this).exists()) {
            Llm.status.value = "модели нет: скачайте её"
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentTitle("LLM-сервер")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE))
        .addAction(android.R.drawable.ic_media_pause, "Остановить", PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        http?.stop()
        http = null
        Llm.close()
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        ServerState.running.value = false
        ServerLog.add("сервер остановлен")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "server"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.example.llmserver.STOP"
        const val ACTION_DOWNLOAD = "com.example.llmserver.DOWNLOAD"
        const val ACTION_RELOAD = "com.example.llmserver.RELOAD"

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, ServerService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && Cfg.autostart(c)) ServerService.start(c)
    }
}
