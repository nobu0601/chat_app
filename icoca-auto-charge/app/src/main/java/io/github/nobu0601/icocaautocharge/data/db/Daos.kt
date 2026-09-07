package io.github.nobu0601.icocaautocharge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargeHistoryDao {

    @Insert
    suspend fun insert(entity: ChargeHistoryEntity): Long

    @Update
    suspend fun update(entity: ChargeHistoryEntity)

    @Query("SELECT * FROM charge_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<ChargeHistoryEntity>>

    @Query("SELECT * FROM charge_history WHERE id = :id")
    suspend fun byId(id: Long): ChargeHistoryEntity?

    /**
     * 期間内のチャージ済み合計。**SUCCESS 系だけを数える**（指示書 §22）。
     * 裏取りできていない SUCCESS_UNVERIFIED も安全側で含める。
     */
    @Query(
        """
        SELECT COALESCE(SUM(chargeAmount), 0) FROM charge_history
        WHERE timestamp >= :startMillis AND timestamp < :endMillis
          AND status IN ('SUCCESS', 'SUCCESS_UNVERIFIED')
        """,
    )
    suspend fun sumChargedBetween(startMillis: Long, endMillis: Long): Int

    @Query("SELECT * FROM charge_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): ChargeHistoryEntity?

    @Query("DELETE FROM charge_history")
    suspend fun clear()
}

@Dao
interface BalanceSampleDao {

    @Insert
    suspend fun insert(entity: BalanceSampleEntity): Long

    @Query("SELECT * FROM balance_sample ORDER BY observedAt DESC LIMIT 1")
    suspend fun latest(): BalanceSampleEntity?

    @Query("SELECT * FROM balance_sample ORDER BY observedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<BalanceSampleEntity>>

    /** 保持期間を過ぎたサンプルを消す。アプリ起動時に呼ぶ。 */
    @Query("DELETE FROM balance_sample WHERE observedAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
