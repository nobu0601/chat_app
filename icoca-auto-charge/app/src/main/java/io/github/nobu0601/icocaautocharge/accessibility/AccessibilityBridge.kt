package io.github.nobu0601.icocaautocharge.accessibility

import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * ユーザー補助サービスと、アプリ本体（Worker / UI）の間の連絡口。
 *
 * サービスはシステムが生成・破棄するため直接 new できない。
 * ここに弱参照を置き、生存しているときだけ操作を依頼する。
 *
 * 自動操作の記録（画面履歴・操作ログ・進行状況）もここに集める。
 * **いずれもメモリ上にのみ保持し、永続化しない**（指示書 §17）。
 */
object AccessibilityBridge {

    private var serviceRef: WeakReference<IcocaAccessibilityService>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastScreen = MutableStateFlow(IcocaScreen.UNKNOWN)
    val lastScreen: StateFlow<IcocaScreen> = _lastScreen.asStateFlow()

    private val _lastBalance = MutableStateFlow<BalanceReading?>(null)
    val lastBalance: StateFlow<BalanceReading?> = _lastBalance.asStateFlow()

    /** Debug 画面用の直近ダンプ。 */
    private val _lastDump = MutableStateFlow<ScreenDump?>(null)
    val lastDump: StateFlow<ScreenDump?> = _lastDump.asStateFlow()

    /** ICOCA が前面にいるか。Debug 画面の表示用（改修指示 §19）。 */
    private val _icocaForeground = MutableStateFlow(false)
    val icocaForeground: StateFlow<Boolean> = _icocaForeground.asStateFlow()

    /** 自動操作のいまの姿。セッションが無ければ null（改修指示 §19）。 */
    private val _automation = MutableStateFlow<AutomationStatusView?>(null)
    val automation: StateFlow<AutomationStatusView?> = _automation.asStateFlow()

    /** 直近 [MAX_SNAPSHOTS] 件の画面履歴。新しいものが先頭（改修指示 §18）。 */
    private val _snapshots = MutableStateFlow<List<SnapshotRecord>>(emptyList())
    val snapshots: StateFlow<List<SnapshotRecord>> = _snapshots.asStateFlow()

    /** 操作ログ。新しいものが末尾（改修指示 §20）。 */
    private val _log = MutableStateFlow<List<AutomationLogEntry>>(emptyList())
    val log: StateFlow<List<AutomationLogEntry>> = _log.asStateFlow()

    /** Debug 設定。ドライラン中はクリックを実行せず、押す予定だけを記録する。 */
    @Volatile var dryRun: Boolean = false

    @Volatile var dumpEnabled: Boolean = false

    internal fun onServiceConnected(service: IcocaAccessibilityService) {
        serviceRef = WeakReference(service)
        _connected.value = true
    }

    internal fun onServiceDisconnected() {
        serviceRef = null
        _connected.value = false
        _lastScreen.value = IcocaScreen.UNKNOWN
        _icocaForeground.value = false
    }

    internal fun publishScreen(screen: IcocaScreen) {
        _lastScreen.value = screen
    }

    internal fun publishBalance(reading: BalanceReading) {
        _lastBalance.value = reading
    }

    internal fun publishDump(dump: ScreenDump) {
        _lastDump.value = dump
    }

    internal fun publishForeground(foreground: Boolean) {
        _icocaForeground.value = foreground
    }

    internal fun publishAutomation(view: AutomationStatusView?) {
        _automation.value = view
    }

    internal fun addSnapshot(record: SnapshotRecord) {
        _snapshots.value = (listOf(record) + _snapshots.value).take(MAX_SNAPSHOTS)
    }

    internal fun addLog(entry: AutomationLogEntry) {
        _log.value = (_log.value + entry).takeLast(MAX_LOG_LINES)
    }

    /** 新しいセッションを始める。1回のフローだけを見たいので前回の記録は捨てる。 */
    internal fun resetRecords() {
        _snapshots.value = emptyList()
        _log.value = emptyList()
    }

    fun service(): IcocaAccessibilityService? = serviceRef?.get()

    fun isRunning(): Boolean = serviceRef?.get() != null

    fun clearDump() {
        _lastDump.value = null
    }

    /** 直近50件の画面履歴（改修指示 §18）。 */
    const val MAX_SNAPSHOTS = 50

    /** 操作ログの保持行数。 */
    const val MAX_LOG_LINES = 200
}

/**
 * Debug 画面に出す自動操作の現況（改修指示 §19）。
 *
 * 可変の [AutomationSession] をそのまま UI に渡すと、
 * Compose が再構成のたびに違う値を見ることになる。読み取り専用の写しを配る。
 */
data class AutomationStatusView(
    val sessionId: String,
    val attemptId: Long,
    val status: AutomationStatus,
    val state: AutomationState,
    val screen: IcocaScreen,
    val currentStep: Int,
    val lastAction: AutomationAction?,
    val lastActionAt: Long,
    /** `performAction` の戻り値。true でも画面が進んだ保証にはならない。 */
    val lastActionResult: Boolean?,
    val matchedText: String?,
    val matchedNodeClass: String?,
    val matchedNodeClickable: Boolean?,
    /** 文字のノードから押す相手まで遡った階層数。 */
    val clickableAncestorDepth: Int?,
    val lastScreenChangeAt: Long,
    val unknownSince: Long?,
    val unknownDurationMillis: Long,
    val consecutiveUnknown: Int,
    /** いまの状態に許された時間。上限が無い状態なら null。 */
    val currentTimeoutMillis: Long?,
    /** いまの状態にとどまっている時間。 */
    val millisInState: Long,
    val elapsedMillis: Long,
    val dryRun: Boolean,
    val autoConfirmPayment: Boolean,
) {
    companion object {
        fun of(session: AutomationSession, now: Long) = AutomationStatusView(
            sessionId = session.sessionId,
            attemptId = session.attemptId,
            status = session.status,
            state = session.state,
            screen = session.currentScreen,
            currentStep = session.currentStep,
            lastAction = session.lastAction,
            lastActionAt = session.lastActionAt,
            lastActionResult = session.lastActionResult,
            matchedText = session.lastMatch?.matchedText ?: session.lastMatch?.label,
            matchedNodeClass = session.lastMatch?.matchedClassName,
            matchedNodeClickable = session.lastMatch?.matchedNodeClickable,
            clickableAncestorDepth = session.lastMatch?.ancestorDepth,
            lastScreenChangeAt = session.lastScreenChangeAt,
            unknownSince = session.unknownSince,
            unknownDurationMillis = session.unknownDurationMillis(now),
            consecutiveUnknown = session.consecutiveUnknown,
            currentTimeoutMillis = AutomationTimeouts.forState(session.state),
            millisInState = session.millisInState(now),
            elapsedMillis = session.millisSinceStart(now),
            dryRun = session.dryRun,
            autoConfirmPayment = session.autoConfirmPayment,
        )
    }
}

/** Debug 画面に出す画面ダンプ。永続化しない。 */
data class ScreenDump(
    val capturedAt: Long,
    val packageName: String,
    val screen: IcocaScreen,
    val nodes: List<NodeSummary>,
)

data class NodeSummary(
    val className: String?,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val editable: Boolean,
)
