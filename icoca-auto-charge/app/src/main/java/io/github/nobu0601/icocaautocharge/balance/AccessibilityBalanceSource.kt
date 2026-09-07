package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.accessibility.AccessibilityBridge
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType

/**
 * ユーザー補助サービスが ICOCA アプリの画面から読み取った残高。
 *
 * **能動的に取りに行くことはできない。** ICOCA アプリが前面に来たときに
 * サービスが読み取った値を、ここでは受け取るだけ。
 * バックグラウンドで ICOCA アプリを勝手に開いて読む、といったことはしない
 * （BAL 制限にも、ユーザーの意図にも反するため）。
 */
class AccessibilityBalanceSource : BalanceSource {

    override val type = BalanceSourceType.ACCESSIBILITY

    override val description: String = "ICOCAアプリの画面から読み取り"

    override suspend fun isAvailable(): Boolean = AccessibilityBridge.isRunning()

    override suspend fun read(): BalanceReading? = AccessibilityBridge.lastBalance.value
}
