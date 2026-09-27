package io.github.nobu0601.icocaautocharge.accessibility

/**
 * 同じボタンの連打を防ぐ（改修指示 §6）。
 *
 * エンジンは 300ms ごとに画面を見るが、**見るたびに押してよいわけではない。**
 * 画面が変わらないまま同じボタンが見え続けるのは普通のことなので、
 * 素直に実装すると1秒に3回同じボタンを叩くことになる。
 * 決済が絡む画面でそれをやるのは論外。
 *
 * 2段構えで止める。
 *
 *  1. **同一ステップでの再クリック禁止** — 一度押したノードは、
 *     状態が進むまで二度と押さない。これが主たる歯止め。
 *  2. **クールダウン** — 状態が進んで同じノードに戻ってきた場合でも、
 *     [COOLDOWN_MILLIS] 未満の間隔では押さない。
 *
 * 同一性は「画面種別 + ノード識別子 + 操作種別」の3つ組で見る。
 * ノード識別子だけだと、別の画面にたまたま同じラベルのボタンがあったときに
 * 押せなくなってしまう。
 */
class ActionThrottle(private val cooldownMillis: Long = COOLDOWN_MILLIS) {

    private data class Key(
        val screen: IcocaScreen,
        val nodeKey: String,
        val action: AutomationAction,
    )

    private val lastClickedAt = HashMap<Key, Long>()

    /** いまの状態で既に押したもの。状態が進むと捨てる。 */
    private val clickedInCurrentStep = HashSet<Key>()

    /**
     * いま押してよいか。
     *
     * 押してよい場合は「押した」として記録するので、
     * **許可されたら必ず押すこと。** 判定だけして押さないと、次の機会を1回失う。
     */
    fun allow(
        screen: IcocaScreen,
        nodeKey: String,
        action: AutomationAction,
        nowMillis: Long,
    ): Boolean {
        val key = Key(screen, nodeKey, action)
        if (key in clickedInCurrentStep) return false
        val last = lastClickedAt[key]
        if (last != null && nowMillis - last < cooldownMillis) return false
        lastClickedAt[key] = nowMillis
        clickedInCurrentStep += key
        return true
    }

    /** 状態が進んだ。ステップ内の記録だけ捨てる（クールダウンは持ち越す）。 */
    fun onStepAdvanced() {
        clickedInCurrentStep.clear()
    }

    /**
     * 押したが `performAction` が false だった。
     *
     * 「このステップでは押し済み」の印だけ外し、もう一度試せるようにする。
     * クールダウンは残すので、連打にはならない（最短 [COOLDOWN_MILLIS] 間隔）。
     * 実機では、画面外にあるノードやツリー作り直し直後のノードで false が返る。
     * 1回の false で諦めると、スクロールやツリー更新を挟めば押せたものまで落とす。
     */
    fun onClickFailed(screen: IcocaScreen, nodeKey: String, action: AutomationAction) {
        clickedInCurrentStep.remove(Key(screen, nodeKey, action))
    }

    companion object {
        /** 同じボタンを押し直すまでの最短間隔。 */
        const val COOLDOWN_MILLIS = 800L
    }
}
