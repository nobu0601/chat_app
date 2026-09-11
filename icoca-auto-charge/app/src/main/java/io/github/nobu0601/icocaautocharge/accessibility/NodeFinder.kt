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
     * ノードが名乗る文字列すべて。text と contentDescription の **両方** を返す。
     *
     * [visibleText] は text があれば contentDescription を見ない。表示用にはそれでよいが、
     * 照合では取りこぼしになる（ボタンの見出しが contentDescription 側だけに入っていて、
     * text には別の短い文字が入っている、という作りが実際にある）。
     */
    private fun labelsOf(node: AccessibilityNodeInfo): List<String> = listOfNotNull(
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
    )

    /**
     * クリック可能なノードが内包する文字列を、子孫まで含めて連結したもの。
     *
     * Compose のボタンはラベルが複数の子ノードに割れることがある。
     * 「****9804」と「でチャージ」が別ノードだと、ノード単位の照合では一生一致しない。
     */
    private fun aggregatedText(node: AccessibilityNodeInfo): String =
        walk(node, maxNodes = AGGREGATE_MAX_NODES).joinToString("") { visibleText(it).orEmpty() }

    /**
     * 指定したテキストと **完全一致** するクリック可能なノードを探す。
     *
     * 部分一致にしないのは、「5,000円」を探しているときに「15,000円」を掴まないため
     * （指示書 §11 の金額確認）。
     *
     * @param excludeEditable true のとき、入力欄（およびその祖先が入力欄のもの）を避ける。
     *   実機の金額選択画面は、上部に編集可能な金額欄があり、下にプリセットのボタンが並ぶ。
     *   どちらにも同じ「5,000」が出るので、入力欄の方を押すとキーボードが出るだけで
     *   先に進まない。押したいのは常にプリセット側。
     */
    fun findClickableByExactText(
        root: AccessibilityNodeInfo?,
        candidates: Collection<String>,
        excludeEditable: Boolean = false,
    ): AccessibilityNodeInfo? {
        val normalized = candidates.map { TextNormalizer.normalize(it) }.toSet()
        for (node in walk(root)) {
            if (excludeEditable && node.isEditable) continue
            val matched = labelsOf(node).any { TextNormalizer.normalize(it) in normalized }
            if (!matched) continue
            val clickable = isClickableSelfOrAncestor(node) ?: continue
            if (excludeEditable && clickable.isEditable) continue
            return clickable
        }
        return null
    }

    /**
     * 指定した語で **終わる** クリック可能なノードを探す。
     *
     * 実機の支払いボタンは「****9804でチャージ」のようにカード番号が入り、
     * 完全一致では掴めない。可変部分を含むボタンはこれで探す。
     * 前方一致にしないのは、可変部分が前に来る形（「〜でチャージ」）だから。
     *
     * ノード単体で一致しなければ、クリック可能なノードの子孫テキストを連結して
     * もう一度照合する。ラベルが複数ノードに割れていても掴めるようにするため。
     */
    fun findClickableByTextSuffix(
        root: AccessibilityNodeInfo?,
        suffixes: Collection<String>,
    ): AccessibilityNodeInfo? {
        val needles = suffixes.map { TextNormalizer.normalize(it) }
        val nodes = walk(root)

        for (node in nodes) {
            val matched = labelsOf(node)
                .any { label -> needles.any { TextNormalizer.normalize(label).endsWith(it) } }
            if (matched) isClickableSelfOrAncestor(node)?.let { return it }
        }

        for (node in nodes) {
            if (!node.isClickable || !node.isEnabled) continue
            val joined = TextNormalizer.normalize(aggregatedText(node))
            if (joined.isNotEmpty() && needles.any { joined.endsWith(it) }) return node
        }
        return null
    }

    /**
     * 画面上のクリックできるものの一覧。**診断専用**（押すのには使わない）。
     *
     * 自動操作が目的のボタンを見つけられなかったとき、
     * 「では何なら押せたのか」が分からないと直しようがない。
     * ラベルにはカード番号が入りうるので、出す前に必ず
     * [io.github.nobu0601.icocaautocharge.core.LogRedactor] を通すこと。
     */
    fun clickableLabels(root: AccessibilityNodeInfo?): List<String> =
        walk(root).filter { it.isClickable && it.isEnabled }
            .mapNotNull { node -> visibleText(node) ?: aggregatedText(node).takeIf { it.isNotEmpty() } }
            .distinct()

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

    /**
     * クリック可能な祖先をどこまで遡るか。
     *
     * Compose のボタンは、文字のノードからクリック可能なノードまでが深い。
     * 浅すぎると「ボタンは画面にあるのに掴めない」が起きるだけで、
     * 深くしても押す対象がボタン以外になることはない（照合は先に済んでいる）。
     */
    private const val MAX_ANCESTOR_HOPS = 8

    /** 子孫テキストを連結するときに見るノード数の上限。 */
    private const val AGGREGATE_MAX_NODES = 40
}
