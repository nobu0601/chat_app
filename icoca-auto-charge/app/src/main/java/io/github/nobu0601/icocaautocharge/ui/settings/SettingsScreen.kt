package io.github.nobu0601.icocaautocharge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nobu0601.icocaautocharge.R
import io.github.nobu0601.icocaautocharge.data.settings.AppSettings
import io.github.nobu0601.icocaautocharge.ui.common.SectionCard
import io.github.nobu0601.icocaautocharge.ui.common.SwitchRow

@Composable
fun SettingsScreen(
    settings: AppSettings,
    accessibilityRunning: Boolean,
    onSave: (AppSettings) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var showConsent by remember { mutableStateOf(false) }

    // 外部から設定が変わったら編集中の内容を追従させる
    LaunchedEffect(settings) { draft = settings }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionCard(stringResource(R.string.set_section_basic)) {
            SwitchRow(
                stringResource(R.string.set_monitoring),
                draft.monitoringEnabled,
            ) { draft = draft.copy(monitoringEnabled = it) }

            YenField(stringResource(R.string.set_threshold), draft.thresholdYen) {
                draft = draft.copy(thresholdYen = it)
            }
            YenField(stringResource(R.string.set_amount), draft.chargeAmountYen) {
                draft = draft.copy(chargeAmountYen = it)
            }
            Text(
                stringResource(R.string.set_amount_over_cap),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard(stringResource(R.string.set_section_limits)) {
            YenField(stringResource(R.string.set_daily_limit), draft.dailyLimitYen) {
                draft = draft.copy(dailyLimitYen = it)
            }
            YenField(stringResource(R.string.set_monthly_limit), draft.monthlyLimitYen) {
                draft = draft.copy(monthlyLimitYen = it)
            }
            HoursSlider(
                label = stringResource(R.string.set_interval),
                hours = draft.minChargeIntervalHours,
                range = 1..24,
            ) { draft = draft.copy(minChargeIntervalHours = it) }
        }

        SectionCard(stringResource(R.string.set_section_conditions)) {
            SwitchRow(stringResource(R.string.set_confirm_before), draft.confirmBeforeCharge) {
                draft = draft.copy(confirmBeforeCharge = it)
            }
            SwitchRow(stringResource(R.string.set_wifi_only), draft.wifiOnly) {
                draft = draft.copy(wifiOnly = it)
            }
            SwitchRow(stringResource(R.string.set_charging_only), draft.chargingOnly) {
                draft = draft.copy(chargingOnly = it)
            }
            HoursSlider(
                label = stringResource(R.string.set_check_interval),
                hours = draft.checkIntervalHours,
                range = 1..24,
            ) { draft = draft.copy(checkIntervalHours = it) }
            Text(
                stringResource(R.string.warn_background_delay),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard(stringResource(R.string.set_section_automation)) {
            Text(stringResource(R.string.set_automation_desc), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.set_automation), draft.automationEnabled) { enabled ->
                if (enabled && !draft.automationConsented) {
                    // 同意していない状態では ON にできない（Play ポリシーの明示的開示）
                    showConsent = true
                } else {
                    draft = draft.copy(automationEnabled = enabled)
                }
            }
            if (draft.automationEnabled) {
                Text(
                    if (accessibilityRunning) {
                        "ユーザー補助サービスは有効です。"
                    } else {
                        stringResource(R.string.warn_accessibility_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!accessibilityRunning) {
                    TextButton(onClick = onOpenAccessibilitySettings) {
                        Text(stringResource(R.string.warn_accessibility_open_settings))
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Button(onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.set_save))
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text(stringResource(R.string.consent_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.consent_body))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(automationEnabled = true, automationConsented = true)
                    showConsent = false
                }) { Text(stringResource(R.string.consent_agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) {
                    Text(stringResource(R.string.consent_cancel))
                }
            },
        )
    }
}

@Composable
private fun YenField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(6)
            text = digits
            digits.toIntOrNull()?.let(onChange)
        },
        // 単位は suffix ではなくラベルに含める。suffix は Material3 の
        // バージョンによって有無が変わるため、依存を増やさない。
        label = { Text("$label（円）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HoursSlider(
    label: String,
    hours: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column {
        Text("$label: ${hours}時間", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = hours.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}
