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
import io.github.nobu0601.icocaautocharge.accessibility.AccessibilityBridge
import io.github.nobu0601.icocaautocharge.accessibility.AutomationLogEntry
import io.github.nobu0601.icocaautocharge.accessibility.AutomationStatusView
import io.github.nobu0601.icocaautocharge.accessibility.ScreenDump
import io.github.nobu0601.icocaautocharge.accessibility.SnapshotRecord
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
    automation: AutomationStatusView?,
    snapshots: List<SnapshotRecord>,
    automationLog: List<AutomationLogEntry>,
    icocaForeground: Boolean,
    accessibilityConnected: Boolean,
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
    onClearCooldown: () -> Unit,
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
            if (dryRun) {
                // ここで見落とされると「なぜ決済確定まで進まないのか」が分からなくなる。
                // ドライラン中はチャージ画面への遷移も金額選択も、そして決済の確定ボタンも
                // 一切クリックしない（ログに「押す予定」を記録するだけ）。
                Text(
                    "ドライラン中は、決済の確定ボタンを含めて実際のクリックは一切行われません。" +
                        "「押す予定だった」という記録がログに残るだけです。" +
                        "本当にチャージを完了させたい場合はOFFにしてください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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

        // 自動操作が止まったとき、logcat を取れない端末ではここだけが手がかりになる。
        // ダンプのトグルとは無関係に、チャージ処理が走れば必ず溜まる（改修指示 §19）。
        SectionCard(stringResource(R.string.dbg_automation)) {
            if (automation == null) {
                Text("自動操作は動いていません。チャージ処理を1回動かすとここに出ます。")
            } else {
                LabeledValue("Session ID", automation.sessionId)
                LabeledValue("Attempt ID", automation.attemptId.toString())
                LabeledValue("Status", automation.status.name)
                LabeledValue("State", automation.state.name)
                LabeledValue("Screen", automation.screen.name)
                LabeledValue("Step", automation.currentStep.toString())
                LabeledValue("Last Action", automation.lastAction?.name ?: "—")
                LabeledValue("Last Action Time", formatTimeFull(automation.lastActionAt.orNull()))
                // performAction の戻り値。true でも「チャージできた」ではない。
                LabeledValue(
                    "Last Action Result",
                    automation.lastActionResult?.toString() ?: "—",
                )
                LabeledValue("Matched Text", automation.matchedText ?: "—")
                LabeledValue("Matched Node Class", automation.matchedNodeClass ?: "—")
                LabeledValue(
                    "Matched Node Clickable",
                    automation.matchedNodeClickable?.toString() ?: "—",
                )
                LabeledValue(
                    "Clickable Ancestor Depth",
                    automation.clickableAncestorDepth?.toString() ?: "—",
                )
                LabeledValue("Last Screen Change", formatTimeFull(automation.lastScreenChangeAt))
                LabeledValue("Unknown Since", formatTimeFull(automation.unknownSince))
                LabeledValue("Unknown Duration", formatSeconds(automation.unknownDurationMillis))
                LabeledValue("Unknown 連続", automation.consecutiveUnknown.toString())
                LabeledValue(
                    "Current Timeout",
                    automation.currentTimeoutMillis?.let { formatSeconds(it) } ?: "上限なし",
                )
                LabeledValue("この状態での経過", formatSeconds(automation.millisInState))
                LabeledValue("Elapsed", formatSeconds(automation.elapsedMillis))
            }
            LabeledValue("ICOCA Foreground", if (icocaForeground) "はい" else "いいえ")
            LabeledValue("Accessibility Connected", if (accessibilityConnected) "はい" else "いいえ")
        }

        // 何をどの順で見て、どう動いたか（改修指示 §20）。
        SectionCard(stringResource(R.string.dbg_automation_log)) {
            if (automationLog.isEmpty()) {
                Text("まだ記録がありません。")
            } else {
                Text(
                    automationLog.joinToString("\n") { e ->
                        "${formatClock(e.timestamp)} ${e.kind.name} ${e.detail}"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }

        // 直近1画面だけでは「どう遷移して詰まったか」が分からない（改修指示 §18）。
        SectionCard(stringResource(R.string.dbg_snapshots)) {
            if (snapshots.isEmpty()) {
                Text("まだ履歴がありません。")
            } else {
                LabeledValue("件数", "${snapshots.size} / ${AccessibilityBridge.MAX_SNAPSHOTS}")
                Text(
                    snapshots.joinToString("\n") { r ->
                        "${formatClock(r.timestamp)} ${r.screenType.name} " +
                            "state=${r.currentState.name} step=${r.currentStep} " +
                            "nodes=${r.nodeCount} act=${r.lastAction?.name ?: "-"} " +
                            "| ${r.importantTexts.joinToString(" / ")}"
                    },
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
            Text(
                "設定した値は「監視を1回実行」を押した瞬間に1回だけ使われ、その後は自動的に消えます。" +
                    "ユーザー補助が読んだ実際の残高より必ず優先されるので、安全に低残高のテストができます。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRunCheck, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dbg_run_check))
                }
                OutlinedButton(onClick = onResetState, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dbg_reset_state))
                }
            }
            OutlinedButton(onClick = onClearCooldown, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dbg_clear_cooldown))
            }
            Text(
                "検知〜完了まで1回テストすると、次のテストまで最低チャージ間隔（設定値。既定6時間）" +
                    "待たされます。「クールダウン解除」はそれをテスト用に無視するボタンで、" +
                    "実際のチャージ動作には影響しません。",
                style = MaterialTheme.typography.bodySmall,
            )
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

/** 0 を「未発生」として扱う。時刻 0 を 1970 年として表示しても意味がない。 */
private fun Long.orNull(): Long? = if (this == 0L) null else this

private fun formatSeconds(millis: Long): String =
    if (millis < 1_000) "${millis}ms" else String.format("%.1f秒", millis / 1000.0)

/** 操作ログ用の時刻。日付は要らないので時分秒だけ。 */
private fun formatClock(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
