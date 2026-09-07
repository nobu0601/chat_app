package io.github.nobu0601.icocaautocharge.domain

import io.github.nobu0601.icocaautocharge.core.IcocaConstants

/**
 * 「いつ・どの手段で・いくらだったか」をひとまとめにした残高。
 *
 * 残高は乗車すれば減るため、値だけでは判断材料にならない。
 * 鮮度 [observedAt] を必ず持たせ、古すぎる値で自動チャージを起動しないようにする。
 */
data class BalanceReading(
    val balanceYen: Int,
    val observedAt: Long,
    val source: BalanceSourceType,
) {
    init {
        require(balanceYen >= 0) { "balanceYen must not be negative" }
    }

    fun isStale(nowMillis: Long, maxAgeMillis: Long): Boolean =
        nowMillis - observedAt > maxAgeMillis

    /** ICOCA の残高としてありえる範囲か。範囲外なら読み取りを誤っている。 */
    val isPlausible: Boolean
        get() = balanceYen in 0..IcocaConstants.CARD_BALANCE_CAP_YEN

    companion object {
        /** 既定の最大鮮度: 24時間。 */
        const val DEFAULT_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}
