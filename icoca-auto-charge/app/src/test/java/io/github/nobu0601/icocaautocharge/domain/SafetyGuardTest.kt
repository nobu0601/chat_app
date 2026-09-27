package io.github.nobu0601.icocaautocharge.domain

import io.github.nobu0601.icocaautocharge.accessibility.IcocaScreen
import io.github.nobu0601.icocaautocharge.accessibility.SafetyGuard
import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 指示書 §11 の安全条件が本当に効いているかを検証する。 */
class SafetyGuardTest {

    private val guard = SafetyGuard(expectedSignature = "AA:BB")

    private fun ctx(
        pkg: String? = IcocaConstants.PACKAGE_NAME,
        signature: String? = "AA:BB",
        screen: IcocaScreen = IcocaScreen.CHARGE_AMOUNT,
        steps: Int = 1,
    ) = SafetyGuard.Context(pkg, signature, screen, steps)

    @Test
    fun `対象アプリが違えば停止する`() {
        val v = guard.check(ctx(pkg = "com.example.fake"))
        assertEquals(ErrorReason.PACKAGE_MISMATCH, (v as SafetyGuard.Verdict.Stop).reason)
    }

    @Test
    fun `署名が違えば停止する`() {
        val v = guard.check(ctx(signature = "CC:DD"))
        assertEquals(ErrorReason.SIGNATURE_MISMATCH, (v as SafetyGuard.Verdict.Stop).reason)
    }

    @Test
    fun `手数の上限を超えたら停止する`() {
        val v = guard.check(ctx(steps = SafetyGuard.MAX_STEPS + 1))
        assertEquals(ErrorReason.STEP_LIMIT_EXCEEDED, (v as SafetyGuard.Verdict.Stop).reason)
    }

    @Test
    fun `画面が進まないことだけでは停止しない`() {
        // 以前は「同じ画面のまま15秒」で止めていたが、画面が変わらないこと自体は
        // 異常ではない（決済確認は人が読む画面だし、通信待ちも動かない）。
        // 待ちの上限は AutomationTimeouts が状態ごとに持つ（改修指示 §8, §9）。
        // このクラスはもう時間を見ない。
        val v = guard.check(ctx(screen = IcocaScreen.CHARGE_AMOUNT))
        assertEquals(SafetyGuard.Verdict.Proceed, v)
    }

    @Test
    fun `認証画面では必ず停止する`() {
        val v = guard.check(ctx(screen = IcocaScreen.AUTHENTICATION))
        assertEquals(ErrorReason.AUTHENTICATION_REQUIRED, (v as SafetyGuard.Verdict.Stop).reason)
    }

    @Test
    fun `決済確認画面ではユーザーに引き渡して終了する`() {
        val v = guard.check(ctx(screen = IcocaScreen.PAYMENT_CONFIRM))
        assertTrue(v is SafetyGuard.Verdict.Finish)
    }

    @Test
    fun `自動確定を有効にすると決済確認画面でも続行する`() {
        val autoGuard = SafetyGuard(expectedSignature = "AA:BB", autoConfirmPayment = true)
        val v = autoGuard.check(ctx(screen = IcocaScreen.PAYMENT_CONFIRM))
        assertTrue(v is SafetyGuard.Verdict.Proceed)
    }

    @Test
    fun `自動確定を有効にしても認証画面では必ず停止する`() {
        // ここが緩んだら、本人認証を自動で突破していることになる。
        // 設定でどう指定されても、この振る舞いは変わってはいけない。
        val autoGuard = SafetyGuard(expectedSignature = "AA:BB", autoConfirmPayment = true)
        val v = autoGuard.check(ctx(screen = IcocaScreen.AUTHENTICATION))
        assertEquals(ErrorReason.AUTHENTICATION_REQUIRED, (v as SafetyGuard.Verdict.Stop).reason)
    }

    @Test
    fun `自動確定を有効にしてもエラー画面では停止する`() {
        val autoGuard = SafetyGuard(expectedSignature = "AA:BB", autoConfirmPayment = true)
        assertTrue(autoGuard.check(ctx(screen = IcocaScreen.ERROR)) is SafetyGuard.Verdict.Stop)
    }

    @Test
    fun `想定外の画面では押さないが中止もしない`() {
        // ここの本題は「押さない」こと。
        // どれだけ待つかは時計を持つ AutomationEngine の担当で、
        // このクラスは時間も回数も数えない（改修指示 §7, §8）。
        val autoGuard = SafetyGuard(expectedSignature = "AA:BB", autoConfirmPayment = true)
        val v = autoGuard.check(ctx(screen = IcocaScreen.UNKNOWN))
        assertEquals(SafetyGuard.Verdict.Wait, v)
    }

    @Test
    fun `決済画面に設定額が出ていることを確認できる`() {
        val screen = listOf("チャージ内容", "5,000円", "決済する")
        assertTrue(guard.isAmountVisibleOnScreen(screen, 5_000))
    }

    @Test
    fun `決済画面に設定額が無ければ確定させない`() {
        // 金額が違う、あるいはどこにも出ていないなら、それは想定した決済ではない
        assertFalse(guard.isAmountVisibleOnScreen(listOf("チャージ内容", "10,000円", "決済する"), 5_000))
        assertFalse(guard.isAmountVisibleOnScreen(listOf("チャージ内容", "決済する"), 5_000))
        assertFalse(guard.isAmountVisibleOnScreen(emptyList(), 5_000))
    }

    @Test
    fun `金額選択画面では続行できる`() {
        assertTrue(guard.check(ctx()) is SafetyGuard.Verdict.Proceed)
    }

    @Test
    fun `設定した金額と一致するラベルだけを受け入れる`() {
        assertTrue(guard.verifyAmountLabel("5,000円", 5_000))
        assertTrue(guard.verifyAmountLabel("¥5,000", 5_000))
        assertTrue(guard.verifyAmountLabel("5000", 5_000))
        assertTrue(guard.verifyAmountLabel("５，０００円", 5_000))

        assertFalse(guard.verifyAmountLabel("15,000円", 5_000))
        assertFalse(guard.verifyAmountLabel("10,000円", 5_000))
        assertFalse(guard.verifyAmountLabel(null, 5_000))
        assertFalse(guard.verifyAmountLabel("チャージ", 5_000))
    }
}
