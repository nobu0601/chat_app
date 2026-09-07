package io.github.nobu0601.icocaautocharge.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nobu0601.icocaautocharge.R
import io.github.nobu0601.icocaautocharge.core.Money
import io.github.nobu0601.icocaautocharge.domain.ChargeState
import io.github.nobu0601.icocaautocharge.ui.UiState
import io.github.nobu0601.icocaautocharge.ui.common.LabeledValue
import io.github.nobu0601.icocaautocharge.ui.common.SectionCard
import io.github.nobu0601.icocaautocharge.ui.common.WarningCard
import io.github.nobu0601.icocaautocharge.ui.common.formatTime

@Composable
fun DashboardScreen(
    state: UiState,
    onCheckNow: () -> Unit,
    onStartCharge: () -> Unit,
    onOpenIcoca: () -> Unit,
    onSubmitManualBalance: (Int) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    var showManualInput by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 環境の問題は最上部に出す。ここが赤いままだと機能しないため。
        if (state.icoca?.installed == false) {
            WarningCard(stringResource(R.string.warn_icoca_missing))
        }
        if (!state.notificationsEnabled) {
            WarningCard(
                stringResource(R.string.warn_notification_off),
                stringResource(R.string.warn_notification_request),
                onRequestNotificationPermission,
            )
        }
        if (state.settings.automationEnabled && !state.accessibilityRunning) {
            WarningCard(
                stringResource(R.string.warn_accessibility_off),
                stringResource(R.string.warn_accessibility_open_settings),
                onOpenAccessibilitySettings,
            )
        }

        SectionCard(stringResource(R.string.dash_status)) {
            LabeledValue(
                stringResource(R.string.dash_monitoring),
                if (state.settings.monitoringEnabled) {
                    stringResource(R.string.common_on)
                } else {
                    stringResource(R.string.common_off)
                },
            )
            LabeledValue(
                stringResource(R.string.dash_balance),
                state.balance?.let { Money.format(it.balanceYen) }
                    ?: stringResource(R.string.dash_balance_unknown),
                emphasis = true,
            )
            state.balance?.let {
                LabeledValue(stringResource(R.string.dash_balance_source), sourceLabel(it.source))
                LabeledValue("残高の取得時刻", formatTime(it.observedAt))
            }
            LabeledValue(
                stringResource(R.string.dash_state),
                state.attempt?.state?.let { stateLabel(it) } ?: "待機中",
            )
            LabeledValue(stringResource(R.string.dash_last_check), formatTime(state.lastCheckAt))
        }

        SectionCard(stringResource(R.string.dash_settings)) {
            LabeledValue(
                stringResource(R.string.dash_threshold),
                Money.format(state.settings.thresholdYen),
            )
            LabeledValue(
                stringResource(R.string.dash_amount),
                Money.format(state.settings.chargeAmountYen),
            )
            LabeledValue(
                stringResource(R.string.dash_next_check),
                if (state.settings.monitoringEnabled) {
                    "約${state.settings.checkIntervalHours}時間ごと"
                } else {
                    stringResource(R.string.common_off)
                },
            )
            LabeledValue(
                "本日のチャージ",
                "${Money.format(state.chargedTodayYen)} / ${Money.format(state.settings.dailyLimitYen)}",
            )
            LabeledValue(
                "今月のチャージ",
                "${Money.format(state.chargedThisMonthYen)} / ${Money.format(state.settings.monthlyLimitYen)}",
            )
        }

        state.lastSkipReason?.let { reason ->
            SectionCard("直近の判定") { Text(skipReasonMessage(reason)) }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onCheckNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dash_check_now))
            }
            if (state.attempt?.state == ChargeState.CHARGE_DETECTED ||
                state.attempt?.state == ChargeState.CHARGE_PENDING
            ) {
                Button(onClick = onStartCharge, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dash_charge_now))
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpenIcoca, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dash_open_icoca))
                }
                OutlinedButton(
                    onClick = { showManualInput = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.dash_enter_balance))
                }
            }
            Text(
                stringResource(R.string.warn_background_delay),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showManualInput) {
        ManualBalanceDialog(
            onDismiss = { showManualInput = false },
            onSubmit = {
                onSubmitManualBalance(it)
                showManualInput = false
            },
        )
    }
}

@Composable
private fun ManualBalanceDialog(onDismiss: () -> Unit, onSubmit: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val value = text.filter { it.isDigit() }.toIntOrNull()
    val valid = value != null && value in 0..20_000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dash_enter_balance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("モバイルICOCAアプリで表示されている残高を入力してください。")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("残高（円）") },
                    isError = text.isNotEmpty() && !valid,
                )
                if (text.isNotEmpty() && !valid) {
                    Text("0〜20,000円の範囲で入力してください")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSubmit) }, enabled = valid) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private fun sourceLabel(source: io.github.nobu0601.icocaautocharge.domain.BalanceSourceType): String =
    when (source) {
        io.github.nobu0601.icocaautocharge.domain.BalanceSourceType.OFFICIAL_API -> "公式API"
        io.github.nobu0601.icocaautocharge.domain.BalanceSourceType.INTENT -> "公式Intent"
        io.github.nobu0601.icocaautocharge.domain.BalanceSourceType.NFC -> "NFC（物理カード）"
        io.github.nobu0601.icocaautocharge.domain.BalanceSourceType.ACCESSIBILITY -> "ICOCAアプリの画面"
        io.github.nobu0601.icocaautocharge.domain.BalanceSourceType.MANUAL -> "手入力"
        io.github.nobu0601.icocaautocharge.domain.BalanceSourceType.SIMULATED -> "疑似値（Debug）"
    }

private fun stateLabel(state: ChargeState): String = when (state) {
    ChargeState.IDLE -> "待機中"
    ChargeState.CHARGE_DETECTED -> "残高低下を検知"
    ChargeState.CHARGE_PENDING -> "チャージ開始待ち"
    ChargeState.CHARGING -> "チャージ処理中"
    ChargeState.VERIFYING -> "残高を確認中"
    ChargeState.SUCCESS -> "完了"
    ChargeState.FAILED -> "失敗"
}

private fun skipReasonMessage(name: String): String =
    runCatching {
        io.github.nobu0601.icocaautocharge.domain.SkipReason.valueOf(name).message
    }.getOrDefault(name)
