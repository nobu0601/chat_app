package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.core.LogRedactor

/**
 * ある瞬間の ICOCA 画面（改修指示 §4, §18）。
 *
 * エンジンはこれだけを見て判断する。`AccessibilityNodeInfo` には触らせない。
 * ノードの探索とクリックは [access] の向こう側に隠してあるので、
 * **エンジン全体が実機なしで JVM のテストから動かせる。**
 * 実機でしか再現しない不具合を、実機なしで回帰テストに固定できるのはこのため。
 */
data class ScreenSnapshot(
    val capturedAt: Long,
    val packageName: String,
    val screen: IcocaScreen,
    /** 画面上の文字列（出現順）。画面判定と金額照合に使う。 */
    val texts: List<String>,
    val nodes: List<NodeSummary>,
    val access: ScreenAccess,
) {
    val nodeCount: Int get() = nodes.size
}

/** 押せるノードの参照。[key] は [ActionThrottle] の同一性判定に使う。 */
data class ClickTarget(
    /** 同じボタンを指すなら毎回同じ値になること。viewId → ラベル → クラス名の順で決める。 */
    val key: String,
    val label: String?,
    /** 照合に使った文字列（ノード自身のテキスト、または子孫を連結したもの）。 */
    val matchedText: String? = null,
    /** 文字を持っていたノードのクラス名。 */
    val matchedClassName: String? = null,
    /** 文字を持っていたノード自身が押せたか。false なら祖先を押す。 */
    val matchedNodeClickable: Boolean = false,
    /** 文字のノードから押す相手まで遡った階層数。0 = 自分自身。 */
    val ancestorDepth: Int = 0,
    /**
     * 押す直前にそのままログへ出す1行（要件4）。
     * **カード番号を含みうるので、出す前に `LogRedactor` を通すこと。**
     */
    val diagnostics: String? = null,
)

/** どのノードを探すか。 */
sealed interface NodeSpec {
    /** 完全一致。「5,000円」を探して「15,000円」を掴まないため。 */
    data class ExactText(
        val candidates: Collection<String>,
        val excludeEditable: Boolean = false,
    ) : NodeSpec

    /** 末尾一致。「****9804でチャージ」のように可変部分が前に来るボタン用。 */
    data class TextSuffix(val suffixes: Collection<String>) : NodeSpec

    /**
     * 部分一致。**見た目のボタンとノード構造が一致しない**ときの主力。
     *
     * 末尾一致だけでは実機で取りこぼした。テキストの後ろに別の語が付くこともあれば、
     * 「****9804」と「でチャージ」が別ノードに割れていることもある。
     */
    data class TextContains(val needles: Collection<String>) : NodeSpec
}

/**
 * 画面上のノードを探して押す手段。
 *
 * 実機では `AccessibilityNodeInfo` を包み、テストでは単なるリストを包む。
 */
interface ScreenAccess {
    fun find(spec: NodeSpec): ClickTarget?

    /** 押せるものの一覧。**診断専用**（押すのには使わない）。 */
    fun clickableLabels(): List<String>

    fun click(target: ClickTarget): Boolean

    /**
     * 直前の [click] で何を試し、それぞれどうだったか。**診断専用。**
     *
     * 押せなかったときに「どの階層まで遡って、それぞれ何を返したか」が分からないと、
     * 実機では手の打ちようがない。集めておきながら出していなかったのが
     * 前回の行き詰まりの原因だった。
     */
    fun lastClickReport(): String? = null

    /**
     * ツリー全体で ACTION_CLICK を公開しているノードの一覧。**診断専用。**
     *
     * 「そもそもこの画面に押せるノードが存在するのか」を確かめるための最後の手段。
     */
    fun clickableInventory(): List<String> = emptyList()
}

/**
 * 画面履歴の1件（改修指示 §18）。
 *
 * 直近1画面だけでは「どう遷移して詰まったのか」が分からない。
 * 実機で止まったとき、その瞬間の画面だけ見ても手遅れなことが多い。
 *
 * **メモリ上にのみ持ち、永続化しない。**
 * [importantTexts] はカード番号を含みうるので、必ず [LogRedactor] を通してから格納する。
 */
data class SnapshotRecord(
    val timestamp: Long,
    val screenType: IcocaScreen,
    val packageName: String,
    val nodeCount: Int,
    val importantTexts: List<String>,
    val currentState: AutomationState,
    val currentStep: Int,
    val lastAction: AutomationAction?,
) {
    companion object {
        /** 履歴に残す文字列の数。全部持つと Debug 画面が読めなくなる。 */
        const val MAX_TEXTS = 8

        fun from(
            snapshot: ScreenSnapshot,
            state: AutomationState,
            step: Int,
            lastAction: AutomationAction?,
        ): SnapshotRecord = SnapshotRecord(
            timestamp = snapshot.capturedAt,
            screenType = snapshot.screen,
            packageName = snapshot.packageName,
            nodeCount = snapshot.nodeCount,
            importantTexts = snapshot.texts
                .filter { it.isNotBlank() }
                .take(MAX_TEXTS)
                .map { LogRedactor.redact(it) },
            currentState = state,
            currentStep = step,
            lastAction = lastAction,
        )
    }
}

/** 操作ログの1行（改修指示 §20）。 */
data class AutomationLogEntry(
    val timestamp: Long,
    val kind: AutomationLogKind,
    /** 既に [LogRedactor] を通した文字列。 */
    val detail: String,
)
