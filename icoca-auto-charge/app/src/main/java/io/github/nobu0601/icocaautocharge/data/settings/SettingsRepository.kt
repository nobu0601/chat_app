package io.github.nobu0601.icocaautocharge.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** [AppSettings] の永続化。既定値は [AppSettings] のコンストラクタ既定値に従う。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val monitoring = booleanPreferencesKey("monitoring_enabled")
        val threshold = intPreferencesKey("threshold_yen")
        val amount = intPreferencesKey("charge_amount_yen")
        val daily = intPreferencesKey("daily_limit_yen")
        val monthly = intPreferencesKey("monthly_limit_yen")
        val interval = intPreferencesKey("min_charge_interval_hours")
        val confirm = booleanPreferencesKey("confirm_before_charge")
        val autoConfirmPayment = booleanPreferencesKey("auto_confirm_payment")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val chargingOnly = booleanPreferencesKey("charging_only")
        val checkInterval = intPreferencesKey("check_interval_hours")
        val automation = booleanPreferencesKey("automation_enabled")
        val consent = booleanPreferencesKey("automation_consented")
        val balanceMaxAge = intPreferencesKey("balance_max_age_hours")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            monitoringEnabled = p[Keys.monitoring] ?: d.monitoringEnabled,
            thresholdYen = p[Keys.threshold] ?: d.thresholdYen,
            chargeAmountYen = p[Keys.amount] ?: d.chargeAmountYen,
            dailyLimitYen = p[Keys.daily] ?: d.dailyLimitYen,
            monthlyLimitYen = p[Keys.monthly] ?: d.monthlyLimitYen,
            minChargeIntervalHours = p[Keys.interval] ?: d.minChargeIntervalHours,
            confirmBeforeCharge = p[Keys.confirm] ?: d.confirmBeforeCharge,
            autoConfirmPayment = p[Keys.autoConfirmPayment] ?: d.autoConfirmPayment,
            wifiOnly = p[Keys.wifiOnly] ?: d.wifiOnly,
            chargingOnly = p[Keys.chargingOnly] ?: d.chargingOnly,
            checkIntervalHours = p[Keys.checkInterval] ?: d.checkIntervalHours,
            automationEnabled = p[Keys.automation] ?: d.automationEnabled,
            automationConsented = p[Keys.consent] ?: d.automationConsented,
            balanceMaxAgeHours = p[Keys.balanceMaxAge] ?: d.balanceMaxAgeHours,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(s: AppSettings) {
        context.settingsDataStore.edit { p ->
            p[Keys.monitoring] = s.monitoringEnabled
            p[Keys.threshold] = s.thresholdYen
            p[Keys.amount] = s.chargeAmountYen
            p[Keys.daily] = s.dailyLimitYen
            p[Keys.monthly] = s.monthlyLimitYen
            p[Keys.interval] = s.minChargeIntervalHours
            p[Keys.confirm] = s.confirmBeforeCharge
            p[Keys.autoConfirmPayment] = s.autoConfirmPayment
            p[Keys.wifiOnly] = s.wifiOnly
            p[Keys.chargingOnly] = s.chargingOnly
            p[Keys.checkInterval] = s.checkIntervalHours
            p[Keys.automation] = s.automationEnabled
            p[Keys.consent] = s.automationConsented
            p[Keys.balanceMaxAge] = s.balanceMaxAgeHours
        }
    }
}
