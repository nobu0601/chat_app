package io.github.nobu0601.icocaautocharge.icoca

import android.content.Context
import android.content.Intent
import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.domain.ErrorReason

/**
 * モバイルICOCA アプリの起動。
 *
 * **公開されているランチャー Intent しか使わない。**
 * 非公開の内部 Activity を名指しで起動すれば「チャージ画面へ直行」できる可能性はあるが、
 * それは相手アプリが想定していない使い方であり、指示書 §2（規約に抵触する可能性が高い方法）に該当する。
 *
 * また、**バックグラウンドから呼んではならない**（Android の BAL 制限。PROJECT_RESEARCH §2.6）。
 * 呼び出し元は必ず前面の Activity であること。
 */
class IcocaLauncher(private val context: Context) {

    sealed interface Result {
        data object Launched : Result
        data class Failed(val reason: ErrorReason) : Result
    }

    fun launchMain(): Result {
        val intent = context.packageManager
            .getLaunchIntentForPackage(IcocaConstants.PACKAGE_NAME)
            ?: run {
                SecureLog.w(SecureLog.Tag.AUTOMATION, "ICOCA app launch intent not found")
                return Result.Failed(ErrorReason.ICOCA_APP_NOT_FOUND)
            }
        // 既存タスクがあればそれを前面に出す。新しいタスクを積み増さない。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            SecureLog.i(SecureLog.Tag.AUTOMATION, "launched ICOCA app")
            Result.Launched
        } catch (e: Exception) {
            SecureLog.e("failed to launch ICOCA app", e)
            Result.Failed(ErrorReason.ICOCA_APP_LAUNCH_FAILED)
        }
    }

    fun isInstalled(): Boolean =
        context.packageManager.getLaunchIntentForPackage(IcocaConstants.PACKAGE_NAME) != null
}
