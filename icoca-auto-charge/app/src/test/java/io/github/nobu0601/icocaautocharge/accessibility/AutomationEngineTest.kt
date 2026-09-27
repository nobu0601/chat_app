package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.domain.ErrorReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 改修指示 §22 の9シナリオ。
 *
 * すべて **実機で実際に踏んだ、または踏みうる停止パターン**を再現している。
 * [AutomationEngine.tick] が時刻を引数で受け取るので、
 * 「20秒経過」も「イベントが1回も来ない」も、待たずに再現できる。
 *
 * 通しの流れは実機でしか確かめられない（docs/TEST_PLAN.md の実機手順）。
 * ここで固定するのは **止まる／止まらないの判断**そのもの。
 */
class AutomationEngineTest {

    private val amount = 5_000

    /** 実機のメイン画面（2026-09-08 採取）に近い形。 */
    private fun mainScreen() = FakeScreen(
        texts = listOf("更新", "23:34", "チャージ", "円", "6,271", "チャージ残額"),
        buttons = mapOf("チャージ" to false),
    )

    /** 「チャージ」を押した直後に一瞬挟まる、中身の無い画面（実機 2026-09-12）。 */
    private fun blankScreen() = FakeScreen(texts = listOf("更新", "11:13"))

    private fun chargeAmountScreen(presetClickable: Boolean = true) = FakeScreen(
        texts = listOf(
            "チャージ", "チャージ残額", "6,271", "円", "チャージ金額", "5,000", "円",
            "1,000", "2,000", "3,000", "5,000", "10,000", "****9804でチャージ",
        ),
        buttons = buildMap {
            // 上部の金額欄。編集可能なので押してはいけない（押すとキーボードが出るだけ）。
            put("5,000 ", true)
            if (presetClickable) put("5,000", false)
            put("****9804でチャージ", false)
        },
    )

    private fun confirmDialog() = FakeScreen(
        texts = listOf(
            "チャージ確認", "JR西日本へお支払い額：5,000円", "チャージ額：5,000円",
            "チャージしますか？", "キャンセル", "チャージする",
        ),
        buttons = mapOf("チャージする" to false, "キャンセル" to false),
    )

    private fun engine(
        recorder: RecordingRecorder = RecordingRecorder(),
        autoConfirm: Boolean = true,
        startAt: Long = 0L,
    ): Pair<AutomationEngine, RecordingRecorder> {
        val session = AutomationSession(
            sessionId = "test",
            attemptId = 1L,
            startedAt = startAt,
            chargeAmountYen = amount,
            dryRun = false,
            autoConfirmPayment = autoConfirm,
        )
        val guard = SafetyGuard(expectedSignature = null, autoConfirmPayment = autoConfirm)
        return AutomationEngine(session, guard, actualSignature = null, recorder = recorder) to recorder
    }

    // ------------------------------------------------------------- Test 1

    @Test
    fun `チャージ押下後にUNKNOWNが数回続いても停止せずCHARGE_ENTRYへ到達する`() {
        val (e, _) = engine()
        var t = 0L

        e.tick(mainScreen().snapshot(t), t)
        assertEquals(AutomationState.WAITING_FOR_CHARGE_ENTRY, e.session.state)

        // 実機で止まっていたのがここ。空の画面が挟まっても中止しない。
        repeat(5) {
            t += 300
            e.tick(blankScreen().snapshot(t), t)
            assertNull("空画面で中止してはいけない", e.session.outcome)
        }

        t += 300
        val amountScreen = chargeAmountScreen()
        e.tick(amountScreen.snapshot(t), t)
        assertNull(e.session.outcome)
        // 金額選択画面まで到達できている
        assertTrue(e.session.state in setOf(AutomationState.SELECTING_AMOUNT, AutomationState.CHARGE_AMOUNT))
    }

    // ------------------------------------------------------------- Test 2

