package io.github.nobu0601.icocaautocharge

import android.content.Context
import io.github.nobu0601.icocaautocharge.balance.AccessibilityBalanceSource
import io.github.nobu0601.icocaautocharge.balance.BalanceRepository
import io.github.nobu0601.icocaautocharge.balance.IntentBalanceSource
import io.github.nobu0601.icocaautocharge.balance.ManualBalanceSource
import io.github.nobu0601.icocaautocharge.balance.SecureElementProbe
import io.github.nobu0601.icocaautocharge.data.db.AppDatabase
import io.github.nobu0601.icocaautocharge.data.repo.HistoryRepository
import io.github.nobu0601.icocaautocharge.data.settings.FlowStateRepository
import io.github.nobu0601.icocaautocharge.data.settings.SettingsRepository
import io.github.nobu0601.icocaautocharge.icoca.IcocaAppProbe
import io.github.nobu0601.icocaautocharge.icoca.IcocaLauncher
import io.github.nobu0601.icocaautocharge.monitor.ChargeFlowCoordinator
import io.github.nobu0601.icocaautocharge.monitor.DeviceConditions
import io.github.nobu0601.icocaautocharge.monitor.MonitorScheduler
import io.github.nobu0601.icocaautocharge.notify.Notifications

/**
 * 依存の解決（ARCHITECTURE §12）。
 *
 * Hilt を入れていないのは意図的。KSP のプロセッサを増やすとビルドが脆くなり、
 * この規模（シングルトン10個ほど）では手で組んだ方が読みやすいため。
 */
class ServiceLocator(context: Context) {

    private val appContext = context.applicationContext

    val settingsRepo = SettingsRepository(appContext)
    val flowState = FlowStateRepository(appContext)

    private val db = AppDatabase.get(appContext)
    val history = HistoryRepository(db.chargeHistoryDao(), db.balanceSampleDao())

    val probe = IcocaAppProbe(appContext)
    val launcher = IcocaLauncher(appContext)
    val notifications = Notifications(appContext)
    val scheduler = MonitorScheduler(appContext)
    val conditions = DeviceConditions(appContext)
    val secureElementProbe = SecureElementProbe(appContext)

    val balanceRepo = BalanceRepository(
        sources = listOf(
            IntentBalanceSource(probe),
            AccessibilityBalanceSource(),
            ManualBalanceSource(flowState),
        ),
        flowState = flowState,
        history = history,
    )

    val coordinator = ChargeFlowCoordinator(
        settingsRepo = settingsRepo,
        flowState = flowState,
        history = history,
        balanceRepo = balanceRepo,
        notifications = notifications,
        launcher = launcher,
        probe = probe,
        conditions = conditions,
    )

    /**
     * ICOCA アプリの署名。ユーザー補助サービスは同期的にこれを必要とするため、
     * 起動時に読んでキャッシュしておく。
     */
    @Volatile
    var cachedIcocaSignature: String? = null
        internal set
}
