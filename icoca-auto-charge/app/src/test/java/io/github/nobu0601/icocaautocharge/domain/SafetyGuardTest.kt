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
        sinceProgress: Long = 0,
    ) = SafetyGuard.Context(pkg, signature, screen, steps, sinceProgress)

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
    fun `画面が進まなければ停止する`() {
        val v = guard.check(ctx(sinceProgress = SafetyGuard.STALL_TIMEOUT_MILLIS + 1))
        assertEquals(ErrorReason.UI_STRUCTURE_CHANGED, (v as SafetyGuard.Verdict.Stop).reason)
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
