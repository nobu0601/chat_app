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
 */
object AccessibilityBridge {

    private var serviceRef: WeakReference<IcocaAccessibilityService>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastScreen = MutableStateFlow(IcocaScreen.UNKNOWN)
    val lastScreen: StateFlow<IcocaScreen> = _lastScreen.asStateFlow()

    private val _lastBalance = MutableStateFlow<BalanceReading?>(null)
    val lastBalance: StateFlow<BalanceReading?> = _lastBalance.asStateFlow()

    /** Debug 画面用の直近ダンプ。**メモリ上にのみ保持し、永続化しない**（指示書 §17）。 */
    private val _lastDump = MutableStateFlow<ScreenDump?>(null)
    val lastDump: StateFlow<ScreenDump?> = _lastDump.asStateFlow()

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

    fun service(): IcocaAccessibilityService? = serviceRef?.get()

    fun isRunning(): Boolean = serviceRef?.get() != null

    fun clearDump() {
        _lastDump.value = null
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
