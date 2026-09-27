package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.core.IcocaConstants
import io.github.nobu0601.icocaautocharge.core.TextNormalizer

/**
 * テスト用の画面。実機の `AccessibilityNodeInfo` の代わりに使う。
 *
 * [AutomationEngine] は [ScreenAccess] しか知らないので、こうして差し替えられる。
 * おかげで「イベントが来ない」「同じ画面が15秒続く」のような、
 * 本来なら実機でしか起きない状況を JVM のテストで再現できる。
 */
class FakeScreen(
    val texts: List<String>,
    /** 押せるボタン。ラベル → 編集可能か。 */
    private val buttons: Map<String, Boolean> = emptyMap(),
    private val packageName: String = IcocaConstants.PACKAGE_NAME,
    /** 明示したい場合の画面種別。省略すると [ScreenClassifier] に判定させる。 */
    private val forcedScreen: IcocaScreen? = null,
) : ScreenAccess {

    /** 押された順に記録する。「連打していないか」をここで数える。 */
    val clicks = mutableListOf<String>()

    /** クリックを失敗させたいとき。 */
    var clickSucceeds: Boolean = true

    /** この回数だけ失敗してから成功する。実機の「画面外で押せない → 入れ直せば押せる」の再現。 */
    var failFirstClicks: Int = 0

    /** 失敗も含めた試行回数。 */
    var clickAttempts: Int = 0

    val screen: IcocaScreen get() = forcedScreen ?: ScreenClassifier.classify(texts)

    fun snapshot(now: Long) = ScreenSnapshot(
        capturedAt = now,
        packageName = packageName,
        screen = screen,
        texts = texts,
        nodes = emptyList(),
        access = this,
    )

    override fun find(spec: NodeSpec): ClickTarget? {
        val label = when (spec) {
            is NodeSpec.ExactText -> {
                val wanted = spec.candidates.map { TextNormalizer.normalize(it) }.toSet()
                buttons.entries.firstOrNull { (text, editable) ->
                    (!spec.excludeEditable || !editable) &&
                        TextNormalizer.normalize(text) in wanted
                }?.key
            }
            is NodeSpec.TextSuffix -> {
                val wanted = spec.suffixes.map { TextNormalizer.normalize(it) }
                buttons.keys.firstOrNull { text ->
                    wanted.any { TextNormalizer.normalize(text).endsWith(it) }
                }
            }
            // 実機と同じ順序で探す。末尾一致 → 部分一致。
            is NodeSpec.TextContains -> {
                val wanted = spec.needles.map { TextNormalizer.normalize(it) }
                buttons.keys.firstOrNull { text ->
                    wanted.any { TextNormalizer.normalize(text).endsWith(it) }
                } ?: buttons.keys.firstOrNull { text ->
                    wanted.any { TextNormalizer.normalize(text).contains(it) }
                }
            }
        } ?: return null
        return ClickTarget(
            key = "text:$label",
            label = label,
            matchedText = label,
            matchedClassName = "android.widget.Button",
            matchedNodeClickable = true,
            ancestorDepth = 0,
            diagnostics = "text=$label depth=0",
        )
    }

    override fun clickableLabels(): List<String> = buttons.keys.toList()

    override fun click(target: ClickTarget): Boolean {
        clickAttempts++
        if (!clickSucceeds) return false
        if (failFirstClicks > 0) {
            failFirstClicks--
            return false
        }
        clicks += target.label ?: target.key
        return true
    }
}

/** ログと履歴を貯めるだけの記録先。検証に使う。 */
class RecordingRecorder : AutomationRecorder {
    val logs = mutableListOf<AutomationLogEntry>()
    val snapshots = mutableListOf<SnapshotRecord>()

    override fun log(kind: AutomationLogKind, detail: String, nowMillis: Long) {
        logs += AutomationLogEntry(nowMillis, kind, detail)
    }

    override fun snapshot(record: SnapshotRecord) {
        snapshots += record
    }

    fun kinds(): List<AutomationLogKind> = logs.map { it.kind }
}
