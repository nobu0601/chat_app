package io.github.nobu0601.icocaautocharge.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.nobu0601.icocaautocharge.IcocaApp
import io.github.nobu0601.icocaautocharge.core.SecureLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 端末再起動・アプリ更新後に監視を張り直す（TEST_PLAN §3 の項目16）。
 *
 * WorkManager は再起動をまたいで復元されるが、設定変更との整合を取るため
 * ここでも明示的に組み直す。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val locator = IcocaApp.locator ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = locator.settingsRepo.current()
                locator.scheduler.reschedule(settings)
                SecureLog.i(SecureLog.Tag.MONITOR, "monitoring rescheduled after $action")
            } catch (e: Exception) {
                SecureLog.e("failed to reschedule after boot", e)
            } finally {
                pending.finish()
            }
        }
    }
}
