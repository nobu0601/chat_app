package io.github.nobu0601.icocaautocharge.data

import io.github.nobu0601.icocaautocharge.core.LogRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST_PLAN.md §1.6 に対応。指示書 §24 の「絶対にログに出さない」を機械的に担保する。 */
class LogRedactorTest {

    @Test
    fun `カード番号らしき数字列はマスクされる`() {
        val out = LogRedactor.redact("card 4111111111111111")
        assertFalse(out.contains("4111111111111111"))
        assertTrue(out.contains("****"))
    }

    @Test
    fun `区切り付きのカード番号もマスクされる`() {
        val out = LogRedactor.redact("4111 1111 1111 1111")
        assertFalse(out.contains("1111 1111"))
    }

    @Test
    fun `パスワードの値はマスクされる`() {
        val out = LogRedactor.redact("password=hunter2")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("password"))
    }

    @Test
    fun `日本語の機密ラベルもマスクされる`() {
        assertFalse(LogRedactor.redact("暗証番号 1234").contains("1234"))
        assertFalse(LogRedactor.redact("セキュリティコード: 123").contains("123"))
        assertFalse(LogRedactor.redact("ワンタイムパスワード 987654").contains("987654"))
    }

    @Test
    fun `残高のような短い数字はそのまま残る`() {
        assertEquals("残高 2840", LogRedactor.redact("残高 2840"))
        assertEquals("balance=2840 threshold=3000", LogRedactor.redact("balance=2840 threshold=3000"))
    }

    @Test
    fun `空文字はそのまま`() {
        assertEquals("", LogRedactor.redact(""))
    }
}
