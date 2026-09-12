package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.accessibility.AutomationSession
import io.github.nobu0601.icocaautocharge.accessibility.IcocaScreen
import io.github.nobu0601.icocaautocharge.accessibility.SafetyGuard
import io.github.nobu0601.icocaautocharge.accessibility.ScreenClassifier
import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.core.LogRedactor
import io.github.nobu0601.icocaautocharge.domain.ErrorReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実機（Pixel 8a / モバイルICOCA）から採取した実際の画面を使う回帰テスト。
 *
 * 2026-09-08 に Debug 画面の「画面ダンプを記録」で取得したメイン画面のノード列。
 * ユーザー補助から見えたテキストを、**出現順のまま**並べている。
 *
 * この1画面で、机上では気づけなかった不具合が2つ見つかった。
 *
 * 1. 残高ラベル「チャージ残額」が数値 6,271 の**後ろ**にあり、
 *    ラベルの後ろだけを見る実装では 6,271 を取り逃していた。
 *    さらにラベルの直後には定期券の日付「6」が並んでおり、
 *    **残高を 6 円と誤読して誤ってチャージ処理を起動する**状態だった。
 * 2. 定期券の日付が裸の数字として並ぶため、
 *    「金額らしき数字が3つ以上」でメイン画面を金額選択画面と誤判定していた。
 *
 * 実機の生データを持たない限り再現できない種類の不具合なので、
 * 採取したものをそのまま資産として残す。
 */
class RealDeviceScreenTest {

    /**
     * モバイルICOCA のメイン画面（残高 6,271円 / 定期券あり）。
     * ダンプの原文どおり。並び順を変えないこと。
     */
    private val icocaMainScreen = listOf(
        "更新",
        "23:34",
        "IC管理",
        "利用履歴",
        "ポイント",
        "定期券",
        "チャージ",
        "円",
        "6,271",
        "チャージ残額",
        "日",
        "6",
        "月",
        "3",
        "9月7日〜2027年",
        "経由：塚本・尼崎",
        "塚口",
        "福島(大阪環状線)",
        "ノブ",
        "8",
    )

    @Test
    fun `実機のメイン画面から残高6271円を読み取れる`() {
        assertEquals(6_271, BalanceTextParser.extractBalanceFromLines(icocaMainScreen))
    }

    @Test
    fun `定期券の日付を残高と取り違えない`() {
        // ラベル「チャージ残額」の直後にある「6」を拾ってしまうと、
        // 残高6円として閾値を割り、誤ってチャージ処理が走る。
        val balance = BalanceTextParser.extractBalanceFromLines(icocaMainScreen)
        assertEquals(6_271, balance)
    }

    @Test
    fun `実機のメイン画面をメイン画面と判定する`() {
        assertEquals(IcocaScreen.MAIN, ScreenClassifier.classify(icocaMainScreen))
    }

    @Test
    fun `更新時刻を金額として拾わない`() {
        assertEquals(null, BalanceTextParser.parseAmount("23:34"))
    }

    @Test
    fun `ラベルが数値の後ろにあっても読み取れる`() {
        assertEquals(
            2_840,
            BalanceTextParser.extractBalanceFromLines(listOf("円", "2,840", "チャージ残額")),
        )
    }

    @Test
    fun `ラベルが数値の前にあっても読み取れる`() {
        assertEquals(
            2_840,
            BalanceTextParser.extractBalanceFromLines(listOf("チャージ残額", "¥2,840")),
        )
    }

    /**
     * 金額選択画面（2026-09-08 採取）。
     *
     * 金額ボタンは「5,000」で、**円が付かない**。
     * 支払いボタンは「****9804でチャージ」でカード番号が入る。
     */
    private val chargeAmountScreen = listOf(
        "チャージ",
        "ノブ",
        "チャージ残額",
        "6,271",
        "円",
        "チャージ金額",
        "5,000",
        "円",
        "チャージ金額上限まであと13,729円チャージできます。",
        "・1円単位でチャージできます。",
        "・チャージの最低金額は500円です。",
        "・チャージの最大金額は20,000円です。",
        "1,000",
        "2,000",
        "3,000",
        "5,000",
        "10,000",
        "****9804でチャージ",
    )