    @Test
    fun `金額を押しても画面イベントが起きなくても支払いボタンへ進む`() {
        val (e, _) = engine()
        var t = 0L
        val screen = chargeAmountScreen()

        // 金額選択画面に着く（イベントは一切来ない前提。ポーリングだけで進む）
        e.tick(screen.snapshot(t), t)
        assertTrue("金額を1回押す", screen.clicks.contains("5,000"))

        // 以降、画面はまったく変わらない。実機ではここでイベントが止まる。
        repeat(6) {
            t += 300
            e.tick(screen.snapshot(t), t)
        }

        assertNull("止まってはいけない", e.session.outcome)
        assertTrue(
            "支払いボタンを見つけて押す",
            screen.clicks.contains("****9804でチャージ"),
        )
    }

    @Test
    fun `金額欄ではなくプリセットのボタンを押す`() {
        val (e, _) = engine()
        val screen = chargeAmountScreen()
        e.tick(screen.snapshot(0), 0)
        // 編集可能な金額欄（"5,000 "）を押すとキーボードが出るだけで先に進まない。
        assertEquals(listOf("5,000"), screen.clicks.take(1))
    }

    @Test
    fun `プリセットが押せなくても設定額が出ていれば進む`() {
        val (e, _) = engine()
        var t = 0L
        val screen = chargeAmountScreen(presetClickable = false)

        repeat(4) {
            e.tick(screen.snapshot(t), t)
            t += 300
        }
        assertNull(e.session.outcome)
        assertTrue(screen.clicks.contains("****9804でチャージ"))
    }

    // ------------------------------------------------------------- Test 3

    @Test
    fun `同じ画面が15秒続いただけでは失敗しない`() {
        // 以前は「同一画面15秒」で一律 UI_STRUCTURE_CHANGED にしていた。
        // 決済確認は人が読む画面なので、15秒で切ってはいけない。
        val (e, _) = engine(autoConfirm = false)
        var t = 0L
        val screen = confirmDialog()

        e.tick(screen.snapshot(t), t)
        // 自動確定 OFF なので即ユーザーへ引き渡す（これは失敗ではない）
        assertTrue(e.session.outcome is AutomationSession.Outcome.HandedToUser)

        // 金額選択画面でも同じことを確かめる（こちらは待ち続ける）
        val (e2, _) = engine()
        val amountScreen = chargeAmountScreen()
        t = 0
        while (t <= 15_000) {
            e2.tick(amountScreen.snapshot(t), t)
            t += 300
        }
        assertNull("15秒経っただけでは失敗にしない", e2.session.outcome)
    }

    // ------------------------------------------------------------- Test 4

    @Test
    fun `決済確認が60秒以内なら待機し続ける`() {
        val (e, _) = engine(autoConfirm = true)
        var t = 0L
        // 確定ボタンが無いダイアログ（ラベルが未知）。押せないので待つことになる。
        val screen = FakeScreen(
            texts = listOf("チャージ確認", "チャージ額：5,000円", "チャージしますか？"),
            buttons = mapOf("キャンセル" to false),
        )
        while (t < 55_000) {
            e.tick(screen.snapshot(t), t)
            t += 300
        }
        assertNull("60秒以内は待つ", e.session.outcome)
        assertTrue("未知のボタンは押さない", screen.clicks.isEmpty())
    }

    // ------------------------------------------------------------- Test 5

    @Test
    fun `認証画面ではユーザーへ引き渡す`() {
        val (e, rec) = engine(autoConfirm = true)
        val screen = FakeScreen(
            texts = listOf("3Dセキュア", "パスワードを入力してください"),
            buttons = mapOf("送信" to false),
        )
        e.tick(screen.snapshot(0), 0)

        val outcome = e.session.outcome
        assertTrue("失敗ではなく引き渡し", outcome is AutomationSession.Outcome.HandedToUser)
        assertEquals(AutomationStatus.USER_ACTION_REQUIRED, e.session.status)
        assertTrue("何も押さない", screen.clicks.isEmpty())
        assertTrue(rec.kinds().contains(AutomationLogKind.USER_ACTION_REQUIRED))
    }

    @Test
    fun `自動確定がONでも認証画面では絶対に止まる`() {
        // ここが緩んだら認証の自動突破になる。設定に関係なく止まること。
        val (e, _) = engine(autoConfirm = true)
        val screen = FakeScreen(
            texts = listOf("本人認証", "ワンタイムパスワード"),
            buttons = mapOf("OK" to false),
        )
        e.tick(screen.snapshot(0), 0)
        assertTrue(e.session.outcome is AutomationSession.Outcome.HandedToUser)
        assertTrue(screen.clicks.isEmpty())
    }

