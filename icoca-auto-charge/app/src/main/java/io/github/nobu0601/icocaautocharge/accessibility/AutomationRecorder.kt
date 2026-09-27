package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.core.LogRedactor

/**
 * エンジンが何を見て何をしたかの記録先（改修指示 §18, §20）。
 *
 * 実機には logcat を取りに行けないので、アプリ自身に残させる。
 * インタフェースにしてあるのは、テストで中身を検査するため。
 *
 * **渡す文字列は必ず [LogRedactor] を通してから**。
 * ボタンのラベルにはカード番号が入りうる（「****9804でチャージ」）。
 */
interface AutomationRecorder {
    fun log(kind: AutomationLogKind, detail: String, nowMillis: Long)
    fun snapshot(record: SnapshotRecord)

    /** 何も残さない実装。ドライランの外側やテストの足場に使う。 */
    object None : AutomationRecorder {
        override fun log(kind: AutomationLogKind, detail: String, nowMillis: Long) = Unit
        override fun snapshot(record: SnapshotRecord) = Unit
    }
}