    /**
     * チャージ確認ダイアログ（2026-09-08 採取）。
     *
     * 「チャージ後の残額：11,271円」は**チャージが成功したらこうなる**という予定額で、
     * 現在の残高ではない。これを残高として取り込むと、キャンセルされても
     * 「残高が増えた＝成功」と誤判定してしまう。
     */
    private val paymentConfirmDialog = listOf(
        "チャージ確認",
        "JR西日本へお支払い額：5,000円",
        "チャージ額：5,000円",
        "チャージ後の残額：11,271円",
        "チャージしますか？",
        "キャンセル",
        "チャージする",
    )

    @Test
    fun `実機の金額選択画面を金額選択画面と判定する`() {
        assertEquals(IcocaScreen.CHARGE_AMOUNT, ScreenClassifier.classify(chargeAmountScreen))
    }

    @Test
    fun `金額選択画面でも現在の残高を読み取れる`() {
        assertEquals(6_271, BalanceTextParser.extractBalanceFromLines(chargeAmountScreen))
    }

    @Test
    fun `チャージ上限の案内文を残高と取り違えない`() {
        // 「あと13,729円チャージできます」は残高ではない
        assertEquals(6_271, BalanceTextParser.extractBalanceFromLines(chargeAmountScreen))
    }

    @Test
    fun `実機の確認ダイアログを決済確認画面と判定する`() {
        assertEquals(IcocaScreen.PAYMENT_CONFIRM, ScreenClassifier.classify(paymentConfirmDialog))
    }

    @Test
    fun `チャージ後の予定残額を現在の残高として採用しない`() {
        // ここが緩むと、キャンセルされたチャージを成功と判定する
        assertEquals(null, BalanceTextParser.extractBalanceFromLines(paymentConfirmDialog))
    }

    @Test
    fun `確認ダイアログに設定額が出ていることを検出できる`() {
        // 「JR西日本へお支払い額：5,000円」のように文中に埋まっていても見つける
        val guard = SafetyGuard(expectedSignature = null, autoConfirmPayment = true)
        assertTrue(guard.isAmountVisibleOnScreen(paymentConfirmDialog, 5_000))
        assertFalse(guard.isAmountVisibleOnScreen(paymentConfirmDialog, 10_000))
    }

    /**
     * 金額選択画面にカードの行が載っている場合（実機で毎回ここで止まっていた）。
     *
     * 「お支払い方法」の中の「お支払い」を決済確認の語として扱っていたため、
     * 金額選択画面が PAYMENT_CONFIRM と判定されていた。すると
     *  - 「決済の確定まで自動で押す」が OFF なら、その場で正常終了して止まる
     *  - ON でも、確定ボタンのラベル（「チャージする」等）が画面に無いので
     *    何も押せず、タイムアウトまで止まる
     * どちらの設定でも金額選択画面から先に進めなくなっていた。
     */
    private val chargeAmountScreenWithCardRow = chargeAmountScreen.toMutableList().apply {
        add(indexOf("****9804でチャージ"), "お支払い方法")
        add(indexOf("****9804でチャージ"), "JCB ****9804")
    }

    @Test
    fun `カード行のある金額選択画面を決済確認と取り違えない`() {
        assertEquals(
            IcocaScreen.CHARGE_AMOUNT,
            ScreenClassifier.classify(chargeAmountScreenWithCardRow),
        )
    }

    @Test
    fun `支払い方法の見出しだけで決済確認と判定しない`() {
        assertEquals(
            IcocaScreen.CHARGE_AMOUNT,
            ScreenClassifier.classify(chargeAmountScreen + "お支払い方法"),
        )
    }

    @Test
    fun `確認ダイアログが金額選択画面に重なっていても決済確認と判定する`() {
        // 実機のダイアログは金額選択画面の上に出るため、両方の文字が同時に見える。
        // ここで金額選択画面と判定すると、確定ボタンを押す判断に入れなくなる。
        assertEquals(
            IcocaScreen.PAYMENT_CONFIRM,
            ScreenClassifier.classify(chargeAmountScreen + paymentConfirmDialog),
        )
    }

