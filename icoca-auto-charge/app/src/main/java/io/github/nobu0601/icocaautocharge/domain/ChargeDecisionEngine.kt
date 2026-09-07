package io.github.nobu0601.icocaautocharge.domain

import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.data.settings.AppSettings

/**
 * 「いまチャージ処理を起動してよいか」を判定する唯一の場所（ARCHITECTURE §4）。
 *
 * Android に依存しない純粋関数。判定順は固定で、
 * **最初に引っかかった理由をそのまま返す**（Debug 画面でそのまま表示できる）。
 */
object ChargeDecisionEngine {

    data class Input(
        val settings: AppSettings,
        val balance: BalanceReading?,
        val nowMillis: Long,
        val currentAttempt: ChargeAttempt?,
        /** 直近で終端状態になった時刻。クールダウンの起点。 */
        val lastTerminalAtMillis: Long?,
        val chargedTodayYen: Int,
        val chargedThisMonthYen: Int,
        val isUnmeteredNetwork: Boolean,
        val isCharging: Boolean,
    )

    fun decide(input: Input): ChargeDecision {
        val s = input.settings

        if (!s.monitoringEnabled) return ChargeDecision.Skip(SkipReason.MONITORING_DISABLED)

        val balance = input.balance
            ?: return ChargeDecision.Skip(SkipReason.BALANCE_UNKNOWN)
        if (!balance.isPlausible) return ChargeDecision.Skip(SkipReason.BALANCE_UNKNOWN)
        if (balance.isStale(input.nowMillis, s.balanceMaxAgeMillis)) {
            return ChargeDecision.Skip(SkipReason.BALANCE_UNKNOWN)
        }

        // 「未満」で発火する。ちょうど閾値のときは発火しない（指示書 §6）。
        if (balance.balanceYen >= s.thresholdYen) {
            return ChargeDecision.Skip(SkipReason.ABOVE_THRESHOLD)
        }

        val attempt = input.currentAttempt
        if (attempt != null && attempt.state.isActive) {
            return ChargeDecision.Skip(SkipReason.ATTEMPT_IN_PROGRESS)
        }

        val lastTerminal = input.lastTerminalAtMillis
        if (lastTerminal != null &&
            input.nowMillis - lastTerminal < s.minChargeIntervalMillis
        ) {
            return ChargeDecision.Skip(SkipReason.COOLDOWN)
        }

        val amount = s.chargeAmountYen
        if (amount > IcocaConstants.PER_CHARGE_CAP_YEN) {
            return ChargeDecision.Skip(SkipReason.EXCEEDS_PER_CHARGE_CAP)
        }
        if (input.chargedTodayYen + amount > s.dailyLimitYen) {
            return ChargeDecision.Skip(SkipReason.DAILY_LIMIT)
        }
        if (input.chargedThisMonthYen + amount > s.monthlyLimitYen) {
            return ChargeDecision.Skip(SkipReason.MONTHLY_LIMIT)
        }
        if (balance.balanceYen + amount > IcocaConstants.CARD_BALANCE_CAP_YEN) {
            return ChargeDecision.Skip(SkipReason.WOULD_EXCEED_CARD_CAP)
        }

        if (LimitCalculator.isMaintenanceWindow(input.nowMillis)) {
            return ChargeDecision.Skip(SkipReason.MAINTENANCE_WINDOW)
        }
        if (s.wifiOnly && !input.isUnmeteredNetwork) {
            return ChargeDecision.Skip(SkipReason.REQUIRES_WIFI)
        }
        if (s.chargingOnly && !input.isCharging) {
            return ChargeDecision.Skip(SkipReason.REQUIRES_CHARGING)
        }

        return ChargeDecision.Proceed(amountYen = amount, balanceYen = balance.balanceYen)
    }
}

sealed interface ChargeDecision {
    data class Proceed(val amountYen: Int, val balanceYen: Int) : ChargeDecision
    data class Skip(val reason: SkipReason) : ChargeDecision
}

enum class SkipReason(val message: String) {
    MONITORING_DISABLED("自動監視がOFFです"),
    BALANCE_UNKNOWN("残高が取得できていません（または古すぎます）"),
    ABOVE_THRESHOLD("残高が設定額以上です"),
    ATTEMPT_IN_PROGRESS("チャージ処理が進行中です"),
    COOLDOWN("最低チャージ間隔の待機中です"),
    DAILY_LIMIT("1日のチャージ上限に達しています"),
    MONTHLY_LIMIT("月間のチャージ上限に達しています"),
    WOULD_EXCEED_CARD_CAP("チャージするとICOCAの残高上限（20,000円）を超えます"),
    EXCEEDS_PER_CHARGE_CAP("チャージ金額がICOCAの1回上限（20,000円）を超えています"),
    MAINTENANCE_WINDOW("ICOCAのメンテナンス時間帯（2:00〜4:00）です"),
    REQUIRES_WIFI("Wi-Fi接続時のみに設定されています"),
    REQUIRES_CHARGING("充電中のみに設定されています"),
}
