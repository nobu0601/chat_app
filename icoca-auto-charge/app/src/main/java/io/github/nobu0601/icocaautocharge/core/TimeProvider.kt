package io.github.nobu0601.icocaautocharge.core

/**
 * 現在時刻の抽象。テストで時刻を固定するために挟んでいる。
 * （クールダウン・上限・メンテナンス時間帯の判定はすべて時刻依存のため）
 */
fun interface TimeProvider {
    fun nowMillis(): Long

    companion object {
        val System = TimeProvider { java.lang.System.currentTimeMillis() }
    }
}
