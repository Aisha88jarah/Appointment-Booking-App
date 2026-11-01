@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.bookings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookings.MainViewModel
import com.example.bookings.MsgKind
import com.example.bookings.R
import com.example.bookings.SlotChoice
import com.example.bookings.core.ListTab
import com.example.bookings.core.Slots
import com.example.bookings.core.Templates
import com.example.bookings.core.WorkDay
import com.example.bookings.core.WorkWeek
import com.example.bookings.data.AppSettings
import com.example.bookings.data.Appointment
import com.example.bookings.data.ApptStatus
import com.example.bookings.data.Service
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
private fun tabLabel(t: ListTab): String = stringResource(
    when (t) {
        ListTab.PENDING -> R.string.tab_pending
        ListTab.CONFIRMED -> R.string.tab_confirmed
        ListTab.CLOSED -> R.string.tab_closed
        ListTab.ALL -> R.string.tab_all
    }
)

// ------------------------------------------------------------------------------------------

@Composable
fun RequestsScreen(
    vm: MainViewModel,
    onOpen: (Long) -> Unit,
    onToggle: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val all by vm.appointments.collectAsStateWithLifecycle()
    val list by vm.shown.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val lastExport by vm.lastExport.collectAsStateWithLifecycle()
    val pending = all.count { it.status == ApptStatus.PENDING }
    val confirmed = all.count { it.status == ApptStatus.CONFIRMED }

    Column(modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        monitorText(status), fontWeight = FontWeight.SemiBold,
                        color = if (status is com.example.bookings.telegram.MonitorStatus.Connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(stringResource(R.string.summary, pending, confirmed), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onToggle) { Text(stringResource(if (isRunning(status)) R.string.stop else R.string.start)) }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ListTab.entries.forEach { t ->
                FilterChip(selected = tab == t, onClick = { vm.tab.value = t }, label = { Text(tabLabel(t)) })
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            singleLine = true,
            label = { Text(stringResource(R.string.search)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.count_items, list.size), style = MaterialTheme.typography.labelLarge)
            Row {
                TextButton(onClick = onExport, enabled = list.isNotEmpty()) { Text(stringResource(R.string.export_excel)) }
                TextButton(onClick = onShare, enabled = lastExport != null) { Text(stringResource(R.string.share_export)) }
            }
        }
        if (list.isEmpty()) Text(stringResource(R.string.empty_list), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.outline)
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { a ->
                ListItem(
                    headlineContent = { Text(a.displayName(), fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Column {
                            Text(a.serviceName ?: a.serviceText.orDash())
                            val whenLine = a.reqText ?: listOfNotNull(a.reqDate, a.reqTime).joinToString(" ")
                            Text(
                                stringResource(R.string.requested_label) + ": " + whenLine.orDash(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (a.status == ApptStatus.CONFIRMED && a.slotStart != null) {
                                Text(fmtRange(a.slotStart, a.slotEnd, ZoneId.systemDefault()), style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                            }
                        }
                    },
                    trailingContent = { Text(statusText(a.status), color = statusColor(a.status), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.clickable { onOpen(a.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

// ------------------------------------------------------------------------------------------

@Composable
fun AgendaScreen(vm: MainViewModel, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    val all by vm.appointments.collectAsStateWithLifecycle()
    val zone = vm.zone
    val startOfToday = remember { LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli() }
    val items = all
        .filter { it.status == ApptStatus.CONFIRMED && it.slotStart != null && (it.slotEnd ?: 0L) >= startOfToday }
        .sortedBy { it.slotStart }
    val grouped = items.groupBy { Instant.ofEpochMilli(it.slotStart ?: 0L).atZone(zone).toLocalDate() }
    val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault())

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.agenda_empty), color = MaterialTheme.colorScheme.outline)
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            grouped.forEach { (d, list) ->
                item(key = d.toString()) {
                    Text(
                        d.format(dateFmt),
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(list, key = { it.id }) { a ->
                    ListItem(
                        overlineContent = { Text(fmtRange(a.slotStart ?: 0L, a.slotEnd, zone).substringAfter(' ')) },
                        headlineContent = { Text(a.displayName(), fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(a.serviceName ?: a.serviceText.orDash()) },
                        modifier = Modifier.clickable { onOpen(a.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------

@Composable
fun DetailScreen(vm: MainViewModel, id: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val all by vm.appointments.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val services by vm.services.collectAsStateWithLifecycle()
    val a = all.firstOrNull { it.id == id }
    val st = settings
    if (a == null) {
        LaunchedEffect(Unit) { onBack() }
    } else if (st != null) {
        DetailContent(vm, a, st, services, all, onBack, modifier)
    }
}

private fun initialDate(a: Appointment, dates: List<LocalDate>, week: Map<Int, WorkDay?>, zone: ZoneId): LocalDate {
    val fromSlot = a.slotStart?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val fromReq = a.reqDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return (fromSlot ?: fromReq)?.takeIf { it in dates }
        ?: dates.firstOrNull { week[it.dayOfWeek.value] != null }
        ?: dates.first()
}

private fun initialStart(a: Appointment, zone: ZoneId): Int? {
    val s = a.slotStart
    if (s != null) {
        val z = Instant.ofEpochMilli(s).atZone(zone)
        return z.hour * 60 + z.minute
    }
    return a.reqTime?.let { WorkWeek.parseHm(it) }
}

@Composable
private fun DetailContent(
    vm: MainViewModel,
    a: Appointment,
    st: AppSettings,
    services: List<Service>,
    all: List<Appointment>,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val zone = vm.zone
    val locale = Locale.getDefault()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now(zone) }
    val week = remember(st.workWeek) { WorkWeek.decode(st.workWeek) }
    val dates = remember(today) { (0 until 28).map { today.plusDays(it.toLong()) } }
    val canPlan = a.status == ApptStatus.PENDING || a.status == ApptStatus.CONFIRMED

    var serviceId by remember(a.id) { mutableStateOf(a.serviceId) }
    var date by remember(a.id) { mutableStateOf(initialDate(a, dates, week, zone)) }
    var startMin by remember(a.id) { mutableStateOf(initialStart(a, zone)) }
    var review by remember { mutableStateOf<MsgKind?>(null) }
    var reviewText by remember { mutableStateOf("") }
    var reviewSlot by remember { mutableStateOf<SlotChoice?>(null) }
    var serviceMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val activeServices = services.filter { it.active }
    val service = activeServices.firstOrNull { it.id == serviceId }
    val sameService = serviceId == a.serviceId
    val duration = service?.durationMin ?: (if (sameService) a.durationMin else null) ?: st.defaultDurationMin
    val serviceName = service?.name ?: (if (sameService) a.serviceName else null)

    val busy = Slots.busyOn(date, all, zone, excludeId = a.id)
    val nowMin = if (date == today) LocalTime.now(zone).let { it.hour * 60 + it.minute } else 0
    val free = Slots.freeStarts(week[date.dayOfWeek.value], duration, st.stepMin, st.bufferMin, st.packTight, busy, nowMin)
    val existingMin = a.slotStart?.let { s ->
        val z = Instant.ofEpochMilli(s).atZone(zone)
        if (z.toLocalDate() == date) z.hour * 60 + z.minute else null
    }
    val chips = (free + listOfNotNull(existingMin)).distinct().sorted()
    val chosen = startMin?.takeIf { it in chips }
    val choice = chosen?.let {
        SlotChoice(service?.id, serviceName, duration, date.atTime(it / 60, it % 60).atZone(zone).toInstant().toEpochMilli())
    }
    val existingChoice = a.slotStart?.let { SlotChoice(a.serviceId, a.serviceName, a.durationMin ?: duration, it) }

    fun openReview(kind: MsgKind, slot: SlotChoice?) {
        val ref = slot?.let { Instant.ofEpochMilli(it.startMs).atZone(zone) }
            ?: a.slotStart?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val refDate = ref?.toLocalDate() ?: a.reqDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val refTime = ref?.toLocalTime()
            ?: a.reqTime?.let { WorkWeek.parseHm(it) }?.let { LocalTime.of((it / 60) % 24, it % 60) }
        val tpl = when (kind) {
            MsgKind.CONFIRM -> st.confirmTpl
            MsgKind.DECLINE -> st.declineTpl
            MsgKind.RESCHEDULE -> st.rescheduleTpl
            MsgKind.CANCEL -> st.cancelTpl
        }
        val withSlot = slot != null
        val values = Templates.values(
            name = a.displayName(),
            service = if (withSlot) slot?.serviceName else (a.serviceName ?: a.serviceText),
            durationMin = if (withSlot) slot?.durationMin else a.durationMin,
            date = refDate, time = refTime,
            business = st.businessName, address = st.address, locale = locale,
        )
        reviewText = Templates.render(tpl, values)
        reviewSlot = slot
        review = kind
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(a.displayName(), style = MaterialTheme.typography.headlineSmall)
        Text(statusText(a.status), color = statusColor(a.status), fontWeight = FontWeight.Bold)
        if (a.status == ApptStatus.CONFIRMED && a.slotStart != null) {
            Text(fmtRange(a.slotStart, a.slotEnd, zone), color = Color(0xFF2E7D32), style = MaterialTheme.typography.titleMedium)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Info("Telegram User ID", a.telegramUserId.toString())
                Info("Telegram Chat ID", a.chatId.toString())
                Info("Username", a.username?.let { "@$it" }.orDash())
                Info(stringResource(R.string.phone), a.phone.orDash())
                Info(stringResource(R.string.service), (a.serviceText ?: a.serviceName).orDash())
                Info(stringResource(R.string.requested_label), (a.reqText ?: listOfNotNull(a.reqDate, a.reqTime).joinToString(" ")).orDash())
                if (a.reqDate != null || a.reqTime != null) {
                    Info(stringResource(R.string.understood_as), listOfNotNull(a.reqDate, a.reqTime).joinToString(" "))
                }
                if (!a.notes.isNullOrBlank()) Info(stringResource(R.string.notes), a.notes)
                Info(stringResource(R.string.received_at), fmtStamp(a.createdAt, zone))
            }
        }

        Text(stringResource(R.string.original_message), style = MaterialTheme.typography.labelLarge)
        Card(Modifier.fillMaxWidth()) { SelectionContainer { Text(a.originalMessage, Modifier.padding(12.dp)) } }

        if (canPlan) {
            HorizontalDivider()
            Text(stringResource(R.string.pick_slot), style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { serviceMenu = true }) {
                    Text(stringResource(R.string.service) + ": " + (serviceName ?: stringResource(R.string.no_service)))
                }
                DropdownMenu(expanded = serviceMenu, onDismissRequest = { serviceMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.no_service)) },
                        onClick = { serviceId = null; startMin = null; serviceMenu = false },
                    )
                    activeServices.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name + " · " + s.durationMin + " " + stringResource(R.string.unit_min)) },
                            onClick = { serviceId = s.id; startMin = null; serviceMenu = false },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.slot_info, duration, st.bufferMin, st.stepMin),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dates.forEach { d ->
                    FilterChip(
                        selected = d == date,
                        enabled = week[d.dayOfWeek.value] != null,
                        onClick = { date = d; startMin = null },
                        label = { Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + d.dayOfMonth + "/" + d.monthValue) },
                    )
                }
            }
            if (chips.isEmpty()) {
                Text(stringResource(R.string.no_free_slots), color = MaterialTheme.colorScheme.outline)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { m ->
                        FilterChip(selected = chosen == m, onClick = { startMin = m }, label = { Text(WorkWeek.hm(m)) })
                    }
                }
            }
            val reqMin = a.reqTime?.let { WorkWeek.parseHm(it) }
            if (a.status == ApptStatus.PENDING && a.reqDate == date.toString() && reqMin != null && reqMin !in chips) {
                Text(stringResource(R.string.requested_unavailable), color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall)
            }
        }

        when (a.status) {
            ApptStatus.PENDING -> {
                Button(
                    onClick = { openReview(MsgKind.CONFIRM, choice) },
                    enabled = choice != null && !sending,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_review_confirm)) }
                OutlinedButton(onClick = { openReview(MsgKind.DECLINE, null) }, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_review_decline))
                }
                TextButton(onClick = { vm.dismiss(a.id); onBack() }) { Text(stringResource(R.string.btn_dismiss)) }
            }
            ApptStatus.CONFIRMED -> {
                Button(
                    onClick = { openReview(MsgKind.RESCHEDULE, choice) },
                    enabled = choice != null && choice.startMs != a.slotStart && !sending,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_review_reschedule)) }
                OutlinedButton(
                    onClick = { openReview(MsgKind.CONFIRM, existingChoice) },
                    enabled = existingChoice != null && !sending,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_resend)) }
                OutlinedButton(onClick = { openReview(MsgKind.CANCEL, null) }, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_review_cancel), color = Color(0xFFC62828))
                }
            }
            else -> {}
        }

        val last = a.lastMessageText
        if (last != null) {
            Text(
                stringResource(R.string.last_message) + (a.lastMessageAt?.let { " · " + fmtStamp(it, zone) } ?: ""),
                style = MaterialTheme.typography.labelLarge,
            )
            Card(Modifier.fillMaxWidth()) { SelectionContainer { Text(last, Modifier.padding(12.dp)) } }
        }

        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.btn_delete), color = Color(0xFFC62828)) }
    }

    val rk = review
    if (rk != null) {
        MessageReviewDialog(
            title = stringResource(
                when (rk) {
                    MsgKind.CONFIRM -> R.string.review_title_confirm
                    MsgKind.DECLINE -> R.string.review_title_decline
                    MsgKind.RESCHEDULE -> R.string.review_title_reschedule
                    MsgKind.CANCEL -> R.string.review_title_cancel
                }
            ),
            recipient = a.displayName() + " (" + a.chatId + ")",
            initial = reviewText,
            sending = sending,
            onSend = { text -> vm.sendToClient(a.id, rk, text, reviewSlot) { ok -> if (ok) review = null } },
            onDismiss = { review = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAppointment(a.id); onBack() }) { Text(stringResource(R.string.btn_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