    // ------------------------------------------------------------- Test 6

    @Test
    fun `エラー画面では失敗として停止する`() {
        val (e, _) = engine()
        val screen = FakeScreen(texts = listOf("エラー", "通信に失敗しました"))
        e.tick(screen.snapshot(0), 0)

        val outcome = e.session.outcome
        assertTrue(outcome is AutomationSession.Outcome.Stopped)
        assertEquals(
            ErrorReason.PAYMENT_DECLINED,
            (outcome as AutomationSession.Outcome.Stopped).reason,
        )
    }

    // ------------------------------------------------------------- Test 7

    @Test
    fun `UNKNOWNが20秒以上続いたらRECOVERINGを経て最終的に失敗する`() {
        val (e, rec) = engine()
        var t = 0L
        val blank = blankScreen()

        // 20秒までは待つ
        while (t < AutomationTimeouts.UNKNOWN_WAIT_MILLIS) {
            e.tick(blank.snapshot(t), t)
            assertNull("20秒までは待つ（t=$t）", e.session.outcome)
            t += 300
        }

        // 20秒を超えると Recovery へ
        t += 300
        e.tick(blank.snapshot(t), t)
        assertEquals(AutomationStatus.RECOVERING, e.session.status)

        // Recovery でも戻らなければ、そこから15秒で失敗
        val deadline = t + AutomationTimeouts.RECOVERY_MILLIS + 1_000
        while (t <= deadline && e.session.outcome == null) {
            t += 300
            e.tick(blank.snapshot(t), t)
        }
        val outcome = e.session.outcome
        assertTrue("最終的には失敗する", outcome is AutomationSession.Outcome.Stopped)
        assertEquals(
            ErrorReason.UNEXPECTED_SCREEN,
            (outcome as AutomationSession.Outcome.Stopped).reason,
        )
        assertTrue(rec.kinds().contains(AutomationLogKind.RECOVERY))
        assertTrue("一度も押していない", blank.clicks.isEmpty())
    }

    @Test
    fun `UNKNOWNから既知画面に戻れば何事もなく続行する`() {
        val (e, _) = engine()
        var t = 0L
        val blank = blankScreen()
        repeat(30) {
            e.tick(blank.snapshot(t), t)
            t += 300
        }
        // 10秒近く UNKNOWN だったが、画面が戻れば復帰する
        val screen = chargeAmountScreen()
        e.tick(screen.snapshot(t), t)
        assertNull(e.session.outcome)
        assertEquals(0, e.session.consecutiveUnknown)
    }

    // ------------------------------------------------------------- Test 8

    @Test
    fun `同一ボタンに対して何周期見ても1回しか押さない`() {
        val (e, _) = engine()
        var t = 0L
        val screen = mainScreen()

        // 300ms 周期で 3 秒ぶん＝10周期。素直に実装すると10回押してしまう。
        repeat(10) {
            e.tick(screen.snapshot(t), t)
            t += 300
        }
        assertEquals("「チャージ」を押すのは1回だけ", 1, screen.clicks.count { it == "チャージ" })
    }

    @Test
    fun `金額ボタンも連打しない`() {
        val (e, _) = engine()
        var t = 0L
        val screen = chargeAmountScreen()
        repeat(10) {
            e.tick(screen.snapshot(t), t)
            t += 300
        }
        assertEquals(1, screen.clicks.count { it == "5,000" })
        assertEquals(1, screen.clicks.count { it == "****9804でチャージ" })
    }

    // ------------------------------------------------------------- Test 9

    @Test
    fun `ICOCAが前面にいない間は待ち続ける`() {
        // プロセスが落ちた、ホームに戻られた等。すぐには諦めない。
        val (e, _) = engine()
        var t = 0L
        repeat(20) {
            e.tick(null, t)
            t += 300
        }
        assertNull(e.session.outcome)
        assertEquals(AutomationStatus.WAITING, e.session.status)
    }

