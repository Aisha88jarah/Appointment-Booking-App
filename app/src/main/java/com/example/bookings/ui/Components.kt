@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.bookings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.bookings.R
import com.example.bookings.core.Templates
import com.example.bookings.data.ApptStatus
import com.example.bookings.telegram.MonitorStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun statusText(s: ApptStatus): String = stringResource(
    when (s) {
        ApptStatus.PENDING -> R.string.st_pending
        ApptStatus.CONFIRMED -> R.string.st_confirmed
        ApptStatus.DECLINED -> R.string.st_declined
        ApptStatus.CANCELLED -> R.string.st_cancelled
        ApptStatus.DISMISSED -> R.string.st_dismissed
    }
)

fun statusColor(s: ApptStatus): Color = when (s) {
    ApptStatus.PENDING -> Color(0xFFEF6C00)
    ApptStatus.CONFIRMED -> Color(0xFF2E7D32)
    ApptStatus.DECLINED -> Color(0xFFC62828)
    ApptStatus.CANCELLED -> Color(0xFFC62828)
    ApptStatus.DISMISSED -> Color(0xFF757575)
}

@Composable
fun monitorText(s: MonitorStatus): String = when (s) {
    MonitorStatus.Stopped -> stringResource(R.string.mon_stopped)
    MonitorStatus.Starting -> stringResource(R.string.mon_starting)
    is MonitorStatus.Connected -> stringResource(R.string.mon_connected) + (s.botUsername?.let { " (@$it)" } ?: "")
    is MonitorStatus.Reconnecting -> stringResource(R.string.mon_reconnecting)
    MonitorStatus.InvalidToken -> stringResource(R.string.mon_invalid_token)
    MonitorStatus.WebhookConflict -> stringResource(R.string.mon_webhook)
    MonitorStatus.NoToken -> stringResource(R.string.mon_no_token)
}

fun isRunning(s: MonitorStatus): Boolean =
    s is MonitorStatus.Starting || s is MonitorStatus.Connected || s is MonitorStatus.Reconnecting

fun String?.orDash(): String = if (isNullOrBlank()) "—" else this

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

fun fmtStamp(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(STAMP)

fun fmtRange(start: Long, end: Long?, zone: ZoneId): String {
    val s = Instant.ofEpochMilli(start).atZone(zone)
    val e = end?.let { Instant.ofEpochMilli(it).atZone(zone) }
    return s.format(STAMP) + (e?.let { " – " + it.format(HHMM) } ?: "")
}

@Composable
fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.outline)
        Text(value)
    }
}

@Composable
fun NumberStepper(label: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
        Text("$value " + stringResource(R.string.unit_min), Modifier.padding(horizontal = 12.dp))
        OutlinedButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("+") }
    }
}

/**
 * The administrator reviews and may edit the message here. Nothing is sent until "Send" is tapped,
 * and sending is blocked while unresolved {placeholders} remain.
 */
@Composable
fun MessageReviewDialog(
    title: String,
    recipient: String,
    initial: String,
    sending: Boolean,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val unresolved = Templates.unresolved(text)
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.review_to, recipient), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                )
                Text("${text.length} / 4096", style = MaterialTheme.typography.labelSmall)
                if (unresolved.isNotEmpty()) {
                    Text(
                        stringResource(R.string.review_unresolved, unresolved.joinToString(" ")),
                        color = Color(0xFFC62828),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(R.string.review_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(text) },
                enabled = !sending && text.isNotBlank() && unresolved.isEmpty() && text.length <= 4096,
            ) { Text(stringResource(if (sending) R.string.sending else R.string.send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.cancel)) }
        },
    )
}
