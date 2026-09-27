package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.domain.ErrorReason

/**
 * 自動操作1回分の状態（改修指示 §2）。
 *
 * **エンジンが所有する可変の状態であって、永続化はしない。**
 * 永続化された試行（`ChargeAttempt` / `ChargeState`）とは別物で、
 * そちらは `ChargeFlowCoordinator` が持つ。二重チャージ防止はあちらの責任。
 *
 * 以前はこのクラスが「イベントが来たら1歩進む」ための小さな箱だったが、
 * イベントが来ないと永久に止まる原因そのものだった。
 * いまはポーリングするエンジンの作業領域であり、
 * **「いつから何を待っているか」を時刻で持つ**のが要点。
 */
class AutomationSession(
    val sessionId: String,
    val attemptId: Long,
    val startedAt: Long,
    val chargeAmountYen: Int,
    /** true のとき、押す予定を記録するだけでクリックしない（Debug 用）。 */
    val dryRun: Boolean,
    /**
     * 決済の確定まで自動で押すか。
     * 本人認証の画面は、この値に関係なく必ず停止する。
     */
    val autoConfirmPayment: Boolean,
) {
    var state: AutomationState = AutomationState.LAUNCHING_ICOCA
        private set

    var status: AutomationStatus = AutomationStatus.RUNNING
        private set

    var currentScreen: IcocaScreen = IcocaScreen.UNKNOWN
        private set

    /**
     * 進んだ手数。無限ループ防止の上限判定（[SafetyGuard.MAX_STEPS]）に使う。
     *
     * **数えるのはクリックであって、状態遷移ではない。**
     * ポーリングにしたことで状態は細かく動くようになったので、
     * 遷移を数えると同じ画面を見ているだけで上限に達してしまう。
     */
    var currentStep: Int = 0
        private set

    var lastAction: AutomationAction? = null
        private set

    var lastActionAt: Long = 0L
        private set

    /**
     * 直前の `performAction` の戻り値。
     *
     * **true でも「チャージ操作が成功した」ではない。**
     * アクセシビリティのアクションが受け付けられたというだけで、
     * 画面が進んだかどうかは次の周期で取り直して確かめる。
     */
    var lastActionResult: Boolean? = null
        private set

    /** 直前に掴んだノードの情報。Debug 画面に出して、外した時に追えるようにする。 */
    var lastMatch: ClickTarget? = null
        private set

    /** 画面種別が最後に変わった時刻。 */
    var lastScreenChangeAt: Long = startedAt
        private set

    /** いまの [state] に入った時刻。state ごとの timeout はここから測る。 */
    var waitingSince: Long = startedAt
        private set

    /** 判別できない画面になった時刻。既知画面に戻ったら null に戻す。 */
    var unknownSince: Long? = null
        private set

    /** 判別できない画面が続いた回数。診断表示にのみ使う（停止条件ではない）。 */
    var consecutiveUnknown: Int = 0
        private set

    /** [AutomationStatus.RECOVERING] に入った時刻。 */
    var recoveringSince: Long? = null
        private set

    /** 金額の選択を済ませたか。押しても見た目が変わらない画面があるので旗で持つ。 */
    var amountSelected: Boolean = false
        private set

    /** ドライラン時に「押す予定だったもの」を残す。 */
    val plannedClicks = mutableListOf<String>()

    var outcome: Outcome? = null
        private set

    sealed interface Outcome {
        /** 本人認証など、ユーザーにしかできない操作に到達した。**失敗ではない。** */
        data class HandedToUser(val screen: IcocaScreen, val message: String) : Outcome

        /** 完了画面を確認できた。 */
        data object Completed : Outcome

        data class Stopped(val reason: ErrorReason, val message: String) : Outcome
    }

    val isFinished: Boolean get() = outcome != null

    /** いまの状態にとどまった時間。 */
    fun millisInState(nowMillis: Long): Long = nowMillis - waitingSince

    /** 判別できない画面が続いている時間。既知画面なら 0。 */
    fun unknownDurationMillis(nowMillis: Long): Long =
        unknownSince?.let { nowMillis - it } ?: 0L

    fun millisSinceStart(nowMillis: Long): Long = nowMillis - startedAt

    /** 状態を移す。同じ状態への遷移では時計を巻き戻さない。 */
    fun moveTo(next: AutomationState, nowMillis: Long) {
        if (next == state) return
        state = next
        waitingSince = nowMillis
    }

    fun setStatus(next: AutomationStatus, nowMillis: Long) {
        if (next == status) return
        status = next
        recoveringSince = if (next == AutomationStatus.RECOVERING) nowMillis else null
    }

    /** 画面を観測した。判別できない画面が続いているかをここで数える。 */
    fun onScreenObserved(screen: IcocaScreen, nowMillis: Long) {
        if (screen != currentScreen) {
            currentScreen = screen
            lastScreenChangeAt = nowMillis
        }
        if (screen == IcocaScreen.UNKNOWN) {
            if (unknownSince == null) unknownSince = nowMillis
            consecutiveUnknown++
        } else {
            unknownSince = null
            consecutiveUnknown = 0
        }
    }

    fun onAction(action: AutomationAction, nowMillis: Long, result: Boolean) {
        lastAction = action
        lastActionAt = nowMillis
        lastActionResult = result
        // **押せなかった操作は手数に数えない。**
        // 手数の上限は「無限ループで画面を進め続けない」ための歯止めであって、
        // 押し直しの回数を制限するためのものではない。
        // ここで数えていたせいで、再試行が12回で打ち切られ、
        // 状態ごとの timeout に届く前に STEP_LIMIT_EXCEEDED になっていた。
        if (result) currentStep++
    }

    /** 押す相手を決めた。押す前に呼ぶので、押せなかった場合も何を掴んだかが残る。 */
    fun onMatch(target: ClickTarget) {
        lastMatch = target
    }

    fun markAmountSelected() {
        amountSelected = true
    }

    fun finish(outcome: Outcome) {
        if (this.outcome != null) return
        this.outcome = outcome
        status = when (outcome) {
            is Outcome.Completed -> AutomationStatus.SUCCESS
            is Outcome.HandedToUser -> AutomationStatus.USER_ACTION_REQUIRED
            is Outcome.Stopped -> AutomationStatus.FAILED
        }
        state = when (outcome) {
            is Outcome.Completed -> AutomationState.COMPLETED
            is Outcome.HandedToUser -> AutomationState.USER_ACTION_REQUIRED
            is Outcome.Stopped -> AutomationState.FAILED
        }
    }
}
