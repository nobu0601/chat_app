package io.github.nobu0601.icocaautocharge.accessibility

import android.view.accessibility.AccessibilityNodeInfo

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
    private val resolved = HashMap<String, AccessibilityNodeInfo>()

    override fun find(spec: NodeSpec): ClickTarget? {
        val node = when (spec) {
            is NodeSpec.ExactText ->
                NodeFinder.findClickableByExactText(root, spec.candidates, spec.excludeEditable)
            is NodeSpec.TextSuffix ->
                NodeFinder.findClickableByTextSuffix(root, spec.suffixes)
        } ?: return null

        val label = NodeFinder.visibleText(node)
        val key = keyOf(node, label)
        resolved[key] = node
        return ClickTarget(key = key, label = label)
    }

    override fun clickableLabels(): List<String> = NodeFinder.clickableLabels(root)

    override fun click(target: ClickTarget): Boolean {
        val node = resolved[target.key] ?: return false
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .getOrDefault(false)
    }

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
}
