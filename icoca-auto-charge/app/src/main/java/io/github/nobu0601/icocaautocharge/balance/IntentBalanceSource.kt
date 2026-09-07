package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType
import io.github.nobu0601.icocaautocharge.icoca.IcocaAppProbe

/**
 * 公式 Intent / Deep Link から残高を取得する手段。
 *
 * **現時点では実装できない。** JR西日本は残高を返す公開 Intent を提供していない
 * （PROJECT_RESEARCH §1.5, §1.6）。
 *
 * このクラスは「推測で実装しない」ための意図的な空実装であり、同時に拡張点でもある。
 * `TECHNICAL_FEASIBILITY.md §3.2` の実機調査で公開 Intent が見つかった場合、
 * ここに実装を足せば自動的にチェーンの先頭で使われるようになる。
 */
class IntentBalanceSource(private val probe: IcocaAppProbe) : BalanceSource {

    override val type = BalanceSourceType.INTENT

    override val description: String =
        "公式Intent（未提供のため利用不可）"

    override suspend fun isAvailable(): Boolean = false

    override suspend fun read(): BalanceReading? {
        // 実機調査の結果をログに残すだけ。値は返さない。
        val schemes = runCatching { probe.probeUrlSchemes() }.getOrDefault(emptyList())
        if (schemes.isNotEmpty()) {
            SecureLog.i(
                SecureLog.Tag.BALANCE,
                "ICOCA app resolves url schemes: $schemes — docs/TECHNICAL_FEASIBILITY.md を更新すること",
            )
        }
        return null
    }
}