    @Test
    fun `プリセットが読めなくても支払いボタンがあれば金額選択画面と判定する`() {
        // 金額のプリセットが contentDescription 側にしか無い等で読めない場合でも、
        // 「〜でチャージ」ボタンが見えていれば金額選択画面。
        assertEquals(
            IcocaScreen.CHARGE_AMOUNT,
            ScreenClassifier.classify(
                listOf("チャージ", "5,000", "円", "****9804でチャージ"),
            ),
        )
    }

    @Test
    fun `支払いボタンのカード番号はログに出ない`() {
        val redacted = LogRedactor.redact("支払いへ進むボタン: ****9804でチャージ")
        assertFalse(redacted.contains("9804"))
        assertTrue(redacted.contains("でチャージ"))
    }

    @Test
    fun `金額はマスクされない`() {
        // カード番号を潰すために金額まで潰すと、診断の役に立たなくなる。
        assertEquals("5,000 / 10,000", LogRedactor.redact("5,000 / 10,000"))
    }

    /**
     * 「チャージ」を押した直後に一瞬だけ挟まる、中身の出来ていない画面
     * （2026-09-12 に実機の「自動操作の記録」から採取）。
     *
     * ノードは13個あるが、文字を持つのは「更新」と時刻だけ。残高も見出しも無い。
     * 記録に残っていた実際の流れはこうだった。
     *
     *   画面=MAIN step=0 → 押した: charge entry:チャージ
     *   画面=UNKNOWN step=1 → 中止: UNEXPECTED_SCREEN
     *
     * つまりチャージ画面に着く前にセッションが死んでいた。ICOCA アプリ側は
     * そのまま遷移を続けるので、利用者からは「チャージ画面が出たまま
     * 自動操作が止まった」ようにしか見えない。金額の設定値とは無関係に起きる。
     */
    private val transitionalBlankScreen = listOf("更新", "11:13")

    @Test
    fun `遷移中の空の画面は判別できない画面として扱う`() {
        assertEquals(IcocaScreen.UNKNOWN, ScreenClassifier.classify(transitionalBlankScreen))
    }

    @Test
    fun `判別できない画面が一度出ただけでは自動操作を中止しない`() {
        val guard = SafetyGuard(expectedSignature = null, autoConfirmPayment = true)
        val verdict = guard.check(unknownScreenContext(streak = 1))
        assertEquals(SafetyGuard.Verdict.Wait, verdict)
    }

    @Test
    fun `判別できない画面が続いたら自動操作を中止する`() {
        val guard = SafetyGuard(expectedSignature = null, autoConfirmPayment = true)
        val verdict = guard.check(
            unknownScreenContext(streak = SafetyGuard.MAX_UNKNOWN_STREAK + 1),
        )
        assertTrue(verdict is SafetyGuard.Verdict.Stop)
        assertEquals(
            ErrorReason.UNEXPECTED_SCREEN,
            (verdict as SafetyGuard.Verdict.Stop).reason,
        )
    }

    @Test
    fun `判別できる画面を挟むと連続回数は0に戻る`() {
        val session = AutomationSession(
            chargeAmountYen = 3_000,
            startedAt = 0L,
            dryRun = true,
        )
        session.onScreen(IcocaScreen.MAIN, 0L)
        session.onScreen(IcocaScreen.UNKNOWN, 1L)
        session.onScreen(IcocaScreen.UNKNOWN, 2L)
        assertEquals(2, session.unknownStreak)

        // 遷移が終わって金額選択画面に着けば、それまでの空振りは無かったことにする。
        session.onScreen(IcocaScreen.CHARGE_AMOUNT, 3L)
        assertEquals(0, session.unknownStreak)
    }

    private fun unknownScreenContext(streak: Int) = SafetyGuard.Context(
        packageName = IcocaConstants.PACKAGE_NAME,
        actualSignature = null,
        screen = IcocaScreen.UNKNOWN,
        stepCount = 1,
        millisSinceProgress = 0L,
        unknownStreak = streak,
    )

    @Test
    fun `金額ボタンは円が付かなくても設定額と一致すると判定できる`() {
        // 実機の金額ボタンは「5,000」で円が付かない
        val guard = SafetyGuard(expectedSignature = null)
        assertTrue(guard.verifyAmountLabel("5,000", 5_000))
        assertFalse(guard.verifyAmountLabel("10,000", 5_000))
        assertFalse(guard.verifyAmountLabel("1,000", 5_000))
    }
}
