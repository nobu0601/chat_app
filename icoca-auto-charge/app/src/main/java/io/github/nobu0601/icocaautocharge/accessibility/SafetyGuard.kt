package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.core.TextNormalizer
import io.github.nobu0601.icocaautocharge.domain.ErrorReason

/**
 * 自動操作の安全条件（指示書 §11）。
 *
 * **クリックを1回でも実行する前に、必ず [check] を通す。**
 * 1つでも条件を満たさなければ操作を止める。止まりすぎて困ることはあっても、
 * 進みすぎて決済してしまうことは絶対に避けなければならない。
 */
class SafetyGuard(
    /** 初回に記録した ICOCA アプリの署名。null なら署名検査を行わない（初回検出時）。 */
    private val expectedSignature: String?,
) {

    sealed interface Verdict {
        /** 操作を続けてよい。 */
        data object Proceed : Verdict

        /** 安全に完了した（これ以上やることがない）。 */
        data class Finish(val screen: IcocaScreen) : Verdict

        /** 停止する。 */
        data class Stop(val reason: ErrorReason, val message: String) : Verdict
    }

    data class Context(
        val packageName: String?,
        val actualSignature: String?,
        val screen: IcocaScreen,
        val stepCount: Int,
        val millisSinceProgress: Long,
    )

    fun check(ctx: Context): Verdict {
        // 1. 操作対象が本当に ICOCA 公式アプリか
        if (ctx.packageName != IcocaConstants.PACKAGE_NAME) {
            return stop(
                ErrorReason.PACKAGE_MISMATCH,
                "操作対象がモバイルICOCAアプリではありません（${ctx.packageName}）",
            )
        }
        // 2. 署名が初回検出時と同じか（別アプリへの差し替え検知）
        if (expectedSignature != null &&
            ctx.actualSignature != null &&
            expectedSignature != ctx.actualSignature
        ) {
            return stop(
                ErrorReason.SIGNATURE_MISMATCH,
                "モバイルICOCAアプリの署名が変わっています。安全のため自動操作を中止しました",
            )
        }
        // 3. ステップ数の上限（無限ループ防止）
        if (ctx.stepCount > MAX_STEPS) {
            return stop(ErrorReason.STEP_LIMIT_EXCEEDED, "操作の手数が上限に達したため中止しました")
        }
        // 4. 画面が進まない
        if (ctx.millisSinceProgress > STALL_TIMEOUT_MILLIS) {
            return stop(
                ErrorReason.UI_STRUCTURE_CHANGED,
                "ICOCAアプリの画面が変更された可能性があります。自動操作を中止しました",
            )
        }
        // 5. 画面種別ごとの判断
        return when (ctx.screen) {
            IcocaScreen.AUTHENTICATION -> stop(
                ErrorReason.AUTHENTICATION_REQUIRED,
                "本人認証が必要です。ここから先はご自身で操作してください",
            )
            IcocaScreen.ERROR -> stop(
                ErrorReason.PAYMENT_DECLINED,
                "ICOCAアプリがエラーを表示しています",
            )
            IcocaScreen.UNKNOWN -> stop(
                ErrorReason.UNEXPECTED_SCREEN,
                "想定していない画面のため、自動操作を中止しました",
            )
            // 決済確認は「エラー」ではない。ユーザーに委ねて正常終了する。
            IcocaScreen.PAYMENT_CONFIRM -> Verdict.Finish(ctx.screen)
            IcocaScreen.COMPLETED -> Verdict.Finish(ctx.screen)
            IcocaScreen.MAIN,
            IcocaScreen.CHARGE_ENTRY,
            IcocaScreen.CHARGE_AMOUNT,
            IcocaScreen.PROCESSING,
            -> Verdict.Proceed
        }
    }

    /**
     * 押そうとしているノードのテキストが、設定したチャージ金額と一致するか（指示書 §11「金額確認」）。
     *
     * 一致を確認できないボタンは押さない。
     */
    fun verifyAmountLabel(label: String?, expectedYen: Int): Boolean {
        if (label == null) return false
        val normalized = TextNormalizer.normalize(label)
        return amountLabelVariants(expectedYen).any { TextNormalizer.normalize(it) == normalized }
    }

    /** 「5,000円」「¥5,000」「5000円」…といった表記ゆれ。 */
    fun amountLabelVariants(yen: Int): List<String> {
        val grouped = groupDigits(yen)
        return listOf(
            "${grouped}円",
            "¥$grouped",
            "¥${grouped}円",
            grouped,
            "$yen",
            "${yen}円",
            "¥$yen",
        )
    }

    private fun groupDigits(v: Int): String {
        val d = v.toString()
        return buildString {
            d.forEachIndexed { i, c ->
                if (i > 0 && (d.length - i) % 3 == 0) append(',')
                append(c)
            }
        }
    }

    private fun stop(reason: ErrorReason, message: String): Verdict.Stop {
        SecureLog.w(SecureLog.Tag.AUTOMATION, "safety stop: $reason / $message")
        return Verdict.Stop(reason, message)
    }

    companion object {
        /** 1回のフローで許すステップ数。 */
        const val MAX_STEPS = 12

        /** 同じ画面のまま進展しないと判断するまでの時間。 */
        const val STALL_TIMEOUT_MILLIS = 15_000L
    }
}
