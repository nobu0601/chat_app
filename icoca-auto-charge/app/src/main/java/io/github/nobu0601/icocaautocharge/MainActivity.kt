package io.github.nobu0601.icocaautocharge

import android.Manifest
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.nobu0601.icocaautocharge.balance.FelicaBalanceReader
import io.github.nobu0601.icocaautocharge.core.SecureLog
import io.github.nobu0601.icocaautocharge.icoca.IcocaLauncher
import io.github.nobu0601.icocaautocharge.monitor.ChargeConfirmActivity
import io.github.nobu0601.icocaautocharge.ui.MainViewModel
import io.github.nobu0601.icocaautocharge.ui.dashboard.DashboardScreen
import io.github.nobu0601.icocaautocharge.ui.debug.DebugScreen
import io.github.nobu0601.icocaautocharge.ui.history.HistoryScreen
import io.github.nobu0601.icocaautocharge.ui.settings.SettingsScreen
import io.github.nobu0601.icocaautocharge.ui.theme.IcocaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val locator: ServiceLocator?
        get() = IcocaApp.locator

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(requireNotNull(locator) { "ServiceLocator is not initialised" })
    }

    private val workState = MutableStateFlow("未取得")

    /** NFC 読み取り中かどうか。Debug 画面のボタンで有効化する。 */
    private var nfcReaderActive = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshEnvironment()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locator?.let { loc ->
            lifecycleScope.launch {
                runCatching {
                    loc.scheduler.observePeriodicState().collect { workState.value = it }
                }.onFailure { SecureLog.e("failed to observe work state", it) }
            }
        }

        setContent {
            IcocaTheme {
                AppRoot(
                    viewModel = viewModel,
                    workStateFlow = workState,
                    onStartCharge = { startActivity(ChargeConfirmActivity.intent(this)) },
                    onOpenIcoca = { openIcoca() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onRequestNotificationPermission = { requestNotificationPermission() },
                    onStartNfcRead = { startNfcRead() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
    }

    override fun onPause() {
        super.onPause()
        stopNfcRead()
    }

    private fun openIcoca() {
        val result = locator?.launcher?.launchMain() ?: return
        if (result is IcocaLauncher.Result.Failed) {
            Toast.makeText(this, R.string.warn_icoca_missing, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * ユーザー補助の設定画面を開く。
     *
     * 自動で有効化することはできないし、してはいけない。
     * ユーザー自身が設定画面で明示的に有効にする方式にしている（指示書 §18）。
     */
    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure { SecureLog.e("failed to open accessibility settings", it) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    /**
     * 物理 ICOCA カードの読み取りを始める（TECHNICAL_FEASIBILITY §3.5）。
     *
     * これで読めるのは **かざした物理カード** のみ。
     * 自端末のモバイルICOCAは読めない（PROJECT_RESEARCH §2.2）。
     */
    private fun startNfcRead() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            Toast.makeText(this, "この端末はNFCに対応していません", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "NFCが無効です。設定から有効にしてください", Toast.LENGTH_LONG).show()
            return
        }
        adapter.enableReaderMode(
            this,
            { tag ->
                when (val result = FelicaBalanceReader.read(tag)) {
                    is FelicaBalanceReader.Result.Success -> {
                        viewModel.submitNfcBalance(result.balanceYen)
                        runOnUiThread {
                            Toast.makeText(this, "残高 ${result.balanceYen} 円", Toast.LENGTH_LONG).show()
                        }
                    }
                    is FelicaBalanceReader.Result.Failure -> runOnUiThread {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        nfcReaderActive = true
        Toast.makeText(this, "ICOCAカードを端末の背面にかざしてください", Toast.LENGTH_LONG).show()
    }

    private fun stopNfcRead() {
        if (!nfcReaderActive) return
        runCatching { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) }
        nfcReaderActive = false
    }
}

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    DASHBOARD(R.string.nav_dashboard, Icons.Filled.Home),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
    HISTORY(R.string.nav_history, Icons.Filled.History),
    DEBUG(R.string.nav_debug, Icons.Filled.BugReport),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    viewModel: MainViewModel,
    workStateFlow: MutableStateFlow<String>,
    onStartCharge: () -> Unit,
    onOpenIcoca: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartNfcRead: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dump by viewModel.dump.collectAsStateWithLifecycle()
    val trace by viewModel.trace.collectAsStateWithLifecycle()
    val probeReport by viewModel.probeReport.collectAsStateWithLifecycle()
    val workState by workStateFlow.collectAsStateWithLifecycle()

    val tabs = remember {
        if (BuildConfig.DEBUG_SCREEN_ENABLED) Tab.entries else Tab.entries.filter { it != Tab.DEBUG }
    }
    var selected by rememberSaveable { mutableStateOf(Tab.DASHBOARD.name) }
    val current = tabs.firstOrNull { it.name == selected } ?: Tab.DASHBOARD

    var dumpEnabled by rememberSaveable { mutableStateOf(false) }
    var dryRun by rememberSaveable { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dash_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = { selected = tab.name },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (current) {
                Tab.DASHBOARD -> DashboardScreen(
                    state = state,
                    onCheckNow = { viewModel.checkNow() },
                    onStartCharge = onStartCharge,
                    onOpenIcoca = onOpenIcoca,
                    onSubmitManualBalance = { viewModel.submitManualBalance(it) },
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                )
                Tab.SETTINGS -> SettingsScreen(
                    settings = state.settings,
                    accessibilityRunning = state.accessibilityRunning,
                    onSave = { viewModel.saveSettings(it) },
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                )
                Tab.HISTORY -> HistoryScreen(
                    history = state.history,
                    onClear = { viewModel.clearHistory() },
                )
                Tab.DEBUG -> DebugScreen(
                    state = state,
                    workState = workState,
                    dump = dump,
                    trace = trace,
                    probeReport = probeReport,
                    dumpEnabled = dumpEnabled,
                    dryRun = dryRun,
                    onDumpEnabledChange = {
                        dumpEnabled = it
                        viewModel.setDumpEnabled(it)
                    },
                    onDryRunChange = {
                        dryRun = it
                        viewModel.setDryRun(it)
                    },
                    onRunIcocaProbe = { viewModel.runIcocaProbe() },
                    onRunSecureElementProbe = { viewModel.runSecureElementProbe() },
                    onStartNfcRead = onStartNfcRead,
                    onSimulateBalance = { viewModel.submitSimulatedBalance(it) },
                    onResetState = { viewModel.resetState() },
                    onClearCooldown = { viewModel.clearCooldown() },
                    onRunCheck = { viewModel.checkNow() },
                )
            }
        }
    }
}
