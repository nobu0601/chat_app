package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.domain.ErrorReason

/**
 * 1回の自動操作セッションの状態。
 *
 * セッションはユーザーが「チャージ」を選んだときにだけ始まり、
 * 決済確認・認証・エラー・完了のいずれかで必ず終わる。
 * **セッションが無い間、サービスは画面を読むだけで一切操作しない。**
 */
class AutomationSession(
    val chargeAmountYen: Int,
    val startedAt: Long,
    /** true のとき、押す予定を記録するだけでクリックしない（Debug 用）。 */
    val dryRun: Boolean,
) {
    var stepCount: Int = 0
        private set

    var lastProgressAt: Long = startedAt
        private set

    var lastScreen: IcocaScreen = IcocaScreen.UNKNOWN
        private set

    /** ドライラン時に「押す予定だったもの」を残す。 */
    val plannedClicks = mutableListOf<String>()

    var outcome: Outcome? = null
        private set

    sealed interface Outcome {
        /** 決済確認までたどり着いた。ここから先はユーザーが操作する。 */
        data class HandedToUser(val screen: IcocaScreen) : Outcome

        /** 完了画面を確認できた。 */
        data object Completed : Outcome

        data class Stopped(val reason: ErrorReason, val message: String) : Outcome
    }

    fun onScreen(screen: IcocaScreen, nowMillis: Long) {
        if (screen != lastScreen) {
            lastScreen = screen
            lastProgressAt = nowMillis
        }
    }

    fun onStep(nowMillis: Long) {
        stepCount++
        lastProgressAt = nowMillis
    }

    fun millisSinceProgress(nowMillis: Long): Long = nowMillis - lastProgressAt

    fun finish(outcome: Outcome) {
        if (this.outcome == null) this.outcome = outcome
    }

    val isFinished: Boolean get() = outcome != null
}
