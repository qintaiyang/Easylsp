package io.github.easylsp.app.platform.diagnostics

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import io.github.easylsp.app.MainActivity
import io.github.easylsp.app.EasylspApp

class DiagnosticService : Service() {
    companion object {
        fun start(graph: EasylspApp) { graph.startForegroundService(Intent(graph, DiagnosticService::class.java)) }
        fun finish(graph: EasylspApp) { graph.stopService(Intent(graph, DiagnosticService::class.java)) }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("startup-diagnostics", "启动诊断", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, DiagnosticService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "startup-diagnostics").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在采集启动诊断").setContentText("目标退出后仍会整理证据；达到时限自动结束")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "结束采集", stop).build()).build()
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(32, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(32, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") (application as EasylspApp).diagnostics.stopAll()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { (application as EasylspApp).diagnostics.stopAll(); super.onDestroy() }
}
