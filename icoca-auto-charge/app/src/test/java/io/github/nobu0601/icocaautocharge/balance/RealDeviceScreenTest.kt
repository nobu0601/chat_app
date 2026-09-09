package io.github.nobu0601.icocaautocharge.balance

import io.github.nobu0601.icocaautocharge.accessibility.IcocaScreen
import io.github.nobu0601.icocaautocharge.accessibility.ScreenClassifier
import org.junit.Assert.assertEquals
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
     * 金額選択画面は、チャージ額（1,000円単位）が並ぶことで判定する。
     * 実機のチャージ画面はまだ採取できていないため、想定される並びで確認しておく。
     */
    @Test
    fun `チャージ額が並ぶ画面は金額選択画面と判定する`() {
        val screen = listOf("チャージ", "1,000円", "3,000円", "5,000円", "10,000円")
        assertEquals(IcocaScreen.CHARGE_AMOUNT, ScreenClassifier.classify(screen))
    }
}
