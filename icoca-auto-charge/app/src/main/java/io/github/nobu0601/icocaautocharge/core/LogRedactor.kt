package io.github.nobu0601.icocaautocharge.core

/**
 * ログに出す前に、機密になりうる文字列をマスクする（指示書 §24）。
 *
 * Android に依存しない純粋関数なので JVM の Unit Test で検証できる。
 *
 * 方針は「疑わしきはマスクする」。ただし残高（3〜5桁）まで潰すと
 * ログが役に立たなくなるため、桁数と文脈で区別している。
 */
object LogRedactor {

    private const val MASK = "****"

    /**
     * 12桁以上の連続した数字。カード番号（14〜16桁）や会員番号を想定。
     * 途中の半角スペース・ハイフンは区切りとして許容する。
     * 残高は最大 20,000（5桁）なので、この閾値に触れることはない。
     */
    private val LONG_DIGIT_RUN = Regex("""\d(?:[ -]?\d){11,}""")

    /**
     * すでにアプリ側でマスクされたカード番号（「****9804」など）。
     *
     * 下4桁だけでも [LONG_DIGIT_RUN] の閾値には届かないので素通りしてしまう。
     * 実機の支払いボタンのラベルが「****9804でチャージ」であり、
     * このラベルはログにも診断表示にも載りうるため、ここで潰しておく。
     * 金額は「5,000」のように伏字を伴わないので巻き添えにならない。
     */
    private val MASKED_PAN = Regex("""[*＊]{2,}[ -]?\d{2,6}""")

    /**
     * 「キーワード + 区切り + 値」の形。値だけをマスクする。
     * 長い候補を先に並べて、部分一致で短い方に食われないようにしている。
     */
    private val SENSITIVE_KEY_VALUE = Regex(
        """(?i)(ワンタイムパスワード|パスワード|認証コード|暗証番号|セキュリティコード|暗証|password|passwd|security[ _-]?code|passcode|pincode|token|secret|cvv|cvc|otp|pin|pass)""" +
            """([\s:=＝：]+)(\S+)""",
    )

    fun redact(message: String): String {
        if (message.isEmpty()) return message
        var out = SENSITIVE_KEY_VALUE.replace(message) { m ->
            m.groupValues[1] + m.groupValues[2] + MASK
        }
        out = LONG_DIGIT_RUN.replace(out, MASK)
        out = MASKED_PAN.replace(out, MASK)
        return out
    }
}
