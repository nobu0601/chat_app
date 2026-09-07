package io.github.nobu0601.icocaautocharge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST_PLAN.md §1.2 に対応。二重チャージ防止の中核なので厚めに検証する。 */
class ChargeStateMachineTest {

    private val sm = ChargeStateMachine()
    private val t0 = 1_700_000_000_000L

    private fun detect(current: ChargeAttempt? = null, balance: Int? = 2_500) =
        sm.detect(current, balance, threshold = 3_000, chargeAmount = 5_000, nowMillis = t0)

    private fun accepted(r: ChargeStateMachine.Result): ChargeAttempt =
        (r as ChargeStateMachine.Result.Accepted).attempt

    @Test
    fun `正常な一連の遷移がすべて受理される`() {
        var a = accepted(detect())
        assertEquals(ChargeState.CHARGE_DETECTED, a.state)

        a = accepted(sm.transition(a, ChargeState.CHARGE_PENDING, t0 + 1))
        a = accepted(sm.transition(a, ChargeState.CHARGING, t0 + 2))
        a = accepted(sm.transition(a, ChargeState.VERIFYING, t0 + 3))
        a = accepted(sm.transition(a, ChargeState.SUCCESS, t0 + 4))
        assertEquals(ChargeState.SUCCESS, a.state)

        a = accepted(sm.transition(a, ChargeState.IDLE, t0 + 5))
        assertEquals(ChargeState.IDLE, a.state)
    }

    @Test
    fun `段階を飛ばす遷移は拒否される`() {
        val a = accepted(detect())
        val idle = a.copy(state = ChargeState.IDLE)
        val r = sm.transition(idle, ChargeState.CHARGING, t0 + 1)
        assertTrue(r is ChargeStateMachine.Result.Rejected)
    }

    @Test
    fun `終端から処理中へは戻れない`() {
        val done = accepted(detect()).copy(state = ChargeState.SUCCESS)
        assertTrue(sm.transition(done, ChargeState.CHARGING, t0 + 1) is ChargeStateMachine.Result.Rejected)
    }

    @Test
    fun `FAILEDへの遷移は理由が必須`() {
        val a = accepted(detect())
        val r = sm.transition(a, ChargeState.FAILED, t0 + 1, errorReason = null)
        assertTrue(r is ChargeStateMachine.Result.Rejected)

        val ok = sm.transition(a, ChargeState.FAILED, t0 + 1, errorReason = ErrorReason.TIMEOUT)
        assertTrue(ok is ChargeStateMachine.Result.Accepted)
    }

    @Test
    fun `処理中に再検知しても新しい試行を作らない`() {
        val active = accepted(detect()).copy(state = ChargeState.CHARGING)
        val r = detect(current = active, balance = 2_400)
        assertTrue(r is ChargeStateMachine.Result.Rejected)
    }

    @Test
    fun `同じ残高を再観測しただけでは新しい試行を作らない`() {
        // 終端していないが isActive でもない、という状態を作れないため
        // CHARGE_DETECTED（進行中）で弾かれることを確認する
        val existing = accepted(detect(balance = 2_500))
        val r = detect(current = existing, balance = 2_500)
        assertTrue(r is ChargeStateMachine.Result.Rejected)
    }

    @Test
    fun `IDLEに戻っていても同じ残高なら新しい試行を作らない`() {
        // クールダウン明けに IDLE へ戻したあと、乗車していないため残高が同じ、という状況。
        // ここで発火すると同じ低残高に対して二重にチャージしてしまう。
        val idle = accepted(detect(balance = 2_500)).copy(state = ChargeState.IDLE)
        val r = detect(current = idle, balance = 2_500)
        assertTrue(r is ChargeStateMachine.Result.Rejected)
    }

    @Test
    fun `終端後は残高が変われば新しい試行を作れる`() {
        val done = accepted(detect(balance = 2_500))
            .copy(state = ChargeState.FAILED, errorReason = ErrorReason.CANCELLED_BY_USER)
        val r = detect(current = done, balance = 2_400)
        assertTrue(r is ChargeStateMachine.Result.Accepted)
    }

    @Test
    fun `放置された試行はタイムアウトで終端に落ちる`() {
        val pending = accepted(detect()).copy(state = ChargeState.CHARGE_PENDING)
        val stillFresh = sm.timeoutIfStale(pending, t0 + 60_000)
        assertNull(stillFresh)

        val expired = sm.timeoutIfStale(
            pending,
            t0 + ChargeStateMachine.ATTEMPT_TIMEOUT_MILLIS + 1,
        )
        val a = accepted(expired!!)
        assertEquals(ChargeState.FAILED, a.state)
        assertEquals(ErrorReason.TIMEOUT, a.errorReason)
    }

    @Test
    fun `終端状態はタイムアウトの対象にならない`() {
        val done = accepted(detect()).copy(state = ChargeState.SUCCESS)
        assertNull(sm.timeoutIfStale(done, t0 + ChargeStateMachine.ATTEMPT_TIMEOUT_MILLIS + 1))
    }
}
