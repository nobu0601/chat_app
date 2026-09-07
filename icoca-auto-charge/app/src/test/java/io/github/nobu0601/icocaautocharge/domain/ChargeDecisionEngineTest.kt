package io.github.nobu0601.icocaautocharge.domain

import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.data.settings.AppSettings
import java.time.LocalDateTime
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST_PLAN.md §1.1 に対応。 */
class ChargeDecisionEngineTest {

    /** 判定が時刻に依存するため、メンテナンス時間帯を避けた固定時刻を基準にする。 */
    private val now = jst(2026, 9, 7, 12, 0)

    private fun jst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), IcocaConstants.ZONE_JST)
            .toInstant().toEpochMilli()

    private fun input(
        balanceYen: Int? = 2_500,
        balanceAt: Long = now,
        settings: AppSettings = AppSettings(),
        attempt: ChargeAttempt? = null,
        lastTerminalAt: Long? = null,
        today: Int = 0,
        month: Int = 0,
        unmetered: Boolean = true,
        charging: Boolean = true,
        nowMillis: Long = now,
    ) = ChargeDecisionEngine.Input(
        settings = settings,
        balance = balanceYen?.let { BalanceReading(it, balanceAt, BalanceSourceType.MANUAL) },
        nowMillis = nowMillis,
        currentAttempt = attempt,
        lastTerminalAtMillis = lastTerminalAt,
        chargedTodayYen = today,
        chargedThisMonthYen = month,
        isUnmeteredNetwork = unmetered,
        isCharging = charging,
    )

    private fun skipReason(d: ChargeDecision): SkipReason? = (d as? ChargeDecision.Skip)?.reason

    @Test
    fun `残高2999は閾値3000を下回るので発火する`() {
        val d = ChargeDecisionEngine.decide(input(balanceYen = 2_999))
        assertTrue(d is ChargeDecision.Proceed)
        assertEquals(5_000, (d as ChargeDecision.Proceed).amountYen)
    }

    @Test
    fun `残高がちょうど閾値のときは発火しない`() {
        assertEquals(SkipReason.ABOVE_THRESHOLD, skipReason(ChargeDecisionEngine.decide(input(3_000))))
    }

    @Test
    fun `残高が閾値を超えていれば発火しない`() {
        assertEquals(SkipReason.ABOVE_THRESHOLD, skipReason(ChargeDecisionEngine.decide(input(3_001))))
    }

    @Test
    fun `残高0でも発火する`() {
        assertTrue(ChargeDecisionEngine.decide(input(0)) is ChargeDecision.Proceed)
    }

    @Test
    fun `監視がOFFなら発火しない`() {
        val d = ChargeDecisionEngine.decide(
            input(settings = AppSettings(monitoringEnabled = false)),
        )
        assertEquals(SkipReason.MONITORING_DISABLED, skipReason(d))
    }

    @Test
    fun `残高が不明なら発火しない`() {
        assertEquals(SkipReason.BALANCE_UNKNOWN, skipReason(ChargeDecisionEngine.decide(input(null))))
    }

    @Test
    fun `残高が古すぎるなら発火しない`() {
        val stale = now - 25 * 3_600_000L
        assertEquals(
            SkipReason.BALANCE_UNKNOWN,
            skipReason(ChargeDecisionEngine.decide(input(balanceAt = stale))),
        )
    }

    @Test
    fun `試行が進行中なら発火しない`() {
        val attempt = ChargeAttempt(
            historyId = 1, state = ChargeState.CHARGING, startedAt = now, updatedAt = now,
            balanceAtDetection = 2_500, threshold = 3_000, chargeAmount = 5_000,
        )
        assertEquals(
            SkipReason.ATTEMPT_IN_PROGRESS,
            skipReason(ChargeDecisionEngine.decide(input(attempt = attempt))),
        )
    }

    @Test
    fun `クールダウン中は発火しない`() {
        val d = ChargeDecisionEngine.decide(input(lastTerminalAt = now - 5 * 3_600_000L))
        assertEquals(SkipReason.COOLDOWN, skipReason(d))
    }

    @Test
    fun `クールダウンを過ぎたら発火する`() {
        val d = ChargeDecisionEngine.decide(input(lastTerminalAt = now - 7 * 3_600_000L))
        assertTrue(d is ChargeDecision.Proceed)
    }

    @Test
    fun `日次上限ちょうどまでは発火する`() {
        val d = ChargeDecisionEngine.decide(input(today = 5_000))
        assertTrue(d is ChargeDecision.Proceed)
    }

    @Test
    fun `日次上限を超えるなら発火しない`() {
        assertEquals(SkipReason.DAILY_LIMIT, skipReason(ChargeDecisionEngine.decide(input(today = 10_000))))
    }

    @Test
    fun `月次上限を超えるなら発火しない`() {
        val d = ChargeDecisionEngine.decide(input(month = 30_000, today = 0))
        assertEquals(SkipReason.MONTHLY_LIMIT, skipReason(d))
    }

    @Test
    fun `チャージするとICOCAの残高上限を超えるなら発火しない`() {
        // 16,000 + 5,000 = 21,000 > 20,000
        val settings = AppSettings(thresholdYen = 18_000)
        val d = ChargeDecisionEngine.decide(input(balanceYen = 16_000, settings = settings))
        assertEquals(SkipReason.WOULD_EXCEED_CARD_CAP, skipReason(d))
    }

    @Test
    fun `チャージ金額が1回上限を超えるなら発火しない`() {
        val settings = AppSettings(chargeAmountYen = 25_000, dailyLimitYen = 30_000)
        val d = ChargeDecisionEngine.decide(input(settings = settings))
        assertEquals(SkipReason.EXCEEDS_PER_CHARGE_CAP, skipReason(d))
    }

    @Test
    fun `メンテナンス時間帯は発火しない`() {
        val d = ChargeDecisionEngine.decide(
            input(nowMillis = jst(2026, 9, 7, 3, 0), balanceAt = jst(2026, 9, 7, 3, 0)),
        )
        assertEquals(SkipReason.MAINTENANCE_WINDOW, skipReason(d))
    }

    @Test
    fun `メンテナンス時間帯の境界の外では発火する`() {
        val before = jst(2026, 9, 7, 1, 59)
        val after = jst(2026, 9, 7, 4, 1)
        assertTrue(
            ChargeDecisionEngine.decide(input(nowMillis = before, balanceAt = before))
                is ChargeDecision.Proceed,
        )
        assertTrue(
            ChargeDecisionEngine.decide(input(nowMillis = after, balanceAt = after))
                is ChargeDecision.Proceed,
        )
    }

    @Test
    fun `WiFi必須なのにモバイル回線なら発火しない`() {
        val d = ChargeDecisionEngine.decide(
            input(settings = AppSettings(wifiOnly = true), unmetered = false),
        )
        assertEquals(SkipReason.REQUIRES_WIFI, skipReason(d))
    }

    @Test
    fun `充電中必須なのに充電していないなら発火しない`() {
        val d = ChargeDecisionEngine.decide(
            input(settings = AppSettings(chargingOnly = true), charging = false),
        )
        assertEquals(SkipReason.REQUIRES_CHARGING, skipReason(d))
    }
}
