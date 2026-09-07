package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType

/**
 * 残高の取得手段。優先順位順に試す（指示書 §4, ARCHITECTURE §5）。
 */
interface BalanceSource {

    val type: BalanceSourceType

    /** 現在この手段が使える状態か（権限・アプリの有無など）。 */
    suspend fun isAvailable(): Boolean

    /**
     * 残高を読む。取得できなければ null を返す。
     * **推測値を返してはいけない。** 分からないときは必ず null。
     */
    suspend fun read(): BalanceReading?

    /** ユーザーに見せる、この手段の説明。 */
    val description: String
}
