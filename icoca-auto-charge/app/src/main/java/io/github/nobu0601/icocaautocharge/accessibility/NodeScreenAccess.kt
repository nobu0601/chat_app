package io.github.nobu0601.icocaautocharge.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import io.github.nobu0601.icocaautocharge.core.TextNormalizer

/**
 * 実機の `AccessibilityNodeInfo` を [ScreenAccess] として包む。
 *
 * **[AutomationEngine] に Android の型を持ち込ませないための境界。**
 * ここだけが `AccessibilityNodeInfo` を知っていて、エンジンは [ClickTarget] しか見ない。
 * おかげでエンジン全体が JVM のテストから駆動できる。
 *
 * 探索は [NodeFinder] に委ねる。座標は一切扱わない（指示書 §26）。
 */
class NodeScreenAccess(private val root: AccessibilityNodeInfo) : ScreenAccess {

    /** [find] が返した [ClickTarget] から実ノードに戻るための対応表。 */
    private val resolved = HashMap<String, NodeFinder.Match>()

    override fun find(spec: NodeSpec): ClickTarget? = when (spec) {
        is NodeSpec.ExactText -> {
            NodeFinder.findClickableByExactText(root, spec.candidates, spec.excludeEditable)
                ?.let { node -> target(NodeFinder.Match(node = node, ancestorDepth = 0)) }
        }
        // 末尾一致も部分一致に委ねる。見た目のボタンとノード構造は一致しないので、
        // 末尾だけで探すと実機で取りこぼす。
        is NodeSpec.TextSuffix -> NodeFinder.findClickableByTextContains(root, spec.suffixes)
            ?.let { target(it) }
        is NodeSpec.TextContains -> NodeFinder.findClickableByTextContains(root, spec.needles)
            ?.let { target(it) }
    }

    private fun target(match: NodeFinder.Match): ClickTarget {
        val node = match.node
        val label = NodeFinder.visibleText(node) ?: match.matchedText.takeIf { it.isNotEmpty() }
        val key = keyOf(node, label)
        resolved[key] = match
        return ClickTarget(
            key = key,
            label = label,
            matchedText = match.matchedText.takeIf { it.isNotEmpty() },
            matchedClassName = match.textClassName ?: node.className?.toString(),
            matchedNodeClickable = match.textNodeClickable || node.isClickable,
            ancestorDepth = match.ancestorDepth,
            diagnostics = NodeFinder.describe(match),
        )
    }

    override fun clickableLabels(): List<String> = NodeFinder.clickableLabels(root)

    /**
     * 押す。
     *
     * 実機では「ボタンは見つかったのに `performAction` が false」で止まった。
     * ダンプを見ると、見た目のボタンの正体は `ScrollView` の中の `TextView` だった。
     * `isClickable` や action list は**当てにならない**ので、
     * 旗を見て諦めるのではなく、順に**実際に試す**。
     *
     * 1. ノードを最新化する。掴んでから押すまでにツリーが作り直されていると、
     *    古いノードへの `performAction` は何もせず false を返す
     * 2. 画面外にいるなら画面内へ入れる。スクロール領域の外にあるノードは押せない
     * 3. 自分 → 親 → さらに上の順に、実際に `performAction` を試す。
     *    ただし無関係なコンテナまで上がらないよう、範囲の判定は [NodeFinder] に委ねる
     *
     * @return 押せたかどうか。**true でも「チャージ操作が成功した」ではない。**
     */
    override fun click(target: ClickTarget): Boolean {
        attempts.clear()
        val match = resolved[target.key] ?: return false
        val node = match.node

        // 1) 掴んでから時間が経っている。古いノードを押しても何も起きない。
        val refreshed = runCatching { node.refresh() }.getOrDefault(false)
        attempts += "refresh=$refreshed visible=${isOnScreen(node)}"

        // 2) 画面外なら見えるところへ持ってくる。
        if (!isOnScreen(node)) {
            attempts += "offscreen→SHOW_ON_SCREEN"
            runCatching {
                node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id,
                )
            }
            runCatching { node.refresh() }
        }

        // 3) 旗ではなく実際の結果で判断する。
        val reference = match.matchedText.ifEmpty { target.label.orEmpty() }
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops <= MAX_CLICK_HOPS) {
            val here = current
            if (hops > 0 && !NodeFinder.enclosesOnlyTheButton(here, reference)) {
                // ここから上はボタンではなく画面のコンテナ。押すと別の場所を叩く。
                attempts += "depth$hops:範囲外で打ち切り"
                break
            }
            val ok = runCatching { here.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                .getOrDefault(false)
            attempts += "depth$hops:${here.className?.toString()?.substringAfterLast('.')}=$ok"
            if (ok) return true
            current = runCatching { here.parent }.getOrNull()
            hops++
        }

        // 祖先の系列が全滅した。同じ語を内包していて ACTION_CLICK を公開している
        // ノードが他にもあるかもしれないので、そちらも試す。
        // 見た目のボタンとノード構造は一致しないので、1系統で諦めない。
        val reference2 = TextNormalizer.normalize(reference)
        for (candidate in NodeFinder.walk(root)) {
            if (!NodeFinder.exposesClickAction(candidate)) continue
            if (!NodeFinder.enclosesOnlyTheButton(candidate, reference)) continue
            val ok = runCatching { candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                .getOrDefault(false)
            attempts += "alt:${candidate.className?.toString()?.substringAfterLast('.')}=$ok"
            if (ok) return true
        }
        attempts += "全滅(ref=${reference2.length}文字)"
        return false
    }

    /** 直前のクリックで何を試したか。診断表示に使う。 */
    private val attempts = mutableListOf<String>()

    override fun lastClickReport(): String? =
        attempts.takeIf { it.isNotEmpty() }?.joinToString(" | ")

    override fun clickableInventory(): List<String> = NodeFinder.clickableInventory(root)

    private fun isOnScreen(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isVisibleToUser }.getOrDefault(true)

    /**
     * 同じボタンなら毎回同じになる識別子。
     *
     * `viewIdResourceName` が取れればそれが一番確かだが、実機の ICOCA アプリの
     * ノードは大半が持っていないので、ラベルとクラス名で補う。
     * ここが安定しないと [ActionThrottle] が連打を止められなくなる。
     */
    private fun keyOf(node: AccessibilityNodeInfo, label: String?): String {
        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { return "id:$it" }
        if (!label.isNullOrEmpty()) return "text:$label"
        return "class:${node.className}"
    }

    private companion object {
        /** クリックを試しながら遡る上限。探索側の上限と揃える。 */
        const val MAX_CLICK_HOPS = 8
    }
}
