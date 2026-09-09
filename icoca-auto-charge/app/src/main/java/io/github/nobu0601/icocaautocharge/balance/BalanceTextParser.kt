package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.core.IcocaConstants

/**
 * 画面上のテキストから残高を取り出す（ARCHITECTURE §6）。
 *
 * **誤検知が最も危険**な部品。実機（Pixel 8a / モバイルICOCA）のメイン画面は、
 * ユーザー補助から次の順でテキストが見える。
 *
 * ```
 * チャージ / 円 / 6,271 / チャージ残額 / 日 / 6 / 月 / 3 / 9月7日〜2027年 / ...
 * ```
 *
 * ここには2つの罠がある。
 *
 * 1. **ラベルが数値の「後ろ」に来る。** 「チャージ残額」は 6,271 の後ろにある。
 *    ラベルの後ろだけを見ていると 6,271 を取り逃す。
 * 2. **定期券の日付が裸の数字として並ぶ。** `日 6 月 3` の `6` や `3` は
 *    ラベルのすぐ後ろにあるので、素朴な実装だと残高 6円 と誤読する。
 *    残高6円は閾値を割るので、**誤ってチャージ処理を起動してしまう。**
 *
 * そこで単純な「ラベルの有無」ではなく、複数の手がかりを重み付けして
 * 最も残高らしい候補を1つ選ぶ方式にしている。
 *
 * Android に依存しない純粋関数。
 */
object BalanceTextParser {

    /** 残高を指し示すラベル。 */
    private val BALANCE_LABELS = listOf("残高", "残額", "ざんだか", "SF残", "チャージ残", "現在の残")

    /**
     * 金額らしき部分。全角の￥・半角の¥・末尾の円 を許容し、桁区切りのカンマを許す。
     * 直前が符号やハイフンの場合は拾わない（負数・電話番号・日付を除外するため）。
     */
    private val AMOUNT = Regex("""(?<![\d\-−▲△.])(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+|\d+)\s*(?:円)?""")

    /** 時刻表記。実機の画面には更新時刻「23:34」が出るので、金額として拾わない。 */
    private val TIME = Regex("""\d{1,2}:\d{2}""")

    /** 通貨の単位。ICOCA アプリは「円」を独立したノードに置いている。 */
    private val CURRENCY = Regex("""[¥￥円]""")
    private val CURRENCY_ONLY = Regex("""^[¥￥円]$""")

    /** そのテキストに残高ラベルが含まれるか。 */
    fun hasBalanceLabel(text: String): Boolean =
        BALANCE_LABELS.any { text.contains(it) }

    /**
     * 単一の文字列から金額を1つ取り出す。文脈は見ない。
     *
     * @return ICOCA の残高としてありえる範囲（0〜20,000円）の値。範囲外・解析不能なら null。
     */
    fun parseAmount(text: String): Int? {
        if (text.isBlank()) return null
        if (TIME.containsMatchIn(text)) return null
        for (m in AMOUNT.findAll(text)) {
            val digits = m.groupValues[1].replace(",", "")
            // 桁が多すぎるものはカード番号・会員番号なので残高候補にしない
            if (digits.length > 6) continue
            val value = digits.toLongOrNull() ?: continue
            if (value in 0..IcocaConstants.CARD_BALANCE_CAP_YEN.toLong()) {
                return value.toInt()
            }
        }
        return null
    }

    /**
     * 画面上のテキストを出現順に並べたリストから残高を推定する。
     *
     * 各候補に点数をつけ、最も高いものを採る。同点なら先に出てきたものを採る。
     *
     * @param preferLabeled true（既定）のとき、[MIN_SCORE] に届かない候補は採用しない。
     *   裏付けの弱い数値で自動チャージが走る方が、残高不明で止まるより危険なため。
     */
    fun extractBalanceFromLines(lines: List<String>, preferLabeled: Boolean = true): Int? {
        val scored = lines.mapIndexedNotNull { index, line ->
            parseAmount(line)?.let { yen -> yen to score(lines, index, line, yen) }
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        if (preferLabeled && best.second < MIN_SCORE) return null
        return best.first
    }

    /**
     * 残高らしさの点数。
     *
     * 実機で観測した並び（`円 / 6,271 / チャージ残額 / 日 / 6`）で
     * 6,271 が 6 に勝つように重みを決めている。
     */
    private fun score(lines: List<String>, index: Int, text: String, yen: Int): Int {
        var score = 0

        // ラベルの裏付けが決定的。これ単独で [MIN_SCORE] に届くようにしてある。
        // 通貨記号や桁区切りは「金額である」ことしか示さず、
        // チャージ金額ボタンの「5,000円」も同じ特徴を持つので、
        // それらを足し合わせただけでラベル付きの候補に勝ってはいけない。
        if (hasBalanceLabel(text)) {
            score += 4
        } else if (nearby(lines, index, LABEL_DISTANCE).any { hasBalanceLabel(it) }) {
            // ラベルは前後どちらにもありうる。実機では数値の「後ろ」にある
            score += 3
        }

        // 通貨単位が同じノードか、隣のノードにある
        if (CURRENCY.containsMatchIn(text)) {
            score += 1
        } else if (nearby(lines, index, 1).any { CURRENCY_ONLY.matches(it.trim()) }) {
            score += 1
        }

        // 桁区切りがあるものは金額として書かれている
        if (text.contains(',') || text.contains('，')) score += 1

        // 3桁未満の裸の数字は、日付・件数・順位である可能性の方が高い
        if (yen < SMALL_NUMBER_THRESHOLD) score -= 2

        return score
    }

    private fun nearby(lines: List<String>, index: Int, distance: Int): List<String> =
        (index - distance..index + distance)
            .filter { it != index && it in lines.indices }
            .map { lines[it] }

    /** ラベルと数値が離れていてよい行数。 */
    private const val LABEL_DISTANCE = 2

    /** これを下回る候補は残高として採用しない。 */
    private const val MIN_SCORE = 3

    private const val SMALL_NUMBER_THRESHOLD = 100
}
