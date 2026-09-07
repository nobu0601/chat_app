package io.github.nobu0601.icocaautocharge.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.nobu0601.icocaautocharge.IcocaApp
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * チャージ処理を見届けるフォアグラウンドサービス。
 *
 * ICOCA アプリが前面にいる間、本アプリのプロセスが落とされて
 * 試行が宙ぶらりんになるのを防ぐ。また、処理中であることをユーザーに可視化する。
 *
 * 常駐はしない。フローが終われば自分で止まる（指示書 §19）。
 */
class ChargeFlowService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = IcocaApp.locator?.notifications ?: Notifications(applicationContext)
        notifications.ensureChannels()
        startAsForeground(notifications)

        val automation = intent?.getBooleanExtra(EXTRA_AUTOMATION, false) ?: false
        if (job?.isActive == true) return START_NOT_STICKY

        job = scope.launch {
            try {
                IcocaApp.locator?.coordinator?.superviseAndVerify(automation)
            } catch (e: Exception) {
                SecureLog.e("charge flow supervision failed", e)
            } finally {
                stopSelf()
            }
        }
        // 途中で殺されても勝手に再実行させない。二重チャージの温床になるため。
        return START_NOT_STICKY
    }

    private fun startAsForeground(notifications: Notifications) {
        val notification = notifications.buildFlowNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID_FLOW,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ID_FLOW, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_AUTOMATION = "automation"

        fun start(context: Context, automation: Boolean) {
            val intent = Intent(context, ChargeFlowService::class.java)
                .putExtra(EXTRA_AUTOMATION, automation)
            runCatching { context.startForegroundService(intent) }
                .onFailure { SecureLog.e("failed to start charge flow service", it) }
        }
    }
}
