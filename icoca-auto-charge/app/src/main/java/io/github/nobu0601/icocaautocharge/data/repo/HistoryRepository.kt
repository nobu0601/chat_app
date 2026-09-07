package io.github.nobu0601.icocaautocharge.data.repo

import io.github.nobu0601.icocaautocharge.data.db.BalanceSampleDao
import io.github.nobu0601.icocaautocharge.data.db.BalanceSampleEntity
import io.github.nobu0601.icocaautocharge.data.db.ChargeHistoryDao
import io.github.nobu0601.icocaautocharge.data.db.ChargeHistoryEntity
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.LimitCalculator
import kotlinx.coroutines.flow.Flow

/** 履歴と残高サンプルの読み書き、および上限の集計。 */
class HistoryRepository(
    private val historyDao: ChargeHistoryDao,
    private val sampleDao: BalanceSampleDao,
) {

    fun observeHistory(): Flow<List<ChargeHistoryEntity>> = historyDao.observeRecent()

    suspend fun insert(entity: ChargeHistoryEntity): Long = historyDao.insert(entity)

    suspend fun update(entity: ChargeHistoryEntity) = historyDao.update(entity)

    suspend fun byId(id: Long): ChargeHistoryEntity? = historyDao.byId(id)

    suspend fun latest(): ChargeHistoryEntity? = historyDao.latest()

    suspend fun clearHistory() = historyDao.clear()

    /** 日本時間の「今日」のチャージ済み合計（指示書 §22）。 */
    suspend fun chargedTodayYen(nowMillis: Long): Int {
        val r = LimitCalculator.dayRange(nowMillis)
        return historyDao.sumChargedBetween(r.startMillis, r.endMillis)
    }

    /** 日本時間の「今月」のチャージ済み合計。 */
    suspend fun chargedThisMonthYen(nowMillis: Long): Int {
        val r = LimitCalculator.monthRange(nowMillis)
        return historyDao.sumChargedBetween(r.startMillis, r.endMillis)
    }

    suspend fun recordBalance(reading: BalanceReading) {
        sampleDao.insert(
            BalanceSampleEntity(
                observedAt = reading.observedAt,
                balanceYen = reading.balanceYen,
                source = reading.source,
            ),
        )
    }

    /** 保持期間を過ぎた残高サンプルを掃除する。 */
    suspend fun pruneSamples(nowMillis: Long) {
        sampleDao.deleteOlderThan(nowMillis - SAMPLE_RETENTION_MILLIS)
    }

    companion object {
        private const val SAMPLE_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1000
    }
}
