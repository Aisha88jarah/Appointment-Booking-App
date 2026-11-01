package com.example.bookings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bookings.core.ListTab
import com.example.bookings.core.Slots
import com.example.bookings.core.TOKEN_REGEX
import com.example.bookings.core.WorkDay
import com.example.bookings.core.WorkWeek
import com.example.bookings.core.XlsxExporter
import com.example.bookings.core.filterAppointments
import com.example.bookings.data.AppSettings
import com.example.bookings.data.Appointment
import com.example.bookings.data.ApptStatus
import com.example.bookings.data.Service
import com.example.bookings.data.defaultSettings
import com.example.bookings.service.MonitorService
import com.example.bookings.telegram.MonitorStatus
import com.example.bookings.telegram.TelegramApiException
import com.example.bookings.telegram.TgBot
import java.io.BufferedOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BotInfo {
    data object None : BotInfo
    data object Loading : BotInfo
    data class Ok(val bot: TgBot) : BotInfo
    data class Error(val text: String) : BotInfo
}

enum class MsgKind { CONFIRM, DECLINE, RESCHEDULE, CANCEL }

/** The slot the administrator picked. */
data class SlotChoice(val serviceId: Long?, val serviceName: String?, val durationMin: Int, val startMs: Long)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val zone: ZoneId = ZoneId.systemDefault()

    val status: StateFlow<MonitorStatus> = app.status
    val appointments: StateFlow<List<Appointment>> =
        app.db.appointments().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val services: StateFlow<List<Service>> =
        app.db.services().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings?> =
        app.db.settings().observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tab = MutableStateFlow(ListTab.PENDING)
    val query = MutableStateFlow("")
    val shown: StateFlow<List<Appointment>> =
        combine(appointments, tab, query) { all, t, q -> filterAppointments(all, t, q) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sending = MutableStateFlow(false)
    val tokenSaved = MutableStateFlow(app.tokenStore.get() != null)
    val botInfo = MutableStateFlow<BotInfo>(BotInfo.None)
    val lastExport = MutableStateFlow<Uri?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (app.db.settings().get() == null) app.db.settings().put(defaultSettings(app))
        }
    }

    private fun say(@StringRes id: Int, vararg args: Any) { messages.tryEmit(app.getString(id, *args)) }
    fun toast(@StringRes id: Int) = say(id)

    // ---- token & connection -------------------------------------------------------------

    fun saveToken(input: String): Boolean {
        val t = input.trim()
        if (!TOKEN_REGEX.matches(t)) { say(R.string.err_token_format); return false }
        return try {
            app.tokenStore.save(t)
            tokenSaved.value = true
            say(R.string.msg_token_saved)
            true
        } catch (e: Exception) {
            say(R.string.err_token_save); false
        }
    }

    fun clearToken() {
        stopMonitoring()
        app.tokenStore.clear()
        tokenSaved.value = false
        botInfo.value = BotInfo.None
        say(R.string.msg_token_deleted)
    }

    /** Tests the typed token if any, otherwise the saved one, using getMe. */
    fun testConnection(input: String) {
        val token = input.trim().ifEmpty { app.tokenStore.get().orEmpty() }
        if (!TOKEN_REGEX.matches(token)) { botInfo.value = BotInfo.Error(app.getString(R.string.err_token_format)); return }
        viewModelScope.launch {
            botInfo.value = BotInfo.Loading
            botInfo.value = try {
                BotInfo.Ok(withContext(Dispatchers.IO) { app.api.getMe(token) })
            } catch (e: Exception) {
                BotInfo.Error(describe(e))
            }
        }
    }

    private fun describe(e: Exception): String = when {
        e is TelegramApiException && (e.code == 401 || e.code == 404) -> app.getString(R.string.err_invalid_token)
        e is TelegramApiException && e.code == 403 -> app.getString(R.string.err_blocked)
        e is TelegramApiException && e.code == 400 -> app.getString(R.string.err_chat_not_found)
        e is TelegramApiException -> app.getString(R.string.err_telegram, e.code)
        e is IOException -> app.getString(R.string.err_network)
        else -> app.getString(R.string.err_unexpected)
    }

    // ---- monitoring ---------------------------------------------------------------------

    fun startMonitoring() {
        if (app.tokenStore.get() == null) { say(R.string.err_no_token); return }
        app.status.value = MonitorStatus.Starting
        ContextCompat.startForegroundService(
            app, Intent(app, MonitorService::class.java).setAction(MonitorService.ACTION_START)
        )
    }

    fun stopMonitoring() {
        app.prefs.monitoring = false
        app.stopService(Intent(app, MonitorService::class.java))
        if (app.status.value !is MonitorStatus.InvalidToken && app.status.value !is MonitorStatus.WebhookConflict) {
            app.status.value = MonitorStatus.Stopped
        }
    }

    /** Called when the UI opens: restores monitoring after an app/process restart. */
    fun resumeIfEnabled() {
        if (app.prefs.monitoring && app.status.value == MonitorStatus.Stopped) startMonitoring()
    }

    /** Explicit admin action: removes the webhook so getUpdates can be used, then restarts polling. */
    fun deleteWebhookAndRestart() {
        val token = app.tokenStore.get() ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { app.api.deleteWebhook(token) }
                say(R.string.msg_webhook_deleted)
                startMonitoring()
            } catch (e: Exception) {
                messages.tryEmit(describe(e))
            }
        }
    }

    // ---- schedule & settings ------------------------------------------------------------

    fun saveSchedule(step: Int, buffer: Int, defaultDuration: Int, packTight: Boolean, week: Map<Int, WorkDay?>) {
        viewModelScope.launch(Dispatchers.IO) {
            val cur = app.db.settings().get() ?: defaultSettings(app)
            app.db.settings().put(
                cur.copy(
                    stepMin = step, bufferMin = buffer, defaultDurationMin = defaultDuration,
                    packTight = packTight, workWeek = WorkWeek.encode(week),
                )
            )
            say(R.string.msg_saved)
        }
    }

    fun saveMessages(business: String, address: String, confirm: String, decline: String, reschedule: String, cancel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cur = app.db.settings().get() ?: defaultSettings(app)
            app.db.settings().put(
                cur.copy(
                    businessName = business.trim(), address = address.trim(),
                    confirmTpl = confirm, declineTpl = decline, rescheduleTpl = reschedule, cancelTpl = cancel,
                )
            )
            say(R.string.msg_saved)
        }
    }

    fun resetTemplates() {
        viewModelScope.launch(Dispatchers.IO) {
            val d = defaultSettings(app)
            val cur = app.db.settings().get() ?: d
            app.db.settings().put(
                cur.copy(confirmTpl = d.confirmTpl, declineTpl = d.declineTpl, rescheduleTpl = d.rescheduleTpl, cancelTpl = d.cancelTpl)
            )
            say(R.string.msg_templates_reset)
        }
    }

    fun saveService(id: Long?, name: String, durationMin: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            app.db.services().upsert(Service(id = id ?: 0, name = name.trim(), durationMin = durationMin))
        }
    }

    fun deleteService(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { app.db.services().delete(id) }
    }

    // ---- appointments -------------------------------------------------------------------

    fun dismiss(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val cur = app.db.appointments().byId(id) ?: return@launch
            app.db.appointments().update(cur.copy(status = ApptStatus.DISMISSED, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteAppointment(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { app.db.appointments().delete(id) }
    }

    /**
     * Sends the administrator-reviewed [text] to the client and, only if Telegram accepted it,
     * applies the decision (confirm / decline / reschedule / cancel). Nothing is ever sent automatically.
     */
    fun sendToClient(id: Long, kind: MsgKind, text: String, slot: SlotChoice?, onResult: (Boolean) -> Unit) {
        if (sending.value) return
        val body = text.trim()
        if (body.isEmpty()) { say(R.string.err_message_empty); onResult(false); return }
        if (body.length > 4096) { say(R.string.err_message_long); onResult(false); return }
        val token = app.tokenStore.get()
        if (token == null) { say(R.string.err_no_token); onResult(false); return }
        sending.value = true
        viewModelScope.launch {
            var ok = false
            try {
                ok = doSend(id, kind, body, slot, token)
            } finally {
                sending.value = false
            }
            onResult(ok)
        }
    }

    private suspend fun doSend(id: Long, kind: MsgKind, body: String, slot: SlotChoice?, token: String): Boolean {
        val dao = app.db.appointments()
        val cur = withContext(Dispatchers.IO) { dao.byId(id) }
        val st = settings.value
        if (cur == null || st == null) { say(R.string.err_unexpected); return false }

        val needsSlot = kind == MsgKind.CONFIRM || kind == MsgKind.RESCHEDULE
        if (needsSlot) {
            if (slot == null) { say(R.string.err_pick_slot); return false }
            val endMs = slot.startMs + slot.durationMin * 60_000L
            val date = Instant.ofEpochMilli(slot.startMs).atZone(zone).toLocalDate()
            val others = withContext(Dispatchers.IO) { dao.confirmedBetween(slot.startMs - 86_400_000L, endMs + 86_400_000L) }
            val busy = Slots.busyOn(date, others, zone, excludeId = cur.id)
            val startMin = ((slot.startMs - date.atStartOfDay(zone).toInstant().toEpochMilli()) / 60_000L).toInt()
            if (Slots.conflicts(startMin, slot.durationMin, st.bufferMin, busy)) { say(R.string.err_slot_taken); return false }
        }

        try {
            withContext(Dispatchers.IO) { app.api.sendMessage(token, cur.chatId, body) }
        } catch (e: Exception) {
            messages.tryEmit(describe(e))
            return false
        }

        val now = System.currentTimeMillis()
        val base = cur.copy(lastMessageText = body, lastMessageAt = now, updatedAt = now)
        val updated = when (kind) {
            MsgKind.CONFIRM, MsgKind.RESCHEDULE -> {
                val s = slot!!
                base.copy(
                    status = ApptStatus.CONFIRMED, serviceId = s.serviceId, serviceName = s.serviceName,
                    durationMin = s.durationMin, slotStart = s.startMs, slotEnd = s.startMs + s.durationMin * 60_000L,
                )
            }
            MsgKind.DECLINE -> base.copy(status = ApptStatus.DECLINED)
            MsgKind.CANCEL -> base.copy(status = ApptStatus.CANCELLED)
        }
        withContext(Dispatchers.IO) { dao.update(updated) }
        say(R.string.msg_sent)
        return true
    }

    // ---- export -------------------------------------------------------------------------

    fun suggestedFileName(): String =
        "appointments_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".xlsx"

    fun export(uri: Uri, onlyShown: Boolean) {
        val list = if (onlyShown) shown.value else appointments.value
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val os = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")
                    BufferedOutputStream(os).use { XlsxExporter.write(it, list, zone) }
                }
                lastExport.value = uri
                say(R.string.msg_exported, list.size)
            } catch (e: Exception) {
                say(R.string.err_export)
            }
        }
    }
}
