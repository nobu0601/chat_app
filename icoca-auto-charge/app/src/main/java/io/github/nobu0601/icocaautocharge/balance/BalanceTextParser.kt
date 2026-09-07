package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.core.IcocaConstants

/**
 * 画面上のテキストから残高を取り出す（ARCHITECTURE §6）。
 *
 * **誤検知が最も危険**な部品。チャージ金額ボタンの「5,000円」を残高と読み違えると
 * 判定がまるごと壊れるため、既定では「残高ラベルの近くにある数値」しか採用しない。
 *
 * Android に依存しない純粋関数。
 */
object BalanceTextParser {

    /** 残高を指し示すラベル。これが近くにある数値だけを残高候補にする。 */
    private val BALANCE_LABELS = listOf("残高", "残額", "ざんだか", "SF残", "チャージ残", "現在の残")

    /**
     * 金額らしき部分。全角の￥・半角の¥・末尾の円 を許容し、桁区切りのカンマを許す。
     * 直前が符号やハイフンの場合は拾わない（負数・電話番号・日付を除外するため）。
     */
    private val AMOUNT = Regex("""(?<![\d\-−▲△.])(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+|\d+)\s*(?:円)?""")

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

    /** 画面から集めた1ノード分のテキストと、その周辺に残高ラベルがあったか。 */
    data class Candidate(val text: String, val labeled: Boolean)

    /**
     * 候補群から残高を決める。
     *
     * @param preferLabeled true（既定）のとき、ラベルの裏付けが無い数値は採用しない。
     *   これによりチャージ金額ボタンや運賃の誤認を防ぐ。
     */
    fun extractBalance(candidates: List<Candidate>, preferLabeled: Boolean = true): Int? {
        val parsed = candidates.mapNotNull { c ->
            parseAmount(c.text)?.let { it to c.labeled }
        }
        if (parsed.isEmpty()) return null
        val labeled = parsed.filter { it.second }
        if (labeled.isNotEmpty()) return labeled.first().first
        return if (preferLabeled) null else parsed.first().first
    }

    /**
     * 画面上のテキストを出現順に並べたリストから残高を推定する。
     *
     * ICOCA アプリのように「残高」ラベルと数値が別ノードに分かれている場合に備え、
     * ラベル行の直後 [LABEL_LOOKAHEAD] 行までをラベル付き候補として扱う。
     */
    fun extractBalanceFromLines(lines: List<String>, preferLabeled: Boolean = true): Int? {
        var labelCarry = 0
        val candidates = lines.map { line ->
            val selfLabeled = hasBalanceLabel(line)
            val labeled = selfLabeled || labelCarry > 0
            labelCarry = when {
                selfLabeled -> LABEL_LOOKAHEAD
                labelCarry > 0 -> labelCarry - 1
                else -> 0
            }
            Candidate(line, labeled)
        }
        return extractBalance(candidates, preferLabeled)
    }

    private const val LABEL_LOOKAHEAD = 2
}
