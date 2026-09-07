package io.github.nobu0601.icocaautocharge.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.nobu0601.icocaautocharge.R
import io.github.nobu0601.icocaautocharge.core.Money
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.monitor.ChargeConfirmActivity

/**
 * 通知の組み立て（指示書 §12）。
 *
 * ### 設計上の要
 * 「チャージ」アクションは **PendingIntent.getActivity で Activity を直接開く**。
 * BroadcastReceiver を経由して startActivity すると、Android 12 以降の
 * 通知トランポリン制限でブロックされるため（ARCHITECTURE §8）。
 *
 * また、バックグラウンドの Worker が ICOCA アプリを直接起動することはできない。
 * 「通知 → ユーザーのタップ → 自アプリの前面 Activity → ICOCA 起動」という
 * ユーザー起点の経路を必ず通す。
 */
class Notifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_LOW_BALANCE,
                context.getString(R.string.channel_low_balance_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_low_balance_desc) },
            NotificationChannel(
                CHANNEL_CHARGE_READY,
                context.getString(R.string.channel_charge_ready_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_charge_ready_desc) },
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.channel_result_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_result_desc) },
            NotificationChannel(
                CHANNEL_FLOW,
                context.getString(R.string.channel_flow_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_flow_desc) },
        )
        manager.createNotificationChannels(channels)
    }

    fun canPost(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            manager.areNotificationsEnabled()
        }

    /** 残高低下（指示書 §12）。 */
    fun showLowBalance(balanceYen: Int?, thresholdYen: Int, chargeAmountYen: Int) {
        val body = if (balanceYen != null) {
            context.getString(
                R.string.notif_low_balance_body,
                Money.format(balanceYen),
                Money.format(thresholdYen),
            )
        } else {
            context.getString(R.string.notif_low_balance_body_unknown)
        }
        val n = baseBuilder(CHANNEL_LOW_BALANCE)
            .setContentTitle(context.getString(R.string.notif_low_balance_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(chargeIntent(chargeAmountYen))
            .addAction(
                0,
                context.getString(R.string.notif_action_charge),
                chargeIntent(chargeAmountYen),
            )
            .addAction(0, context.getString(R.string.notif_action_later), laterIntent())
            .build()
        post(ID_LOW_BALANCE, n)
    }

    /** チャージ準備完了（指示書 §12）。 */
    fun showChargeReady(chargeAmountYen: Int) {
        val n = baseBuilder(CHANNEL_CHARGE_READY)
            .setContentTitle(
                context.getString(R.string.notif_ready_title, Money.format(chargeAmountYen)),
            )
            .setContentText(context.getString(R.string.notif_ready_body))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_ready_body)),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(chargeIntent(chargeAmountYen))
            .addAction(
                0,
                context.getString(R.string.notif_action_open_icoca),
                chargeIntent(chargeAmountYen),
            )
            .build()
        post(ID_CHARGE_READY, n)
    }

    fun showSuccess(beforeYen: Int?, afterYen: Int?, chargedYen: Int) {
        val body = if (beforeYen != null && afterYen != null) {
            context.getString(
                R.string.notif_success_body,
                Money.format(beforeYen),
                Money.format(afterYen),
                Money.format(chargedYen),
            )
        } else {
            context.getString(R.string.notif_success_unverified_body)
        }
        val n = baseBuilder(CHANNEL_RESULT)
            .setContentTitle(context.getString(R.string.notif_success_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()
        post(ID_RESULT, n)
    }

    /** 失敗（指示書 §12）。理由は必ず本文に出す。 */
    fun showError(reasonMessage: String) {
        val n = baseBuilder(CHANNEL_RESULT)
            .setContentTitle(context.getString(R.string.notif_error_title))
            .setContentText(reasonMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reasonMessage))
            .build()
        post(ID_RESULT, n)
    }

    /** チャージ処理中のフォアグラウンド通知。 */
    fun buildFlowNotification(): Notification =
        baseBuilder(CHANNEL_FLOW)
            .setContentTitle(context.getString(R.string.notif_flow_title))
            .setContentText(context.getString(R.string.notif_flow_body))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun cancelLowBalance() = manager.cancel(ID_LOW_BALANCE)

    private fun baseBuilder(channelId: String) =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

    /**
     * 「チャージ」アクション。ユーザーのタップで前面 Activity を開く。
     *
     * Android 15 以降、PendingIntent の作成側もバックグラウンド起動特権の
     * opt-in を明示する必要があるが、ここは自アプリの Activity を開くだけなので
     * その特権を委譲する必要はない（ICOCA の起動は開いた Activity が前面で行う）。
     */
    private fun chargeIntent(chargeAmountYen: Int): PendingIntent {
        val intent = Intent(context, ChargeConfirmActivity::class.java).apply {
            action = ChargeConfirmActivity.ACTION_START_CHARGE
            putExtra(ChargeConfirmActivity.EXTRA_AMOUNT_YEN, chargeAmountYen)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            REQ_CHARGE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun laterIntent(): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_LATER
        }
        return PendingIntent.getBroadcast(
            context,
            REQ_LATER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(id: Int, notification: Notification) {
        if (!canPost()) {
            SecureLog.w(SecureLog.Tag.MONITOR, "notification permission not granted; skipped id=$id")
            return
        }
        runCatching { manager.notify(id, notification) }
            .onFailure { SecureLog.e("failed to post notification id=$id", it) }
    }

    companion object {
        const val CHANNEL_LOW_BALANCE = "low_balance"
        const val CHANNEL_CHARGE_READY = "charge_ready"
        const val CHANNEL_RESULT = "charge_result"
        const val CHANNEL_FLOW = "charge_flow"

        const val ID_LOW_BALANCE = 1001
        const val ID_CHARGE_READY = 1002
        const val ID_RESULT = 1003
        const val ID_FLOW = 1004

        private const val REQ_CHARGE = 2001
        private const val REQ_LATER = 2002
    }
}
