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
    /**
     * 決済の確定まで自動で押すか。
     * 本人認証の画面は、この値に関係なく必ず停止する。
     */
    val autoConfirmPayment: Boolean = false,
) {
    var stepCount: Int = 0
        private set

    var lastProgressAt: Long = startedAt
        private set

    var lastScreen: IcocaScreen = IcocaScreen.UNKNOWN
        private set

    /**
     * 判別できない画面が続いた回数。
     *
     * ICOCA アプリは画面を切り替える途中で、まだ中身が出来ていないツリーを
     * 一瞬だけ見せる（実機では「更新」と時刻しか無い13ノードの画面が挟まった）。
     * これを1回でも「想定外の画面」として中止すると、チャージ画面へ移る途中で
     * 毎回セッションが死ぬ。かといって無制限に待つと、本当に知らない画面に
     * 迷い込んだときに居座り続けてしまう。そこで回数で区切る。
     *
     * 判別できた画面を1回見れば 0 に戻る。
     */
    var unknownStreak: Int = 0
        private set

    /**
     * 金額選択画面で、設定した金額のボタンを押し終えたか。
     *
     * 実機の金額選択画面は「金額を選ぶ」と「支払いへ進む」が同じ画面にあるため、
     * 同じ画面で2手必要になる。これが無いと金額ボタンを押し続けて先へ進まない。
     */
    var amountSelected: Boolean = false
        private set

    fun markAmountSelected() {
        amountSelected = true
    }

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
        if (screen == IcocaScreen.UNKNOWN) unknownStreak++ else unknownStreak = 0
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
