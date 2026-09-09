package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.balance.BalanceTextParser

/** ICOCA アプリのどの画面にいるかの推定結果。 */
enum class IcocaScreen {
    /** 残高が見えているメイン画面。 */
    MAIN,

    /** チャージの入口（チャージ方法の選択など）。 */
    CHARGE_ENTRY,

    /** チャージ金額を選ぶ画面。 */
    CHARGE_AMOUNT,

    /** 決済の最終確認。**ここから先は自動操作しない。** */
    PAYMENT_CONFIRM,

    /** 3Dセキュア・生体認証・パスワード入力。**絶対に自動化しない。** */
    AUTHENTICATION,

    /** 処理中のローディング。 */
    PROCESSING,

    /** チャージ完了。 */
    COMPLETED,

    /** エラー表示。 */
    ERROR,

    /** 分類できない。安全のため操作を止める。 */
    UNKNOWN,
    ;

    /**
     * この画面を見たら自動操作を止めるべきか（指示書 §11）。
     *
     * PAYMENT_CONFIRM は「エラーだから止める」のではなく
     * 「決済はユーザーの意思で行う」ために止める。
     */
    val requiresStop: Boolean
        get() = this == AUTHENTICATION || this == ERROR ||
            this == UNKNOWN || this == PAYMENT_CONFIRM
}

/**
 * 画面上のテキストから画面種別を推定する（ARCHITECTURE §7）。
 *
 * 座標や画面構造ではなく **テキスト** だけを見る。
 * ICOCA アプリのレイアウト変更に対して壊れにくくするため（指示書 §26）。
 *
 * 判定順は固定。**AUTHENTICATION と ERROR を最優先**で判定する。
 * 「認証画面なのにチャージ画面と誤認して操作を続ける」ことが最悪の事故であり、
 * 逆方向の誤り（止まりすぎ）は安全側に倒れるだけだから。
 *
 * Android に依存しない純粋関数。
 */
object ScreenClassifier {

    private val AUTH_KEYWORDS = listOf(
        "3dセキュア", "3-dセキュア", "3Dセキュア", "ワンタイムパスワード", "本人認証",
        "認証", "パスワード", "暗証番号", "生体認証", "指紋", "顔認証",
        "セキュリティコード", "確認コード", "verified by", "パスコード",
    )

    private val ERROR_KEYWORDS = listOf(
        "エラー", "失敗しました", "できませんでした", "問題が発生", "中断されました",
        "通信に失敗", "やり直してください", "利用できません",
    )

    private val COMPLETED_KEYWORDS = listOf(
        "完了しました", "チャージが完了", "チャージしました", "受け付けました",
    )

    /**
     * 決済の最終確認を示す語。
     *
     * 「チャージする」のような、メイン画面のボタンにも出てきうる汎用語は入れない。
     * ここで誤判定すると自動操作が常に止まってしまい、機能が死ぬため。
     */
    private val PAYMENT_KEYWORDS = listOf(
        // 実機の確認ダイアログの見出しと本文（2026-09-09 / Pixel 8a）。
        // 「チャージ確認」「JR西日本へお支払い額：5,000円」「チャージしますか？」
        "チャージ確認", "チャージしますか", "お支払い額",
        "決済", "お支払い", "支払う", "この内容で", "以下の内容で", "内容をご確認",
    )

    private val CHARGE_AMOUNT_KEYWORDS = listOf(
        "チャージ金額", "金額を選択", "チャージする金額", "入金金額",
    )

    private val CHARGE_ENTRY_KEYWORDS = listOf(
        "チャージ方法", "チャージ手段", "支払い方法",
    )

    private val PROCESSING_KEYWORDS = listOf(
        "処理中", "しばらくお待ち", "通信中", "読み込み中",
    )

    /** チャージ額の刻み。ICOCA のチャージは 1,000 円単位。 */
    private const val CHARGE_DENOMINATION_UNIT = 1_000

    /** 金額選択画面とみなすのに必要な、並んでいるチャージ額の数。 */
    private const val MIN_DENOMINATIONS = 3

    fun classify(texts: List<String>): IcocaScreen {
        if (texts.isEmpty()) return IcocaScreen.UNKNOWN
        val joined = texts.joinToString("\n")
        val lower = joined.lowercase()

        // 1. 認証画面が最優先。ここを取り違えると認証の自動突破になりかねない。
        if (AUTH_KEYWORDS.any { lower.contains(it.lowercase()) }) return IcocaScreen.AUTHENTICATION

        // 2. エラーはその次。
        if (ERROR_KEYWORDS.any { joined.contains(it) }) return IcocaScreen.ERROR

        // 3. 完了。
        if (COMPLETED_KEYWORDS.any { joined.contains(it) }) return IcocaScreen.COMPLETED

        // 4. 決済確認。ここに来たら自動操作を終える。
        if (PAYMENT_KEYWORDS.any { joined.contains(it) }) return IcocaScreen.PAYMENT_CONFIRM

        // 5. 金額選択。明示的な見出しか、チャージ額らしい数字が並んでいるか。
        //
        // 「金額としてパースできる数字が3つ以上」では緩すぎる。実機のメイン画面には
        // 定期券の日付（6, 3, 8）が裸の数字として並んでおり、それだけで
        // 金額選択画面と誤判定されていた。チャージ額は 1,000 円単位なので、
        // 「1,000 以上かつ 1,000 の倍数」に絞る。
        val denominationCount = texts.count { text ->
            val yen = BalanceTextParser.parseAmount(text)
            yen != null && yen >= CHARGE_DENOMINATION_UNIT && yen % CHARGE_DENOMINATION_UNIT == 0
        }
        if (CHARGE_AMOUNT_KEYWORDS.any { joined.contains(it) } ||
            denominationCount >= MIN_DENOMINATIONS
        ) {
            return IcocaScreen.CHARGE_AMOUNT
        }

        if (CHARGE_ENTRY_KEYWORDS.any { joined.contains(it) }) return IcocaScreen.CHARGE_ENTRY
        if (PROCESSING_KEYWORDS.any { joined.contains(it) }) return IcocaScreen.PROCESSING

        // 6. 残高が見えていればメイン画面とみなす。
        if (texts.any { BalanceTextParser.hasBalanceLabel(it) }) return IcocaScreen.MAIN

        return IcocaScreen.UNKNOWN
    }
}