    @Test
    fun `ICOCAが戻ってくれば現在画面から再開する`() {
        val (e, rec) = engine()
        var t = 0L
        repeat(10) {
            e.tick(null, t)
            t += 300
        }
        // 戻ってきたら、いきなり金額選択画面でも、そこから続きをやる（Recovery）
        val screen = chargeAmountScreen()
        e.tick(screen.snapshot(t), t)
        assertNull(e.session.outcome)
        assertTrue(rec.kinds().contains(AutomationLogKind.RECOVERY))
        assertTrue(screen.clicks.isNotEmpty())
    }

    // ------------------------------------------------- 安全条件（維持の確認）

    @Test
    fun `別アプリの画面なら即停止する`() {
        val (e, _) = engine()
        val screen = FakeScreen(
            texts = listOf("チャージ"),
            buttons = mapOf("チャージ" to false),
            packageName = "com.example.fake",
        )
        e.tick(screen.snapshot(0), 0)
        val outcome = e.session.outcome
        assertEquals(
            ErrorReason.PACKAGE_MISMATCH,
            (outcome as AutomationSession.Outcome.Stopped).reason,
        )
        assertTrue(screen.clicks.isEmpty())
    }

    @Test
    fun `決済画面に設定額が無ければ確定を押さない`() {
        val (e, _) = engine(autoConfirm = true)
        val screen = FakeScreen(
            texts = listOf("チャージ確認", "チャージ額：10,000円", "チャージしますか？"),
            buttons = mapOf("チャージする" to false),
        )
        e.tick(screen.snapshot(0), 0)
        val outcome = e.session.outcome
        assertEquals(
            ErrorReason.AMOUNT_MISMATCH,
            (outcome as AutomationSession.Outcome.Stopped).reason,
        )
        assertTrue("確定は押さない", screen.clicks.isEmpty())
    }

    @Test
    fun `自動確定がONなら設定額の一致を確認して確定を押す`() {
        val (e, _) = engine(autoConfirm = true)
        val screen = confirmDialog()
        e.tick(screen.snapshot(0), 0)
        assertEquals(listOf("チャージする"), screen.clicks)
    }

    @Test
    fun `ドライランでは一切押さない`() {
        val session = AutomationSession(
            sessionId = "dry", attemptId = 1L, startedAt = 0L,
            chargeAmountYen = amount, dryRun = true, autoConfirmPayment = true,
        )
        val e = AutomationEngine(
            session,
            SafetyGuard(expectedSignature = null, autoConfirmPayment = true),
            actualSignature = null,
        )
        val screen = confirmDialog()
        e.tick(screen.snapshot(0), 0)
        assertTrue("実際には押さない", screen.clicks.isEmpty())
        assertTrue("押す予定だけ残る", session.plannedClicks.isNotEmpty())
    }

    @Test
    fun `完了画面を見たら成功として終わる`() {
        val (e, rec) = engine()
        val screen = FakeScreen(texts = listOf("チャージが完了しました"))
        e.tick(screen.snapshot(0), 0)
        assertEquals(AutomationSession.Outcome.Completed, e.session.outcome)
        assertTrue(rec.kinds().contains(AutomationLogKind.SUCCESS))
    }

    @Test
    fun `画面履歴と操作ログが残る`() {
        val (e, rec) = engine()
        var t = 0L
        e.tick(mainScreen().snapshot(t), t)
        t += 300
        e.tick(blankScreen().snapshot(t), t)
        t += 300
        e.tick(chargeAmountScreen().snapshot(t), t)

        assertTrue("画面履歴が残る", rec.snapshots.size >= 3)
        assertTrue("SCREEN 行がある", rec.kinds().contains(AutomationLogKind.SCREEN))
        assertTrue("ACTION 行がある", rec.kinds().contains(AutomationLogKind.ACTION))
    }

    @Test
    fun `履歴のカード番号はマスクされる`() {
        val (e, rec) = engine()
        e.tick(chargeAmountScreen().snapshot(0), 0)
        val all = rec.snapshots.flatMap { it.importantTexts } + rec.logs.map { it.detail }
        assertTrue("下4桁を残さない", all.none { it.contains("9804") })
    }
}
