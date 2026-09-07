package io.github.nobu0601.icocaautocharge.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.nobu0601.icocaautocharge.IcocaApp
import io.github.nobu0601.icocaautocharge.core.SecureLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通知の「後で」アクション。
 *
 * ここから Activity を起動することはない（通知トランポリン制限のため）。
 * 進行中の試行を「ユーザーがキャンセルした」として畳み、クールダウンを始めるだけ。
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LATER) return
        val locator = IcocaApp.locator ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                locator.coordinator.onUserPostponed()
                locator.notifications.cancelLowBalance()
            } catch (e: Exception) {
                SecureLog.e("failed to handle 'later' action", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LATER = "io.github.nobu0601.icocaautocharge.action.LATER"
    }
}
