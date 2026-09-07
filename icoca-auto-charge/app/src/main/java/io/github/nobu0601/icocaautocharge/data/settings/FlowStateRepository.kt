package io.github.nobu0601.icocaautocharge.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.nobu0601.icocaautocharge.domain.AutomationMethod
import io.github.nobu0601.icocaautocharge.domain.BalanceReading
import io.github.nobu0601.icocaautocharge.domain.BalanceSourceType
import io.github.nobu0601.icocaautocharge.domain.ChargeAttempt
import io.github.nobu0601.icocaautocharge.domain.ChargeState
import io.github.nobu0601.icocaautocharge.domain.ErrorReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.flowStateDataStore: DataStore<Preferences> by preferencesDataStore("flow_state")

/**
 * チャージ試行の状態を端末に残す。
 *
 * **プロセスが死んでも端末を再起動しても状態が残ること**が二重チャージ防止の前提
 * （指示書 §21 / TEST_PLAN §3.1-F）。メモリ上だけで持ってはいけない。
 */
class FlowStateRepository(private val context: Context) {

    private object Keys {
        val state = stringPreferencesKey("state")
        val historyId = longPreferencesKey("history_id")
        val startedAt = longPreferencesKey("started_at")
        val updatedAt = longPreferencesKey("updated_at")
        val balanceAtDetection = intPreferencesKey("balance_at_detection")
        val hasBalanceAtDetection = booleanPreferencesKey("has_balance_at_detection")
        val threshold = intPreferencesKey("threshold")
        val chargeAmount = intPreferencesKey("charge_amount")
        val method = stringPreferencesKey("automation_method")
        val userConfirmed = booleanPreferencesKey("user_confirmed")
        val errorReason = stringPreferencesKey("error_reason")

        val lastTerminalAt = longPreferencesKey("last_terminal_at")
        val lastCheckAt = longPreferencesKey("last_check_at")
        val lastChargeAt = longPreferencesKey("last_charge_at")

        val lastBalance = intPreferencesKey("last_balance")
        val lastBalanceAt = longPreferencesKey("last_balance_at")
        val lastBalanceSource = stringPreferencesKey("last_balance_source")

        val lastSkipReason = stringPreferencesKey("last_skip_reason")

        /** 初回に検出した ICOCA アプリの署名。なりすまし検知に使う。 */
        val icocaSignature = stringPreferencesKey("icoca_signature")
    }

    val attempt: Flow<ChargeAttempt?> = context.flowStateDataStore.data.map { it.toAttempt() }

    val lastBalance: Flow<BalanceReading?> = context.flowStateDataStore.data.map { p ->
        val yen = p[Keys.lastBalance] ?: return@map null
        val at = p[Keys.lastBalanceAt] ?: return@map null
        val src = p[Keys.lastBalanceSource]
            ?.let { runCatching { BalanceSourceType.valueOf(it) }.getOrNull() }
            ?: BalanceSourceType.MANUAL
        BalanceReading(yen, at, src)
    }

    val lastTerminalAt: Flow<Long?> = context.flowStateDataStore.data.map { it[Keys.lastTerminalAt] }
    val lastCheckAt: Flow<Long?> = context.flowStateDataStore.data.map { it[Keys.lastCheckAt] }
    val lastChargeAt: Flow<Long?> = context.flowStateDataStore.data.map { it[Keys.lastChargeAt] }
    val lastSkipReason: Flow<String?> = context.flowStateDataStore.data.map { it[Keys.lastSkipReason] }

    suspend fun currentAttempt(): ChargeAttempt? = attempt.first()
    suspend fun currentLastBalance(): BalanceReading? = lastBalance.first()
    suspend fun currentLastTerminalAt(): Long? = lastTerminalAt.first()

