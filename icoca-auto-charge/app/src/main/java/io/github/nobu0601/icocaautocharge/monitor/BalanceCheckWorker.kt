package io.github.nobu0601.icocaautocharge.monitor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.nobu0601.icocaautocharge.IcocaApp
import io.github.nobu0601.icocaautocharge.core.SecureLog

/**
 * 定期的な残高チェック（指示書 §19）。
 *
 * **この Worker から ICOCA アプリを起動することはない。**
 * バックグラウンドからの Activity 起動は Android にブロックされるため、
 * ここでは判定と通知までを行い、起動はユーザーのタップに委ねる（ARCHITECTURE §8）。
 */
class BalanceCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val locator = IcocaApp.locator ?: return Result.retry()
        return try {
            val trigger = inputData.getString(KEY_TRIGGER) ?: "periodic"
            val result = locator.coordinator.runCheck(trigger)
            if (result.autoStarted) {
                // 通知を挟まずに ICOCA を開いた場合、見届ける役がいない。
                // フォアグラウンドサービスはバックグラウンドからは起動できないため、
                // この Worker がそのまま最後まで面倒を見る。
                // 待ち時間の合計は Worker の実行上限（約10分）に収まる。
                locator.coordinator.superviseAndVerify(automation = true)
            }
            Result.success()
        } catch (e: Exception) {
            SecureLog.e("balance check worker failed", e)
            // 一時的な失敗（DB ロックなど）はリトライする価値がある
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        private const val MAX_ATTEMPTS = 3
    }
}
