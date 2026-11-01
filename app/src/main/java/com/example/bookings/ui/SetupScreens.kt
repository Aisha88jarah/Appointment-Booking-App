@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.bookings.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookings.BotInfo
import com.example.bookings.MainViewModel
import com.example.bookings.R
import com.example.bookings.core.WorkDay
import com.example.bookings.core.WorkWeek
import com.example.bookings.data.AppSettings
import com.example.bookings.data.Service
import com.example.bookings.telegram.MonitorStatus
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private data class DayEdit(val enabled: Boolean, val from: String, val to: String)

// ------------------------------------------------------------------------------------------
// Schedule: gaps between appointments, opening hours, services

@Composable
fun ScheduleScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val services by vm.services.collectAsStateWithLifecycle()
    val st = settings
    if (st == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        ScheduleContent(vm, st, services, modifier)
    }
}

@Composable
private fun ScheduleContent(vm: MainViewModel, st: AppSettings, services: List<Service>, modifier: Modifier) {
    var step by remember(st.stepMin) { mutableStateOf(st.stepMin) }
    var buffer by remember(st.bufferMin) { mutableStateOf(st.bufferMin) }
    var defDuration by remember(st.defaultDurationMin) { mutableStateOf(st.defaultDurationMin) }
    var pack by remember(st.packTight) { mutableStateOf(st.packTight) }
    val days = remember(st.workWeek) {
        mutableStateMapOf<Int, DayEdit>().also { m ->
            WorkWeek.decode(st.workWeek).forEach { (d, w) ->
                m[d] = DayEdit(w != null, WorkWeek.hm(w?.openMin ?: 540), WorkWeek.hm(w?.closeMin ?: 1020))
            }
        }
    }
    var editing by remember { mutableStateOf<Service?>(null) }
    var adding by remember { mutableStateOf(false) }
    val locale = Locale.getDefault()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.sched_title), style = MaterialTheme.typography.titleMedium)
        NumberStepper(stringResource(R.string.sched_step), step, 5, 240, 5) { step = it }
        NumberStepper(stringResource(R.string.sched_buffer), buffer, 0, 120, 5) { buffer = it }
        NumberStepper(stringResource(R.string.sched_default_duration), defDuration, 5, 480, 5) { defDuration = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sched_pack), Modifier.weight(1f))
            Switch(checked = pack, onCheckedChange = { pack = it })
        }
        Text(
            stringResource(R.string.sched_explain, defDuration, buffer, step),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        HorizontalDivider()
        Text(stringResource(R.string.sched_hours), style = MaterialTheme.typography.titleMedium)
        for (d in 1..7) {
            val e = days[d] ?: continue
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = e.enabled, onCheckedChange = { days[d] = e.copy(enabled = it) })
                Text(DayOfWeek.of(d).getDisplayName(TextStyle.FULL, locale), Modifier.weight(1f))
                if (e.enabled) {
                    OutlinedTextField(
                        value = e.from, onValueChange = { days[d] = e.copy(from = it) }, singleLine = true,
                        modifier = Modifier.width(96.dp),
                    )
                    Text("–")
                    OutlinedTextField(
                        value = e.to, onValueChange = { days[d] = e.copy(to = it) }, singleLine = true,
                        modifier = Modifier.width(96.dp),
                    )
                } else {
                    Text(stringResource(R.string.sched_closed), color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Text(stringResource(R.string.sched_hours_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

        Button(
            onClick = {
                val week = mutableMapOf<Int, WorkDay?>()
                var valid = true
                for (d in 1..7) {
                    val e = days[d] ?: continue
                    if (!e.enabled) {
                        week[d] = null
                        continue
                    }
                    val a = WorkWeek.parseHm(e.from)
                    val b = WorkWeek.parseHm(e.to)
                    if (a == null || b == null || a >= b) valid = false else week[d] = WorkDay(a, b)
                }
                if (valid) vm.saveSchedule(step, buffer, defDuration, pack, week) else vm.toast(R.string.err_hours)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save)) }

        HorizontalDivider()
        Text(stringResource(R.string.services_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.services_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        services.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold)
                    Text(s.durationMin.toString() + " " + stringResource(R.string.unit_min), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { editing = s }) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = { vm.deleteService(s.id) }) { Text(stringResource(R.string.btn_delete), color = Color(0xFFC62828)) }
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.service_add)) }
    }

    if (adding) {
        ServiceDialog(null, defDuration, { n, m -> vm.saveService(null, n, m); adding = false }, { adding = false })
    }
    val ed = editing
    if (ed != null) {
        ServiceDialog(ed, ed.durationMin, { n, m -> vm.saveService(ed.id, n, m); editing = null }, { editing = null })
    }
}

