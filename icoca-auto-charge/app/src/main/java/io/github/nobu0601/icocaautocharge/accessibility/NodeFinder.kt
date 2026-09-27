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
    ): AccessibilityNodeInfo? = findClickableByTextContains(root, suffixes)?.node

    /**
     * 画面上のクリックできるものの一覧。**診断専用**（押すのには使わない）。
     *
     * 自動操作が目的のボタンを見つけられなかったとき、
     * 「では何なら押せたのか」が分からないと直しようがない。
     * ラベルにはカード番号が入りうるので、出す前に必ず
     * [io.github.nobu0601.icocaautocharge.core.LogRedactor] を通すこと。
     */
    fun clickableLabels(root: AccessibilityNodeInfo?): List<String> =
        walk(root).filter { canBeClicked(it) }
            .mapNotNull { node -> visibleText(node) ?: aggregatedText(node).takeIf { it.isNotEmpty() } }
            .distinct()

    /**
     * ツリー全体で ACTION_CLICK を公開しているノードの一覧。**診断専用。**
     *
     * 「そもそもこの画面に押せるノードがあるのか」を確かめるためのもの。
     * すべて押せないなら、ユーザー補助の公開 API では押せない画面だと分かる
     * （そのときに座標タップへ逃げてはいけない。指示書 §26）。
     *
     * カード番号を含みうるので、出す前に必ず `LogRedactor` を通すこと。
     */
    fun clickableInventory(root: AccessibilityNodeInfo?): List<String> =
        walk(root).mapNotNull { node ->
            val clickable = node.isClickable
            val action = exposesClickAction(node)
            if (!clickable && !action) return@mapNotNull null
            val name = node.className?.toString()?.substringAfterLast('.')
            val label = visibleText(node)?.take(20) ?: "-"
            val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
            "$name[$label] click=$clickable action=$action visible=$visible"
        }

    /** テキストを含むノードを探す（画面判定など、押さない用途にのみ使う）。 */
    fun findByTextContains(root: AccessibilityNodeInfo?, needle: String): AccessibilityNodeInfo? =
        walk(root).firstOrNull { visibleText(it)?.contains(needle) == true }

    fun findByViewId(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? =
        walk(root).firstOrNull { it.viewIdResourceName == viewId }

    /**
     * そのノードを押せるか。
     *
     * `isClickable` **だけを見るのでは足りない。** 独自 View や Compose には、
     * `isClickable=false` なのに action list には ACTION_CLICK を持つノードがある。
     * 逆に `isClickable=true` でも ACTION_CLICK を公開していないものもある。
     * どちらか一方でも押せるなら押せるものとして扱う。
     */
    fun canBeClicked(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        if (node.isClickable) return true
        return exposesClickAction(node)
    }

    /** action list に ACTION_CLICK が入っているか。 */
    fun exposesClickAction(node: AccessibilityNodeInfo): Boolean = runCatching {
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    }.getOrDefault(false)

    /**
     * そのノード自身か、直近の祖先のうち押せるものを返す。
     *
     * Compose や独自 View では、文字を持つノードではなく親が押せることが多い。
     */
    fun isClickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        resolveClickable(node, needle = null)?.node

    /**
     * 押せる祖先まで遡る（要件2）。
     *
     * 優先順位は上から順に見るだけで自然に満たされる。
     *  A. テキストノード自身が押せる（depth 0）
     *  B. 親が押せる（depth 1）
     *  C. さらに上の祖先が押せる（depth 2 以上）
     *
     * **無関係な親まで上がってはいけない。** 上がりすぎると、画面全体を包む
     * コンテナを掴んでまったく別の場所を叩くことになる。
     * [needle] を渡した場合、その祖先の子孫テキストが
     *  - 照合した語をまだ含んでいること
     *  - 語に対して極端に長くないこと（＝ボタン1個ぶんの範囲に収まっていること）
     * を確かめ、外れたらそこで打ち切る。
     */
    fun resolveClickable(node: AccessibilityNodeInfo?, needle: String?): Match? {
        var current = node ?: return null
        var depth = 0
        while (depth <= MAX_ANCESTOR_HOPS) {
            if (canBeClicked(current)) {
                if (needle == null || enclosesOnlyTheButton(current, needle)) {
                    return Match(node = current, ancestorDepth = depth)
                }
                // 押せはするが範囲が広すぎる。これ以上 上がっても広がる一方なので諦める。
                return null
            }
            current = current.parent ?: return null
            depth++
        }
        return null
    }

    /**
     * その祖先が「ボタン1個ぶん」に収まっているか。
     *
     * 画面全体を包む `ScrollView` などを掴むと、押した場所が意図と無関係になる。
     * 子孫テキストの長さで見分ける。ボタンのラベルに対して極端に長ければ、
     * それはボタンではなくページのコンテナ。
     */
    fun enclosesOnlyTheButton(ancestor: AccessibilityNodeInfo, reference: String): Boolean {
        val needle = TextNormalizer.normalize(reference)
        if (needle.isEmpty()) return false
        val subtree = TextNormalizer.normalize(aggregatedText(ancestor))
        if (!subtree.contains(needle)) return false
        return subtree.length <= needle.length * ANCESTOR_TEXT_RATIO + ANCESTOR_TEXT_SLACK
    }

    /** 押せるノードと、そこへ到達するまでに遡った階層数。 */
    data class Match(
        val node: AccessibilityNodeInfo,
        val ancestorDepth: Int,
        /** 照合に使った文字列。ノード自身のテキストか、子孫を連結したもの。 */
        val matchedText: String = "",
        /** 文字を持っていたノードのクラス名。 */
        val textClassName: String? = null,
        /** 文字を持っていたノード自身が押せたか（false なら祖先を押す）。 */
        val textNodeClickable: Boolean = false,
    )

    /**
     * 指定した語を **含む** 押せるノードを探す（要件1）。
     *
     * 末尾一致だけでは実機で取りこぼした。「****9804でチャージ」のつもりでも、
     * ノードのテキストが「****9804でチャージする」だったり、
     * 「****9804」と「でチャージ」が別ノードに割れていたりする。
     * 見た目のボタンと `AccessibilityNodeInfo` の構造は一致しない。
     *
     * 探す順序は「確からしい順」。
     *  1. ノード自身のテキストが語で**終わる**（もっとも確か）
     *  2. ノード自身のテキストが語を**含む**
     *  3. 子孫テキストを連結したものが語を含む（ラベルが割れている場合）
     *
     * どの段でも、押す相手は [resolveClickable] が決める。
     */
    fun findClickableByTextContains(
        root: AccessibilityNodeInfo?,
        needles: Collection<String>,
    ): Match? {
        val wanted = needles.map { TextNormalizer.normalize(it) }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return null
        val nodes = walk(root)

        // 1) ノード自身のテキストが語で終わる
        findBy(nodes, wanted) { text, needle -> text.endsWith(needle) }?.let { return it }
        // 2) ノード自身のテキストが語を含む
        findBy(nodes, wanted) { text, needle -> text.contains(needle) }?.let { return it }

        // 3) ラベルが子ノードに割れている場合。連結して照合する。
        //    ここでノード自身が押せることを条件にしてはいけない。押せるのは
        //    さらに親のこともあるので、連結の対象と押す相手は分けて考える。
        for (node in nodes) {
            val joined = TextNormalizer.normalize(aggregatedText(node))
            if (joined.isEmpty()) continue
            val needle = wanted.firstOrNull { joined.contains(it) } ?: continue
            val match = resolveClickable(node, needle) ?: continue
            return match.copy(
                matchedText = joined,
                textClassName = node.className?.toString(),
                textNodeClickable = node.isClickable,
            )
        }
        return null
    }

    private inline fun findBy(
        nodes: List<AccessibilityNodeInfo>,
        wanted: List<String>,
        matches: (text: String, needle: String) -> Boolean,
    ): Match? {
        for (node in nodes) {
            for (raw in labelsOf(node)) {
                val text = TextNormalizer.normalize(raw)
                val needle = wanted.firstOrNull { matches(text, it) } ?: continue
                val match = resolveClickable(node, needle) ?: continue
                return match.copy(
                    matchedText = raw,
                    textClassName = node.className?.toString(),
                    textNodeClickable = node.isClickable,
                )
            }
        }
        return null
    }

    /**
     * 見つけたノードの周辺情報。押す直前のログに載せる（要件4）。
     *
     * **カード番号を含みうるので、出す前に必ず `LogRedactor` を通すこと。**
     */
    fun describe(match: Match): String {
        val n = match.node
        val p1 = runCatching { n.parent }.getOrNull()
        val p2 = runCatching { p1?.parent }.getOrNull()
        return buildString {
            append("text=${match.matchedText}")
            append(" className=${match.textClassName}")
            append(" isClickable=${n.isClickable}")
            append(" isEnabled=${n.isEnabled}")
            append(" actions=${actionNames(n)}")
            append(" parent1=${p1?.className}")
            append(" parent1Clickable=${p1?.isClickable}")
            append(" parent2=${p2?.className}")
            append(" parent2Clickable=${p2?.isClickable}")
            append(" depth=${match.ancestorDepth}")
        }
    }

    private fun actionNames(node: AccessibilityNodeInfo): String = runCatching {
        node.actionList.joinToString(",") { a ->
            when (a.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> "LONG_CLICK"
                AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
                AccessibilityNodeInfo.ACTION_SELECT -> "SELECT"
                else -> a.id.toString()
            }
        }
    }.getOrDefault("?")

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

    /**
     * 祖先を「ボタン1個ぶん」とみなす、子孫テキスト長の倍率と許容幅。
     *
     * 「****9804でチャージ」なら正規化後で14文字ほど。4倍+40 で96文字まで許す。
     * 画面全体を包むコンテナは数百文字になるので、これで切り分けられる。
     */
    private const val ANCESTOR_TEXT_RATIO = 4
    private const val ANCESTOR_TEXT_SLACK = 40
}
