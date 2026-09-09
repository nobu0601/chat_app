package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.data.settings.FlowStateRepository
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType

/**
 * ユーザーが手入力した残高（指示書 §27 の Fallback）。
 *
 * 自動取得がすべて失敗しても、この手段があるのでアプリは成立する。
 * ただし手入力値は乗車のたびに古くなるため、
 * [io.github.nobu0601.icocaautocharge.data.settings.AppSettings.balanceMaxAgeHours] を超えたら
 * 判定側で「不明」として扱われる。
 *
 * Debug の疑似値はここでは扱わない。[SimulatedBalanceSource] が別枠で持ち、
 * 使ったら消える1回限りの値として扱う（テスト値がチャージ後の確認まで
 * 残ってしまわないようにするため）。
 */
class ManualBalanceSource(private val flowState: FlowStateRepository) : BalanceSource {

    override val type = BalanceSourceType.MANUAL

    override val description: String = "手入力"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun read(): BalanceReading? {
        val last = flowState.currentLastBalance() ?: return null
        return if (last.source == BalanceSourceType.MANUAL) last else null
    }
}
