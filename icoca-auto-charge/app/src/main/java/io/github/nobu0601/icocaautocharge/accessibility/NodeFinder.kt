package io.github.nobu0601.icocaautocharge.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import io.github.nobu0601.icocaautocharge.core.TextNormalizer

/**
 * `AccessibilityNodeInfo` の走査ユーティリティ。
 *
 * **座標は一切扱わない**（指示書 §26）。text / contentDescription / viewIdResourceName だけを見る。
 * ICOCA アプリのレイアウトが変わっても壊れにくくするため。
 */
object NodeFinder {

    /** 深さ優先で全ノードを列挙する。無限ループと過大なツリーに上限をかける。 */
    fun walk(root: AccessibilityNodeInfo?, maxNodes: Int = MAX_NODES): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>(64)
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && out.size < maxNodes) {
            val (node, depth) = stack.removeLast()
            out += node
            if (depth >= MAX_DEPTH) continue
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                stack.addLast(child to depth + 1)
            }
        }
        return out
    }

    /** ノードから見える文字列（text 優先、無ければ contentDescription）。 */
    fun visibleText(node: AccessibilityNodeInfo): String? {
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) return t
        val d = node.contentDescription?.toString()?.trim()
        return if (!d.isNullOrEmpty()) d else null
    }

    /** 画面上のテキストを出現順に集める。 */
    fun collectTexts(root: AccessibilityNodeInfo?): List<String> =
        walk(root).mapNotNull { visibleText(it) }

    /**
     * 指定したテキストと **完全一致** するクリック可能なノードを探す。
     *
     * 部分一致にしないのは、「5,000円」を探しているときに「15,000円」を掴まないため
     * （指示書 §11 の金額確認）。
     */
    fun findClickableByExactText(
        root: AccessibilityNodeInfo?,
        candidates: Collection<String>,
    ): AccessibilityNodeInfo? {
        val normalized = candidates.map { TextNormalizer.normalize(it) }.toSet()
        return walk(root).firstOrNull { node ->
            val text = visibleText(node)?.let { TextNormalizer.normalize(it) }
                ?: return@firstOrNull false
            text in normalized && isClickableSelfOrAncestor(node) != null
        }?.let { isClickableSelfOrAncestor(it) }
    }

    /**
     * 指定した語で **終わる** クリック可能なノードを探す。
     *
     * 実機の支払いボタンは「****9804でチャージ」のようにカード番号が入り、
     * 完全一致では掴めない。可変部分を含むボタンはこれで探す。
     * 前方一致にしないのは、可変部分が前に来る形（「〜でチャージ」）だから。
     */
    fun findClickableByTextSuffix(
        root: AccessibilityNodeInfo?,
        suffixes: Collection<String>,
    ): AccessibilityNodeInfo? {
        val normalizedSuffixes = suffixes.map { TextNormalizer.normalize(it) }
        return walk(root).firstOrNull { node ->
            val text = visibleText(node)?.let { TextNormalizer.normalize(it) }
                ?: return@firstOrNull false
            normalizedSuffixes.any { text.endsWith(it) } && isClickableSelfOrAncestor(node) != null
        }?.let { isClickableSelfOrAncestor(it) }
    }

    /** テキストを含むノードを探す（画面判定など、押さない用途にのみ使う）。 */
    fun findByTextContains(root: AccessibilityNodeInfo?, needle: String): AccessibilityNodeInfo? =
        walk(root).firstOrNull { visibleText(it)?.contains(needle) == true }

    fun findByViewId(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? =
        walk(root).firstOrNull { it.viewIdResourceName == viewId }

    /**
     * そのノード自身か、直近の祖先のうちクリック可能なものを返す。
     *
     * Compose や独自 View では、文字を持つノードではなく親がクリック可能なことが多い。
     */
    fun isClickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var hops = 0
        while (current != null && hops <= MAX_ANCESTOR_HOPS) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 40
    private const val MAX_ANCESTOR_HOPS = 5
}
