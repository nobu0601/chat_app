package io.github.nobu0601.icocaautocharge.domain

import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import java.time.LocalDateTime
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST_PLAN.md §1.4 に対応。日本時間での集計が要点。 */
class LimitCalculatorTest {

    private fun jst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), IcocaConstants.ZONE_JST)
            .toInstant().toEpochMilli()

    private fun rec(millis: Long, yen: Int, status: ChargeStatus = ChargeStatus.SUCCESS) =
        LimitCalculator.Record(millis, yen, status)

    @Test
    fun `日本時間の同じ日は同じ範囲に入る`() {
        val now = jst(2026, 9, 7, 12, 0)
        val range = LimitCalculator.dayRange(now)
        val records = listOf(
            rec(jst(2026, 9, 7, 0, 5), 1_000),
            rec(jst(2026, 9, 7, 23, 55), 2_000),
        )
        assertEquals(3_000, LimitCalculator.sumInRange(records, range))
    }

    @Test
    fun `日付をまたぐと別の日として扱う`() {
        val now = jst(2026, 9, 7, 12, 0)
        val range = LimitCalculator.dayRange(now)
        val records = listOf(
            rec(jst(2026, 9, 6, 23, 55), 1_000),
            rec(jst(2026, 9, 8, 0, 5), 2_000),
        )
        assertEquals(0, LimitCalculator.sumInRange(records, range))
    }

    @Test
    fun `UTCの日付境界に影響されない`() {
        // 日本時間 9月7日 8:00 は UTC では 9月6日 23:00。
        // UTC で日を切ると前日扱いになってしまうが、JST 基準なら同じ日。
        val now = jst(2026, 9, 7, 20, 0)
        val range = LimitCalculator.dayRange(now)
        assertEquals(5_000, LimitCalculator.sumInRange(listOf(rec(jst(2026, 9, 7, 8, 0), 5_000)), range))
    }

    @Test
    fun `月をまたぐと別の月として扱う`() {
        val now = jst(2026, 9, 15, 12, 0)
        val range = LimitCalculator.monthRange(now)
        val records = listOf(
            rec(jst(2026, 8, 31, 23, 59), 1_000),
            rec(jst(2026, 9, 1, 0, 1), 2_000),
            rec(jst(2026, 10, 1, 0, 1), 4_000),
        )
        assertEquals(2_000, LimitCalculator.sumInRange(records, range))
    }

    @Test
    fun `失敗した履歴は集計に含めない`() {
        val now = jst(2026, 9, 7, 12, 0)
        val range = LimitCalculator.dayRange(now)
        val records = listOf(
            rec(now, 5_000, ChargeStatus.FAILED),
            rec(now, 5_000, ChargeStatus.CANCELLED),
        )
        assertEquals(0, LimitCalculator.sumInRange(records, range))
    }

    @Test
    fun `裏取りできていない成功も安全側で集計に含める`() {
        val now = jst(2026, 9, 7, 12, 0)
        val range = LimitCalculator.dayRange(now)
        val records = listOf(rec(now, 5_000, ChargeStatus.SUCCESS_UNVERIFIED))
        assertEquals(5_000, LimitCalculator.sumInRange(records, range))
    }

    @Test
    fun `メンテナンス時間帯を境界込みで判定する`() {
        assertFalse(LimitCalculator.isMaintenanceWindow(jst(2026, 9, 7, 1, 59)))
        assertTrue(LimitCalculator.isMaintenanceWindow(jst(2026, 9, 7, 2, 0)))
        assertTrue(LimitCalculator.isMaintenanceWindow(jst(2026, 9, 7, 3, 59)))
        assertFalse(LimitCalculator.isMaintenanceWindow(jst(2026, 9, 7, 4, 0)))
    }

    @Test
    fun `日本時間の同じ日かを判定できる`() {
        // 1日1回の残高確認は、この判定で「今日もう見たか」を決める。
        val jstMorning = zonedJst(2026, 9, 27, 8, 0)
        val jstNight = zonedJst(2026, 9, 27, 23, 30)
        assertTrue(LimitCalculator.isSameJstDay(jstMorning, jstNight))
    }

    @Test
    fun `日をまたげば別の日として扱う`() {
        val before = zonedJst(2026, 9, 27, 23, 59)
        val after = zonedJst(2026, 9, 28, 0, 1)
        assertFalse(LimitCalculator.isSameJstDay(before, after))
    }

    @Test
    fun `UTCで日をまたいでも日本時間で同じ日なら同じ日`() {
        // 日本時間 8:00 は UTC では前日 23:00。UTC で数えると
        // 朝の確認が前日扱いになり、1日に2回開いてしまう。
        val jstMorning = zonedJst(2026, 9, 27, 8, 0)
        val jstNoon = zonedJst(2026, 9, 27, 12, 0)
        assertTrue(LimitCalculator.isSameJstDay(jstMorning, jstNoon))
    }

    private fun zonedJst(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        java.time.ZonedDateTime.of(
            y, m, d, h, min, 0, 0,
            io.github.nobu0601.icocaautocharge.core.IcocaConstants.ZONE_JST,
        ).toInstant().toEpochMilli()
}
