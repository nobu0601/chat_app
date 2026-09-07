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
 */
class ManualBalanceSource(private val flowState: FlowStateRepository) : BalanceSource {

    override val type = BalanceSourceType.MANUAL

    override val description: String = "手入力"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun read(): BalanceReading? {
        val last = flowState.currentLastBalance() ?: return null
        // 手入力・疑似値以外は他のソースが返すべきなので、ここでは扱わない
        return if (last.source == BalanceSourceType.MANUAL || last.source == BalanceSourceType.SIMULATED) {
            last
        } else {
            null
        }
    }
}
