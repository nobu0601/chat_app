package io.github.nobu0601.icocaautocharge.ui.debug

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nobu0601.icocaautocharge.BuildConfig
import io.github.nobu0601.icocaautocharge.R
import io.github.nobu0601.icocaautocharge.accessibility.ScreenDump
import io.github.nobu0601.icocaautocharge.core.Money
import io.github.nobu0601.icocaautocharge.ui.UiState
import io.github.nobu0601.icocaautocharge.ui.common.LabeledValue
import io.github.nobu0601.icocaautocharge.ui.common.SectionCard
import io.github.nobu0601.icocaautocharge.ui.common.SwitchRow
import io.github.nobu0601.icocaautocharge.ui.common.formatTimeFull

/**
 * 開発・実機検証用の画面（指示書 §23）。
 *
 * リリースビルドではタブ自体が出ない（`BuildConfig.DEBUG_SCREEN_ENABLED`）。
 * ここの機能は `docs/TECHNICAL_FEASIBILITY.md` の検証手順とそのまま対応している。
 */
@Composable
fun DebugScreen(
    state: UiState,
    workState: String,
    dump: ScreenDump?,
    probeReport: String?,
    dumpEnabled: Boolean,
    dryRun: Boolean,
    onDumpEnabledChange: (Boolean) -> Unit,
    onDryRunChange: (Boolean) -> Unit,
    onRunIcocaProbe: () -> Unit,
    onRunSecureElementProbe: () -> Unit,
    onStartNfcRead: () -> Unit,
    onSimulateBalance: (Int) -> Unit,
    onResetState: () -> Unit,
    onRunCheck: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionCard("環境") {
            LabeledValue(
                stringResource(R.string.dbg_icoca_detect),
                state.icoca?.let {
                    if (it.installed) "検出: ${it.versionName} (${it.versionCode})" else "未検出"
                } ?: "確認中",
            )
            LabeledValue(
                stringResource(R.string.dbg_accessibility_state),
                if (state.accessibilityRunning) "有効" else "無効",
            )
            LabeledValue(
                stringResource(R.string.dbg_android_version),
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            LabeledValue("端末", "${Build.MANUFACTURER} ${Build.MODEL}")
            LabeledValue(
                stringResource(R.string.dbg_app_version),
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
        }

        SectionCard("状態") {
            LabeledValue(
                stringResource(R.string.dbg_last_balance),
                state.balance?.let { "${Money.format(it.balanceYen)} (${it.source})" } ?: "—",
            )
            LabeledValue(stringResource(R.string.dbg_last_check), formatTimeFull(state.lastCheckAt))
            LabeledValue(stringResource(R.string.dbg_last_charge), formatTimeFull(state.lastChargeAt))
            LabeledValue(stringResource(R.string.dbg_state), state.attempt?.state?.name ?: "IDLE")
            LabeledValue("直近のスキップ理由", state.lastSkipReason ?: "—")
            LabeledValue(stringResource(R.string.dbg_work_state), workState)
        }

        SectionCard("実機検証（TECHNICAL_FEASIBILITY.md）") {
            SwitchRow(stringResource(R.string.dbg_dump_toggle), dumpEnabled, onChange = onDumpEnabledChange)
            SwitchRow(stringResource(R.string.dbg_dry_run), dryRun, onChange = onDryRunChange)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRunIcocaProbe, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dbg_probe))
                }
                OutlinedButton(onClick = onRunSecureElementProbe, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dbg_omapi))
                }
            }
            OutlinedButton(onClick = onStartNfcRead, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dbg_nfc_read))
            }
        }

        probeReport?.let { report ->
            SectionCard("調査結果") {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }

        SectionCard(stringResource(R.string.dbg_dump)) {
            if (dump == null) {
                Text("まだダンプがありません。「画面ダンプを記録」をONにしてICOCAアプリを開いてください。")
            } else {
                LabeledValue("取得時刻", formatTimeFull(dump.capturedAt))
                LabeledValue("画面判定", dump.screen.name)
                LabeledValue("ノード数", dump.nodes.size.toString())
                Text(
                    dump.nodes.joinToString("\n") { n ->
                        "· ${n.className?.substringAfterLast('.')} " +
                            "id=${n.viewId ?: "-"} " +
                            "text=${n.text ?: "-"} " +
                            "desc=${n.contentDescription ?: "-"} " +
                            "click=${n.clickable} edit=${n.editable}"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }

        SectionCard("テスト操作") {
            SimulateBalanceRow(onSimulateBalance)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRunCheck, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dbg_run_check))
                }
                OutlinedButton(onClick = onResetState, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dbg_reset_state))
                }
            }
        }
    }
}

@Composable
private fun SimulateBalanceRow(onSubmit: (Int) -> Unit) {
    var text by remember { mutableStateOf("2999") }
    val value = text.filter { it.isDigit() }.toIntOrNull()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(stringResource(R.string.dbg_simulate_balance)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { value?.let(onSubmit) },
            enabled = value != null && value <= 20_000,
        ) { Text(stringResource(R.string.common_ok)) }
    }
}
