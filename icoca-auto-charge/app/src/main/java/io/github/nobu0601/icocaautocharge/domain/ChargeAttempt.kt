package io.github.nobu0601.icocaautocharge.domain

/**
 * 1回のチャージ試行。**同時に存在してよいのは高々1つ**（指示書 §21 の二重チャージ防止）。
 */
data class ChargeAttempt(
    /** 対応する履歴行の id。0 は未採番。 */
    val historyId: Long,
    val state: ChargeState,
    val startedAt: Long,
    val updatedAt: Long,
    /** 検知したときの残高。同じ残高の再観測を弾くために使う。 */
    val balanceAtDetection: Int?,
    val threshold: Int,
    val chargeAmount: Int,
    val automationMethod: AutomationMethod = AutomationMethod.NONE,
    val userConfirmed: Boolean = false,
    val errorReason: ErrorReason? = null,
)

/** チャージ処理の状態（指示書 §21）。 */
enum class ChargeState {
    IDLE,
    CHARGE_DETECTED,
    CHARGE_PENDING,
    CHARGING,
    VERIFYING,
    SUCCESS,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == SUCCESS || this == FAILED

    /** 進行中（新しい試行を始めてはいけない状態）か。 */
    val isActive: Boolean
        get() = this == CHARGE_DETECTED || this == CHARGE_PENDING ||
            this == CHARGING || this == VERIFYING
}
