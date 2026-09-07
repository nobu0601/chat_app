package io.github.nobu0601.icocaautocharge.domain

import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 上限管理（指示書 §22）の日付計算。
 *
 * 「1日」「1か月」は **日本時間のカレンダー**で数える。
 * UTC で数えると日本時間 8:00 のチャージが前日扱いになり、上限が壊れる。
 */
object LimitCalculator {

    data class Range(val startMillis: Long, val endMillis: Long)

    /** now を含む「日本時間のその日」の範囲 [start, end)。 */
    fun dayRange(nowMillis: Long): Range {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), IcocaConstants.ZONE_JST)
        val start = now.toLocalDate().atStartOfDay(IcocaConstants.ZONE_JST)
        return Range(
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = start.plusDays(1).toInstant().toEpochMilli(),
        )
    }

    /** now を含む「日本時間のその月」の範囲 [start, end)。 */
    fun monthRange(nowMillis: Long): Range {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), IcocaConstants.ZONE_JST)
        val start = now.toLocalDate().withDayOfMonth(1).atStartOfDay(IcocaConstants.ZONE_JST)
        return Range(
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = start.plusMonths(1).toInstant().toEpochMilli(),
        )
    }

    /** 集計対象の履歴。DB のエンティティに依存させないための最小の形。 */
    data class Record(val timestampMillis: Long, val chargeAmountYen: Int, val status: ChargeStatus)

    /** 範囲内かつ集計対象ステータスのものだけを合計する。 */
    fun sumInRange(records: List<Record>, range: Range): Int =
        records.filter {
            it.status.countsTowardLimits &&
                it.timestampMillis >= range.startMillis &&
                it.timestampMillis < range.endMillis
        }.sumOf { it.chargeAmountYen }

    /** チャージ不可のメンテナンス時間帯（日本時間 2:00〜4:00）か。 */
    fun isMaintenanceWindow(nowMillis: Long): Boolean {
        val jst = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), IcocaConstants.ZONE_JST)
        val t = jst.toLocalTime()
        val start = LocalTime.of(IcocaConstants.MAINTENANCE_START_HOUR_JST, 0)
        val end = LocalTime.of(IcocaConstants.MAINTENANCE_END_HOUR_JST, 0)
        return !t.isBefore(start) && t.isBefore(end)
    }
}
