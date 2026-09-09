package io.github.nobu0601.icocaautocharge.balance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEST_PLAN.md §1.3 に対応。誤検知を出さないことが最重要。 */
class BalanceTextParserTest {

    @Test
    fun `よくある表記をすべて解釈できる`() {
        assertEquals(2_840, BalanceTextParser.parseAmount("¥2,840"))
        assertEquals(2_840, BalanceTextParser.parseAmount("2,840円"))
        assertEquals(2_840, BalanceTextParser.parseAmount("残高 2,840"))
        assertEquals(2_840, BalanceTextParser.parseAmount("￥2,840"))
        assertEquals(2_840, BalanceTextParser.parseAmount("2840"))
        assertEquals(0, BalanceTextParser.parseAmount("¥0"))
    }

    @Test
    fun `ICOCAの残高としてありえない値は棄却する`() {
        assertNull(BalanceTextParser.parseAmount("¥25,000"))
        assertNull(BalanceTextParser.parseAmount("-100"))
        assertNull(BalanceTextParser.parseAmount(""))
        assertNull(BalanceTextParser.parseAmount("チャージ"))
    }

    @Test
    fun `カード番号のような長い数字は残高にしない`() {
        assertNull(BalanceTextParser.parseAmount("1234567890123456"))
        assertNull(BalanceTextParser.parseAmount("会員番号 9876543"))
    }

    @Test
    fun `ラベルの裏付けがない金額は既定では採用しない`() {
        // チャージ金額ボタンの「5,000円」だけが見えている状況で、
        // それを残高として採用してしまわないこと
        assertNull(BalanceTextParser.extractBalanceFromLines(listOf("5,000円")))
    }

    @Test
    fun `preferLabeledをfalseにすれば裏付けのない金額も採用する`() {
        assertEquals(
            5_000,
            BalanceTextParser.extractBalanceFromLines(listOf("5,000円"), preferLabeled = false),
        )
    }

    @Test
    fun `ラベルの近くにある金額を優先する`() {
        val lines = listOf("5,000円", "その他", "案内", "残高", "2,840")
        assertEquals(2_840, BalanceTextParser.extractBalanceFromLines(lines))
    }

    @Test
    fun `ラベル行の直後の数値を残高として拾う`() {
        val lines = listOf("残高", "¥2,840", "有効期限")
        assertEquals(2_840, BalanceTextParser.extractBalanceFromLines(lines))
    }

    @Test
    fun `ラベルから離れた金額は拾わない`() {
        val lines = listOf("残高", "¥2,840", "その他", "案内", "チャージ金額", "5,000円")
        assertEquals(2_840, BalanceTextParser.extractBalanceFromLines(lines))
    }

    @Test
    fun `ラベルがどこにもなければ何も返さない`() {
        val lines = listOf("1,000円", "5,000円", "10,000円")
        assertNull(BalanceTextParser.extractBalanceFromLines(lines))
    }

    @Test
    fun `残高ラベルを判別できる`() {
        assertEquals(true, BalanceTextParser.hasBalanceLabel("SF残高"))
        assertEquals(true, BalanceTextParser.hasBalanceLabel("現在の残額"))
        assertEquals(false, BalanceTextParser.hasBalanceLabel("チャージ金額"))
    }
}
