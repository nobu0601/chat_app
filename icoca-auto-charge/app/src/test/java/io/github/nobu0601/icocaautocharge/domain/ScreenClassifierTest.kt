package io.github.nobu0601.icocaautocharge.domain

import io.github.nobu0601.icocaautocharge.accessibility.IcocaScreen
import io.github.nobu0601.icocaautocharge.accessibility.ScreenClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST_PLAN.md §1.5 に対応。認証とエラーを取り違えないことが最重要。 */
class ScreenClassifierTest {

    @Test
    fun `残高が見えていればメイン画面`() {
        assertEquals(
            IcocaScreen.MAIN,
            ScreenClassifier.classify(listOf("残高", "¥2,840", "チャージ")),
        )
    }

    @Test
    fun `金額の見出しがあれば金額選択画面`() {
        assertEquals(
            IcocaScreen.CHARGE_AMOUNT,
            ScreenClassifier.classify(listOf("チャージ金額", "1,000円", "5,000円", "10,000円")),
        )
    }

    @Test
    fun `決済の語があれば決済確認画面`() {
        assertEquals(
            IcocaScreen.PAYMENT_CONFIRM,
            ScreenClassifier.classify(listOf("この内容でチャージします", "5,000円", "決済する")),
        )
    }

    @Test
    fun `認証を示す語は最優先で認証画面と判定する`() {
        listOf(
            listOf("ワンタイムパスワード", "認証"),
            listOf("3Dセキュア"),
            listOf("指紋で認証してください"),
            listOf("暗証番号を入力してください"),
        ).forEach {
            assertEquals("input=$it", IcocaScreen.AUTHENTICATION, ScreenClassifier.classify(it))
        }
    }

    @Test
    fun `完了を示す語があれば完了画面`() {
        assertEquals(
            IcocaScreen.COMPLETED,
            ScreenClassifier.classify(listOf("チャージが完了しました")),
        )
    }

    @Test
    fun `エラーを示す語があればエラー画面`() {
        assertEquals(
            IcocaScreen.ERROR,
            ScreenClassifier.classify(listOf("通信エラーが発生しました")),
        )
    }

    @Test
    fun `認証とエラーが混在してもどちらにせよ停止対象になる`() {
        val screen = ScreenClassifier.classify(listOf("エラー", "認証"))
        assertTrue(screen.requiresStop)
    }

    @Test
    fun `判別できない画面はUNKNOWNで停止対象`() {
        val screen = ScreenClassifier.classify(listOf("まったく無関係"))
        assertEquals(IcocaScreen.UNKNOWN, screen)
        assertTrue(screen.requiresStop)
    }

    @Test
    fun `テキストが空ならUNKNOWN`() {
        assertEquals(IcocaScreen.UNKNOWN, ScreenClassifier.classify(emptyList()))
    }

    @Test
    fun `決済確認は停止対象だが完了とメインは違う`() {
        assertTrue(IcocaScreen.PAYMENT_CONFIRM.requiresStop)
        assertTrue(!IcocaScreen.COMPLETED.requiresStop)
        assertTrue(!IcocaScreen.MAIN.requiresStop)
    }
}
