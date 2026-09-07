package io.github.nobu0601.icocaautocharge.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nobu0601.icocaautocharge.R
import io.github.nobu0601.icocaautocharge.core.Money
import io.github.nobu0601.icocaautocharge.data.db.ChargeHistoryEntity
import io.github.nobu0601.icocaautocharge.domain.ChargeStatus
import io.github.nobu0601.icocaautocharge.ui.common.formatTimeFull

@Composable
fun HistoryScreen(history: List<ChargeHistoryEntity>, onClear: () -> Unit) {
    if (history.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.hist_empty))
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(history, key = { it.id }) { row -> HistoryRow(row) }
        item {
            TextButton(onClick = onClear, modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.hist_clear))
            }
        }
    }
}

@Composable
private fun HistoryRow(row: ChargeHistoryEntity) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatTimeFull(row.timestamp), style = MaterialTheme.typography.labelLarge)
            Text(
                buildString {
                    append(Money.formatOrDash(row.balanceBefore))
                    append(" → ")
                    append(Money.formatOrDash(row.balanceAfter))
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("+${Money.format(row.chargeAmount)}", style = MaterialTheme.typography.bodyMedium)
            Text(statusLabel(row.status), style = MaterialTheme.typography.bodyMedium)
            row.errorReason?.let {
                Text("理由: ${it.name}", style = MaterialTheme.typography.bodySmall)
            }
            row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "閾値: ${Money.format(row.threshold)} / " +
                    "方法: ${row.automationMethod.name} / " +
                    "確認: ${if (row.userConfirmed) "YES" else "NO"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun statusLabel(status: ChargeStatus): String = when (status) {
    ChargeStatus.SUCCESS -> stringResource(R.string.hist_status_success)
    ChargeStatus.SUCCESS_UNVERIFIED -> stringResource(R.string.hist_status_success_unverified)
    ChargeStatus.FAILED -> stringResource(R.string.hist_status_failed)
    ChargeStatus.CANCELLED -> stringResource(R.string.hist_status_cancelled)
    ChargeStatus.DETECTED,
    ChargeStatus.PENDING,
    ChargeStatus.CHARGING,
    ChargeStatus.VERIFYING,
    -> stringResource(R.string.hist_status_in_progress)
}
