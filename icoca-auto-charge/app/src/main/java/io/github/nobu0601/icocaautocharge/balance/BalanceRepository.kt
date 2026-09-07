package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.data.repo.HistoryRepository
import io.github.nobu0601.icocaautocharge.data.settings.FlowStateRepository
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType

/**
 * 残高取得のチェーン（ARCHITECTURE §5）。
 *
 * 優先度の高い手段から順に試し、**最初に成功したものを採用**する。
 * どれも失敗したら null を返す。推測値は決して返さない。
 */
class BalanceRepository(
    private val sources: List<BalanceSource>,
    private val flowState: FlowStateRepository,
    private val history: HistoryRepository,
) {

    data class Outcome(
        val reading: BalanceReading?,
        /** 各手段の試行結果。Debug 画面と実機検証のために残す。 */
        val attempts: List<Attempt>,
    ) {
        data class Attempt(val type: BalanceSourceType, val available: Boolean, val gotValue: Boolean)
    }

    /** 優先度順にソートした手段。 */
    private val ordered = sources.sortedBy { it.type.priority }

    suspend fun refresh(): Outcome {
        val attempts = mutableListOf<Outcome.Attempt>()
        var best: BalanceReading? = null

        for (source in ordered) {
            val available = runCatching { source.isAvailable() }
                .onFailure { SecureLog.e("balance source ${source.type} availability check failed", it) }
                .getOrDefault(false)
            if (!available) {
                attempts += Outcome.Attempt(source.type, available = false, gotValue = false)
                continue
            }
            val reading = runCatching { source.read() }
                .onFailure { SecureLog.e("balance source ${source.type} read failed", it) }
                .getOrNull()
            val plausible = reading?.isPlausible == true
            attempts += Outcome.Attempt(source.type, available = true, gotValue = plausible)
            if (plausible && reading != null) {
                best = reading
                break
            }
        }

        if (best != null) {
            SecureLog.i(
                SecureLog.Tag.BALANCE,
                "balance=${best.balanceYen} source=${best.source} observedAt=${best.observedAt}",
            )
            persist(best)
        } else {
            SecureLog.w(SecureLog.Tag.BALANCE, "no balance source produced a value")
        }
        return Outcome(best, attempts)
    }

    /** 手入力・NFC 読み取り・Debug の疑似値をチェーンの外から取り込む。 */
    suspend fun submit(reading: BalanceReading) {
        if (!reading.isPlausible) {
            SecureLog.w(SecureLog.Tag.BALANCE, "rejected implausible balance: ${reading.balanceYen}")
            return
        }
        persist(reading)
    }

    private suspend fun persist(reading: BalanceReading) {
        flowState.saveBalance(reading)
        runCatching { history.recordBalance(reading) }
            .onFailure { SecureLog.e("failed to record balance sample", it) }
    }

    /** どの手段も使えない状態か。UI に「手入力での運用になります」と出すために使う。 */
    suspend fun hasAutomaticSource(): Boolean = ordered.any {
        it.type != BalanceSourceType.MANUAL && runCatching { it.isAvailable() }.getOrDefault(false)
    }
}
