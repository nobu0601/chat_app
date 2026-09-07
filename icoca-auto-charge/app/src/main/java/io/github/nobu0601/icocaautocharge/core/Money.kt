package io.github.nobu0601.icocaautocharge.core

/** 円建て金額の表示ヘルパー。ロケールに依らず「¥2,840」の形に揃える。 */
object Money {

    fun format(yen: Int): String {
        val sign = if (yen < 0) "-" else ""
        val digits = kotlin.math.abs(yen).toString()
        val grouped = buildString {
            digits.forEachIndexed { i, c ->
                if (i > 0 && (digits.length - i) % 3 == 0) append(',')
                append(c)
            }
        }
        return "$sign¥$grouped"
    }

    fun formatOrDash(yen: Int?): String = yen?.let { format(it) } ?: "—"
}
