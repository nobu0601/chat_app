package io.github.nobu0601.icocaautocharge.core

/**
 * 画面上の文字列を比較できる形に揃える。
 *
 * ICOCA アプリの金額表記は「5,000円」「¥5,000」「５，０００円」のように揺れうるため、
 * 比較の前に必ずここを通す。
 *
 * 文字列だけを扱う純粋関数なので、Android に依存せず Unit Test で検証できる。
 */
object TextNormalizer {

    /** 全角・半角、空白、通貨記号のゆれを吸収する。 */
    fun normalize(s: String): String =
        s.trim()
            .replace('￥', '¥')
            .replace('，', ',')
            .replace(Regex("[\\s　]+"), "")
            .map(::toHalfWidthDigit)
            .joinToString("")

    private fun toHalfWidthDigit(c: Char): Char =
        if (c in '０'..'９') ('0' + (c - '０')) else c
}
