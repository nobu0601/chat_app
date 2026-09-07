package io.github.nobu0601.icocaautocharge.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.nobu0601.icocaautocharge.domain.AutomationMethod
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType
import io.github.nobu0601.icocaautocharge.domain.ChargeStatus
import io.github.nobu0601.icocaautocharge.domain.ErrorReason

/**
 * チャージ処理の履歴（指示書 §13）。
 *
 * **カード情報・認証情報は一切保存しない。** 保存するのは金額・時刻・結果だけ。
 */
@Entity(tableName = "charge_history", indices = [Index("timestamp")])
data class ChargeHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 低残高を検知した時刻。上限の集計にはこの時刻を使う。 */
    val timestamp: Long,
    val completedAt: Long? = null,
    val balanceBefore: Int?,
    val balanceAfter: Int? = null,
    val threshold: Int,
    val chargeAmount: Int,
    val status: ChargeStatus,
    val errorReason: ErrorReason? = null,
    val automationMethod: AutomationMethod = AutomationMethod.NONE,
    val userConfirmed: Boolean = false,
    val balanceSource: BalanceSourceType? = null,
    val note: String? = null,
)

/**
 * 残高の観測値。推移の表示と、「同じ残高を見ているだけ」判定の材料に使う。
 */
@Entity(tableName = "balance_sample", indices = [Index("observedAt")])
data class BalanceSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val observedAt: Long,
    val balanceYen: Int,
    val source: BalanceSourceType,
)