    suspend fun saveAttempt(a: ChargeAttempt) {
        context.flowStateDataStore.edit { p ->
            p[Keys.state] = a.state.name
            p[Keys.historyId] = a.historyId
            p[Keys.startedAt] = a.startedAt
            p[Keys.updatedAt] = a.updatedAt
            p[Keys.hasBalanceAtDetection] = a.balanceAtDetection != null
            p[Keys.balanceAtDetection] = a.balanceAtDetection ?: 0
            p[Keys.threshold] = a.threshold
            p[Keys.chargeAmount] = a.chargeAmount
            p[Keys.method] = a.automationMethod.name
            p[Keys.userConfirmed] = a.userConfirmed
            if (a.errorReason != null) p[Keys.errorReason] = a.errorReason.name else p.remove(Keys.errorReason)

            if (a.state.isTerminal) p[Keys.lastTerminalAt] = a.updatedAt
            if (a.state == ChargeState.SUCCESS) p[Keys.lastChargeAt] = a.updatedAt
        }
    }

    /** 試行を破棄して IDLE に戻す。クールダウンの起点は残したままにする。 */
    suspend fun clearAttempt() {
        context.flowStateDataStore.edit { p ->
            p.remove(Keys.state)
            p.remove(Keys.historyId)
            p.remove(Keys.startedAt)
            p.remove(Keys.updatedAt)
            p.remove(Keys.hasBalanceAtDetection)
            p.remove(Keys.balanceAtDetection)
            p.remove(Keys.threshold)
            p.remove(Keys.chargeAmount)
            p.remove(Keys.method)
            p.remove(Keys.userConfirmed)
            p.remove(Keys.errorReason)
        }
    }

    suspend fun saveBalance(reading: BalanceReading) {
        context.flowStateDataStore.edit { p ->
            p[Keys.lastBalance] = reading.balanceYen
            p[Keys.lastBalanceAt] = reading.observedAt
            p[Keys.lastBalanceSource] = reading.source.name
        }
    }

    val icocaSignature: Flow<String?> = context.flowStateDataStore.data.map { it[Keys.icocaSignature] }

    suspend fun currentIcocaSignature(): String? = icocaSignature.first()

    /**
     * ICOCA アプリの署名を覚える。
     *
     * 初回だけ記録し、以後は変更しない。後から書き換えられるようにすると、
     * なりすましアプリの署名を正として上書きされてしまい、検査の意味が無くなるため。
     */
    suspend fun rememberIcocaSignatureIfAbsent(signature: String) {
        context.flowStateDataStore.edit { p ->
            if (p[Keys.icocaSignature] == null) p[Keys.icocaSignature] = signature
        }
    }

    suspend fun saveCheckedAt(millis: Long, skipReason: String?) {
        context.flowStateDataStore.edit { p ->
            p[Keys.lastCheckAt] = millis
            if (skipReason != null) p[Keys.lastSkipReason] = skipReason else p.remove(Keys.lastSkipReason)
        }
    }

    private fun Preferences.toAttempt(): ChargeAttempt? {
        val state = this[Keys.state]
            ?.let { runCatching { ChargeState.valueOf(it) }.getOrNull() }
            ?: return null
        return ChargeAttempt(
            historyId = this[Keys.historyId] ?: 0L,
            state = state,
            startedAt = this[Keys.startedAt] ?: 0L,
            updatedAt = this[Keys.updatedAt] ?: 0L,
            balanceAtDetection = if (this[Keys.hasBalanceAtDetection] == true) {
                this[Keys.balanceAtDetection]
            } else {
                null
            },
            threshold = this[Keys.threshold] ?: 0,
            chargeAmount = this[Keys.chargeAmount] ?: 0,
            automationMethod = this[Keys.method]
                ?.let { runCatching { AutomationMethod.valueOf(it) }.getOrNull() }
                ?: AutomationMethod.NONE,
            userConfirmed = this[Keys.userConfirmed] ?: false,
            errorReason = this[Keys.errorReason]
                ?.let { runCatching { ErrorReason.valueOf(it) }.getOrNull() },
        )
    }
}
