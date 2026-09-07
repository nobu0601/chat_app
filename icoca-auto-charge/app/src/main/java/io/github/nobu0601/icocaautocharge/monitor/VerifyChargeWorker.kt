package io.github.nobu0601.icocaautocharge.monitor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.nobu0601.icocaautocharge.IcocaApp
import io.github.nobu0601.icocaautocharge.core.SecureLog

/**
 * チャージ後の残高確認を、少し遅らせて行う保険。
 *
 * [ChargeFlowService] が何らかの理由で途中終了しても、
 * 試行が CHARGING のまま取り残されないようにする。
 */
class VerifyChargeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val locator = IcocaApp.locator ?: return Result.retry()
        return try {
            locator.coordinator.verifyNow()
            Result.success()
        } catch (e: Exception) {
            SecureLog.e("verify worker failed", e)
            Result.failure()
        }
    }
}
