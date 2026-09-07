package io.github.nobu0601.icocaautocharge.monitor

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.data.settings.AppSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 監視のスケジューリング（指示書 §19）。
 *
 * WorkManager の周期実行の下限は 15 分。加えて Doze と App Standby により
 * 実際の実行はさらに遅れる。**定刻実行は保証されない**ことを UI にも明示している。
 *
 * バッテリーを食う常時監視はしない（指示書 §19 の禁止事項）。
 */
class MonitorScheduler(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    fun reschedule(settings: AppSettings) {
        if (!settings.monitoringEnabled) {
            cancel()
            return
        }
        val hours = settings.checkIntervalHours.coerceAtLeast(1).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                // 「Wi-Fi時のみ」はここで効かせる。判定側でも二重に見ている。
                if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED,
            )
            .setRequiresCharging(settings.chargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<BalanceCheckWorker>(
            hours, TimeUnit.HOURS,
            FLEX_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString(BalanceCheckWorker.KEY_TRIGGER, "periodic").build())
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC_CHECK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        SecureLog.i(SecureLog.Tag.MONITOR, "periodic check scheduled every ${hours}h")
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_PERIODIC_CHECK)
        SecureLog.i(SecureLog.Tag.MONITOR, "periodic check cancelled")
    }

    /** 「今すぐ確認」。制約を付けずに1回だけ走らせる。 */
    fun runCheckNow() {
        val request = OneTimeWorkRequestBuilder<BalanceCheckWorker>()
            .setInputData(Data.Builder().putString(BalanceCheckWorker.KEY_TRIGGER, "manual").build())
            .build()
        workManager.enqueueUniqueWork(WORK_ONE_SHOT_CHECK, ExistingWorkPolicy.REPLACE, request)
    }

    /** チャージ後の残高確認の保険。 */
    fun scheduleVerification() {
        val request = OneTimeWorkRequestBuilder<VerifyChargeWorker>()
            .setInitialDelay(VERIFY_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(WORK_VERIFY, ExistingWorkPolicy.REPLACE, request)
    }

    /** Debug 画面に出す WorkManager の状態。 */
    fun observePeriodicState(): Flow<String> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_PERIODIC_CHECK).map { infos ->
            when (val info: WorkInfo? = infos.firstOrNull()) {
                null -> "未登録"
                else -> "${info.state} (試行 ${info.runAttemptCount} 回)"
            }
        }

    companion object {
        const val WORK_PERIODIC_CHECK = "icoca_periodic_check"
        const val WORK_ONE_SHOT_CHECK = "icoca_one_shot_check"
        const val WORK_VERIFY = "icoca_verify_charge"

        private const val FLEX_MINUTES = 30L
        private const val VERIFY_DELAY_MINUTES = 5L
    }
}