@Composable
private fun ServiceDialog(initial: Service?, defaultDuration: Int, onSave: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var dur by remember { mutableStateOf((initial?.durationMin ?: defaultDuration).toString()) }
    val minutes = dur.toIntOrNull()
    val valid = name.isNotBlank() && minutes != null && minutes in 5..480
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.service_add else R.string.service_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.service_name)) },
                )
                OutlinedTextField(
                    value = dur, onValueChange = { dur = it.filter { c -> c.isDigit() } }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.service_minutes)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, minutes ?: 30) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------------------------------
// Settings: Telegram bot, business details, message templates

@Composable
fun SettingsScreen(vm: MainViewModel, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val saved by vm.tokenSaved.collectAsStateWithLifecycle()
    val info by vm.botInfo.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val st = settings
    var token by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.set_bot), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text(stringResource(if (saved) R.string.token_saved_label else R.string.token_label)) },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (vm.saveToken(token)) token = "" }, enabled = token.isNotEmpty()) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = { vm.testConnection(token) }, enabled = token.isNotEmpty() || saved) { Text(stringResource(R.string.test_connection)) }
            TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.hide else R.string.show)) }
        }
        if (saved) TextButton(onClick = { vm.clearToken() }) { Text(stringResource(R.string.delete_token), color = Color(0xFFC62828)) }

        when (val i = info) {
            BotInfo.None -> {}
            BotInfo.Loading -> Text(stringResource(R.string.testing))
            is BotInfo.Error -> Text(i.text, color = Color(0xFFC62828))
            is BotInfo.Ok -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.connected_ok), color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    Info(stringResource(R.string.bot_name), i.bot.firstName)
                    Info("Username", i.bot.username?.let { "@$it" }.orDash())
                    Info("Bot ID", i.bot.id.toString())
                }
            }
        }

        Text(stringResource(R.string.monitoring_label) + ": " + monitorText(status), fontWeight = FontWeight.SemiBold)
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (isRunning(status)) R.string.stop_monitoring else R.string.start_monitoring))
        }
        if (status is MonitorStatus.WebhookConflict) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.webhook_explain))
                    Button(onClick = { vm.deleteWebhookAndRestart() }) { Text(stringResource(R.string.webhook_delete)) }
                }
            }
        }
        Text(stringResource(R.string.limits_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.limits_text), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
            Text(stringResource(R.string.battery_settings))
        }

        HorizontalDivider()
        if (st == null) {
            CircularProgressIndicator()
        } else {
            MessagesEditor(vm, st)
        }
    }
}

@Composable
private fun MessagesEditor(vm: MainViewModel, st: AppSettings) {
    var business by remember(st.businessName) { mutableStateOf(st.businessName) }
    var address by remember(st.address) { mutableStateOf(st.address) }
    var confirm by remember(st.confirmTpl) { mutableStateOf(st.confirmTpl) }
    var decline by remember(st.declineTpl) { mutableStateOf(st.declineTpl) }
    var reschedule by remember(st.rescheduleTpl) { mutableStateOf(st.rescheduleTpl) }
    var cancel by remember(st.cancelTpl) { mutableStateOf(st.cancelTpl) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.set_business), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = business, onValueChange = { business = it }, singleLine = true,
            label = { Text(stringResource(R.string.business_name)) }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = address, onValueChange = { address = it },
            label = { Text(stringResource(R.string.business_address)) }, modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.set_templates), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.tpl_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        TemplateField(stringResource(R.string.tpl_confirm_label), confirm) { confirm = it }
        TemplateField(stringResource(R.string.tpl_decline_label), decline) { decline = it }
        TemplateField(stringResource(R.string.tpl_reschedule_label), reschedule) { reschedule = it }
        TemplateField(stringResource(R.string.tpl_cancel_label), cancel) { cancel = it }
        Button(
            onClick = { vm.saveMessages(business, address, confirm, decline, reschedule, cancel) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save)) }
        OutlinedButton(onClick = { vm.resetTemplates() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reset_templates))
        }
        Text(stringResource(R.string.client_howto_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.client_howto), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TemplateField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
    )
}
