package io.github.nobu0601.icocaautocharge.accessibility

/**
 * 状態ごとの待ち時間の上限（改修指示 §9）。
 *
 * ### なぜ「同一画面15秒」をやめたのか
 *
 * 以前は「同じ画面のまま15秒進展しなければ UI が変わった」と決めつけて停止していた。
 * だが画面が変わらないこと自体は異常ではない。
 *
 *  - 決済の確認ダイアログは、ユーザーが読んで判断する間ずっと同じ画面
 *  - 通信待ちの「処理中」も同じ画面のまま
 *  - 金額選択画面で金額を押しても、すでにその額なら見た目は変わらない
 *
 * どれも正常系なのに、一律15秒で切っていた。
 * 代わりに「いま何を待っているのか」ごとに上限を決める。
 * ボタンが出るのを待つ20秒と、人が決済を判断する60秒は、同じ尺度で測れない。
 *
 * **設定値にはしない。** ユーザーが伸ばせてしまうと、
 * UI が変わって二度と進めない状態を延々と待ち続けることになる。
 */
object AutomationTimeouts {

    /** 画面を見に行く周期。これ自体はクリック頻度ではない（[ActionThrottle] が別に効く）。 */
    const val POLL_INTERVAL_MILLIS = 300L

    /**
     * 判別できない画面を待つ時間（改修指示 §7）。
     *
     * 画面遷移の途中に中身の無いツリーが挟まるのは正常。回数ではなく時間で測る。
     * 実機では1〜2回（0.5秒未満）で終わるので、20秒あれば遷移の揺らぎは十分吸収できる。
     */
    const val UNKNOWN_WAIT_MILLIS = 20_000L

    /**
     * [AutomationStatus.RECOVERING] にいられる時間。
     *
     * Recovery は現在画面を見直すだけなので、長く粘っても意味がない。
     * ここを超えたら本当に知らない画面にいる。
     */
    const val RECOVERY_MILLIS = 15_000L

    /** セッション全体の上限。個別の timeout をすべてすり抜けた場合の最後の歯止め。 */
    const val SESSION_MILLIS = 5 * 60 * 1000L

    /**
     * 状態ごとの上限。ここに無い状態は [SESSION_MILLIS] だけが効く。
     *
     * 値の根拠:
     *  - 「〜を待つ」系が20秒 — 実機の画面遷移は1秒前後。通信が挟まっても20秒あれば足りる
     *  - `SELECTING_AMOUNT` が5秒 — 押した直後に次を探すだけなので長い必要がない
     *  - `PAYMENT_CONFIRM` が60秒 — **人が読んで判断する画面**。急かしてはいけない
     *  - `PROCESSING` / `WAITING_FOR_COMPLETION` が120秒 — 決済の通信待ち。
     *    ここで切ると「実際は成功しているのに失敗扱い」になりかねないので長めに取る
     */
    private val BY_STATE: Map<AutomationState, Long> = mapOf(
        AutomationState.LAUNCHING_ICOCA to 15_000L,
        AutomationState.WAITING_FOR_MAIN to 20_000L,
        AutomationState.WAITING_FOR_CHARGE_ENTRY to 20_000L,
        AutomationState.WAITING_FOR_AMOUNT to 20_000L,
        AutomationState.SELECTING_AMOUNT to 5_000L,
        AutomationState.WAITING_FOR_PAYMENT_BUTTON to 20_000L,
        AutomationState.PAYMENT_CONFIRM to 60_000L,
        AutomationState.PROCESSING to 120_000L,
        AutomationState.WAITING_FOR_COMPLETION to 120_000L,
    )

    /** その状態にとどまってよい時間。上限を設けない状態は null。 */
    fun forState(state: AutomationState): Long? = BY_STATE[state]
}
