package io.github.nobu0601.icocaautocharge.monitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.nobu0601.icocaautocharge.IcocaApp
import io.github.nobu0601.icocaautocharge.core.SecureLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通知の「チャージ」アクションが開く、透明な中継 Activity。
 *
 * ### なぜ Activity なのか
 * バックグラウンドから ICOCA アプリを起動することは Android にブロックされる。
 * 「通知をユーザーがタップ → この Activity が前面に立つ → ここから ICOCA を起動」
 * という経路にすると、ユーザー起点の起動として許可される（ARCHITECTURE §8）。
 *
 * BroadcastReceiver を挟まないのは、Android 12 以降の通知トランポリン制限のため。
 */
class ChargeConfirmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != ACTION_START_CHARGE) {
            finish()
            return
        }
        val locator = IcocaApp.locator
        if (locator == null) {
            finish()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.Default) { locator.coordinator.startCharge() }
                when (result) {
                    is ChargeFlowCoordinator.StartResult.Launched -> {
                        // 見届け役をフォアグラウンドサービスとして起動する。
                        // 保険として WorkManager でも残高確認を予約しておく。
                        ChargeFlowService.start(this@ChargeConfirmActivity, result.automation)
                        locator.scheduler.scheduleVerification()
                    }
                    is ChargeFlowCoordinator.StartResult.Failed -> {
                        Toast.makeText(this@ChargeConfirmActivity, result.message, Toast.LENGTH_LONG)
                            .show()
                    }
                }
            } catch (e: Exception) {
                SecureLog.e("failed to start charge from notification", e)
            } finally {
                finish()
            }
        }
    }

    companion object {
        const val ACTION_START_CHARGE = "io.github.nobu0601.icocaautocharge.action.START_CHARGE"
        const val EXTRA_AMOUNT_YEN = "amount_yen"

        /** アプリ内（ダッシュボード）からチャージを始めるときの入口。 */
        fun intent(context: Context): Intent =
            Intent(context, ChargeConfirmActivity::class.java).setAction(ACTION_START_CHARGE)
    }
}
