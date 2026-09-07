package io.github.nobu0601.icocaautocharge.data.db

import androidx.room.TypeConverter
import io.github.nobu0601.icocaautocharge.domain.AutomationMethod
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType
import io.github.nobu0601.icocaautocharge.domain.ChargeStatus
import io.github.nobu0601.icocaautocharge.domain.ErrorReason

/**
 * 列挙は名前で保存する。序数で保存すると、後から列挙値を挿入したときに
 * 既存の履歴の意味が変わってしまうため。
 */
class Converters {
    @TypeConverter fun statusToString(v: ChargeStatus?): String? = v?.name

    @TypeConverter
    fun stringToStatus(v: String?): ChargeStatus? =
        v?.let { runCatching { ChargeStatus.valueOf(it) }.getOrNull() }

    @TypeConverter fun errorToString(v: ErrorReason?): String? = v?.name

    @TypeConverter
    fun stringToError(v: String?): ErrorReason? =
        v?.let { runCatching { ErrorReason.valueOf(it) }.getOrNull() }

    @TypeConverter fun methodToString(v: AutomationMethod?): String? = v?.name

    @TypeConverter
    fun stringToMethod(v: String?): AutomationMethod? =
        v?.let { runCatching { AutomationMethod.valueOf(it) }.getOrNull() }

    @TypeConverter fun sourceToString(v: BalanceSourceType?): String? = v?.name

    @TypeConverter
    fun stringToSource(v: String?): BalanceSourceType? =
        v?.let { runCatching { BalanceSourceType.valueOf(it) }.getOrNull() }
}
