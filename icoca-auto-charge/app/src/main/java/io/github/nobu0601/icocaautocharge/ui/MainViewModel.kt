package io.github.nobu0601.icocaautocharge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.nobu0601.icocaautocharge.ServiceLocator
import io.github.nobu0601.icocaautocharge.accessibility.AccessibilityBridge
import io.github.nobu0601.icocaautocharge.accessibility.ScreenDump
import io.github.nobu0601.icocaautocharge.balance.SecureElementProbe
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.data.db.ChargeHistoryEntity
import io.github.nobu0601.icocaautocharge.data.settings.AppSettings
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType
import io.github.nobu0601.icocaautocharge.domain.ChargeAttempt
import io.github.nobu0601.icocaautocharge.icoca.IcocaAppProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val settings: AppSettings = AppSettings(),
    val balance: BalanceReading? = null,
    val attempt: ChargeAttempt? = null,
    val lastCheckAt: Long? = null,
    val lastChargeAt: Long? = null,
    val lastSkipReason: String? = null,
    val history: List<ChargeHistoryEntity> = emptyList(),
    val accessibilityRunning: Boolean = false,
    val icoca: IcocaAppProbe.AppInfo? = null,
    val notificationsEnabled: Boolean = true,
    val chargedTodayYen: Int = 0,
    val chargedThisMonthYen: Int = 0,
    val message: String? = null,
)

class MainViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    private data class TransientState(
        val icoca: IcocaAppProbe.AppInfo? = null,
        val notificationsEnabled: Boolean = true,
        val chargedTodayYen: Int = 0,
        val chargedThisMonthYen: Int = 0,
        val message: String? = null,
    )

    /**
     * 9本の Flow を一度に combine すると型が Array<Any?> に落ちて安全でないため、
     * 型付きオーバーロードが効く5本以下ずつに分けて畳んでいる。
     */
    private data class CoreState(
        val settings: AppSettings,
        val balance: BalanceReading?,
        val attempt: ChargeAttempt?,
        val history: List<ChargeHistoryEntity>,
        val accessibilityRunning: Boolean,
    )

    private data class TimingState(
        val lastCheckAt: Long?,
        val lastChargeAt: Long?,
        val lastSkipReason: String?,
    )

    private val core = combine(
        locator.settingsRepo.settings,
        locator.flowState.lastBalance,
        locator.flowState.attempt,
        locator.history.observeHistory(),
        AccessibilityBridge.connected,
    ) { settings, balance, attempt, history, accessibility ->
        CoreState(settings, balance, attempt, history, accessibility)
    }

    private val timing = combine(
        locator.flowState.lastCheckAt,
        locator.flowState.lastChargeAt,
        locator.flowState.lastSkipReason,
    ) { checkAt, chargeAt, skip -> TimingState(checkAt, chargeAt, skip) }

    val state: StateFlow<UiState> = combine(core, timing, transient) { c, t, tr ->
        UiState(
            settings = c.settings,
            balance = c.balance,
            attempt = c.attempt,
            history = c.history,
            accessibilityRunning = c.accessibilityRunning,
            lastCheckAt = t.lastCheckAt,
            lastChargeAt = t.lastChargeAt,
            lastSkipReason = t.lastSkipReason,
            icoca = tr.icoca,
            notificationsEnabled = tr.notificationsEnabled,
            chargedTodayYen = tr.chargedTodayYen,
            chargedThisMonthYen = tr.chargedThisMonthYen,
            message = tr.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _dump: StateFlow<ScreenDump?> = AccessibilityBridge.lastDump
    val dump: StateFlow<ScreenDump?> get() = _dump

    /** 自動操作が何を見て何をしたか。実機で止まった原因を追うために出す。 */
    val trace: StateFlow<List<String>> get() = AccessibilityBridge.trace

    private val _probeReport = MutableStateFlow<String?>(null)
    val probeReport: StateFlow<String?> = _probeReport.asStateFlow()

    init {
        refreshEnvironment()
    }

    fun refreshEnvironment() = viewModelScope.launch {
        runCatching {
            val now = System.currentTimeMillis()
            transient.value = transient.value.copy(
                icoca = locator.probe.detect(),
                notificationsEnabled = locator.notifications.canPost(),
                chargedTodayYen = locator.history.chargedTodayYen(now),
                chargedThisMonthYen = locator.history.chargedThisMonthYen(now),
            )
        }.onFailure { SecureLog.e("failed to refresh environment", it) }
    }

    fun checkNow() = viewModelScope.launch {
        runCatching {
            val result = locator.coordinator.runCheck("ui")
            val text = when (val d = result.decision) {
                is io.github.nobu0601.icocaautocharge.domain.ChargeDecision.Proceed ->
                    "チャージ処理を開始しました"
                is io.github.nobu0601.icocaautocharge.domain.ChargeDecision.Skip ->
                    d.reason.message
            }
            transient.value = transient.value.copy(message = text)
            refreshEnvironment()
        }.onFailure {
            SecureLog.e("manual check failed", it)
            transient.value = transient.value.copy(message = "確認に失敗しました")
        }
    }

    fun saveSettings(settings: AppSettings) = viewModelScope.launch {
        val errors = settings.validate()
        if (errors.isNotEmpty()) {
            transient.value = transient.value.copy(message = errors.first())
            return@launch
        }
        locator.settingsRepo.save(settings)
        locator.scheduler.reschedule(settings)
        transient.value = transient.value.copy(message = "設定を保存しました")
    }

    fun submitManualBalance(yen: Int) = viewModelScope.launch {
        runCatching {
            locator.balanceRepo.submit(
                BalanceReading(yen, System.currentTimeMillis(), BalanceSourceType.MANUAL),
            )
            transient.value = transient.value.copy(message = "残高を登録しました")
        }.onFailure { transient.value = transient.value.copy(message = "登録できませんでした") }
    }

    fun submitNfcBalance(yen: Int) = viewModelScope.launch {
        locator.balanceRepo.submit(
            BalanceReading(yen, System.currentTimeMillis(), BalanceSourceType.NFC),
        )
        transient.value = transient.value.copy(message = "カードから残高を読み取りました")
    }

    /**
     * 疑似残高を設定する。
     *
     * 直接 [locator.balanceRepo] へ書き込むのではなく [FlowStateRepository.setDebugOverride]
     * を使う。こうすることで「監視を1回実行」を押すまでは値がチェーンに現れず、
     * 実行した瞬間に**1回だけ**最優先で使われて消える
     * （[io.github.nobu0601.icocaautocharge.balance.SimulatedBalanceSource] 参照）。
     * これにより、ユーザー補助が直前に読んだ実際の残高に負けて
     * テストにならない、という事故を防いでいる。
     */
    fun submitSimulatedBalance(yen: Int) = viewModelScope.launch {
        locator.flowState.setDebugOverride(yen, System.currentTimeMillis())
        transient.value = transient.value.copy(
            message = "疑似残高${yen}円を設定しました。「監視を1回実行」を押すと1回だけ使われます。",
        )
    }

    fun clearHistory() = viewModelScope.launch {
        locator.history.clearHistory()
        transient.value = transient.value.copy(message = "履歴を消去しました")
    }

    fun resetState() = viewModelScope.launch {
        locator.coordinator.resetState()
        transient.value = transient.value.copy(message = "状態をリセットしました")
    }

    /** クールダウンを解除する（テスト専用）。実機テストを繰り返すたびに6時間待たずに済む。 */
    fun clearCooldown() = viewModelScope.launch {
        locator.coordinator.clearCooldownForTesting()
        transient.value = transient.value.copy(message = "クールダウンを解除しました")
    }

    fun consumeMessage() {
        transient.value = transient.value.copy(message = null)
    }

    fun setDumpEnabled(enabled: Boolean) {
        AccessibilityBridge.dumpEnabled = enabled
        if (!enabled) AccessibilityBridge.clearDump()
    }

    fun setDryRun(enabled: Boolean) {
        AccessibilityBridge.dryRun = enabled
    }

    /** ICOCA アプリの公開コンポーネントを列挙する（TECHNICAL_FEASIBILITY §3.2）。 */
    fun runIcocaProbe() = viewModelScope.launch {
        runCatching {
            val info = locator.probe.detect()
            val activities = locator.probe.listExportedActivities()
            val schemes = locator.probe.probeUrlSchemes()
            _probeReport.value = buildString {
                appendLine("== ICOCAアプリ ==")
                appendLine("installed: ${info.installed}")
                appendLine("versionName: ${info.versionName}")
                appendLine("versionCode: ${info.versionCode}")
                appendLine("signature(SHA-256): ${info.signatureSha256}")
                appendLine()
                appendLine("== exported Activity (${activities.size}) ==")
                activities.forEach { a ->
                    appendLine("- ${a.className}")
                    if (a.actions.isNotEmpty()) appendLine("    actions: ${a.actions}")
                    if (a.categories.isNotEmpty()) appendLine("    categories: ${a.categories.distinct()}")
                }
                appendLine()
                appendLine("== 解決できたURLスキーム ==")
                if (schemes.isEmpty()) appendLine("(なし)") else schemes.forEach { appendLine("- $it://") }
            }
        }.onFailure {
            SecureLog.e("icoca probe failed", it)
            _probeReport.value = "調査に失敗しました: ${it.javaClass.simpleName}"
        }
    }

    /** OMAPI の到達性を調べる（TECHNICAL_FEASIBILITY §3.6）。 */
    fun runSecureElementProbe() = viewModelScope.launch {
        val report: SecureElementProbe.Report = locator.secureElementProbe.probe()
        _probeReport.value = buildString {
            appendLine("== OMAPI ==")
            appendLine("supported: ${report.supported}")
            appendLine("connected: ${report.connected}")
            appendLine("readers: ${report.readers}")
            appendLine(report.note)
        }
    }

    class Factory(private val locator: ServiceLocator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(locator) as T
    }
}
