package com.example.bookings.data

import androidx.room.withTransaction
import com.example.bookings.core.AppointmentFactory
import com.example.bookings.core.ParsedRequest
import com.example.bookings.core.RequestParser
import com.example.bookings.core.ServiceMatcher
import com.example.bookings.telegram.TgMessage
import com.example.bookings.telegram.TgUpdate
import com.example.bookings.telegram.UpdateStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RoomUpdateStore(
    private val db: AppDatabase,
    private val onNewRequests: (Int) -> Unit = {},
) : UpdateStore {

    override suspend fun nextOffset(botId: Long): Long {
        val s = db.botState().get()
        if (s == null || s.botId != botId) {     // another bot => its update_ids are unrelated
            db.botState().put(BotState(botId = botId, nextOffset = 0))
            return 0
        }
        return s.nextOffset
    }

    /** Requests and the new offset are committed together: a crash never loses or repeats an update. */
    override suspend fun save(updates: List<TgUpdate>, newOffset: Long) {
        var created = 0
        db.withTransaction {
            for (u in updates) {
                val msg = u.message ?: continue
                if (process(msg)) created++
            }
            val s = db.botState().get()
            db.botState().put((s ?: BotState(botId = 0, nextOffset = 0)).copy(nextOffset = newOffset))
        }
        if (created > 0) onNewRequests(created)
    }

    /** Returns true when a NEW request was created. */
    private suspend fun process(m: TgMessage): Boolean {
        val userId = m.userId ?: return false
        val original = m.text?.trim().orEmpty()
        if (original.isEmpty()) return false
        // "/start" alone is ignored; "/book\nName: ..." keeps the part after the command.
        val body = if (original.startsWith("/")) original.replaceFirst(Regex("^/\\S+\\s*"), "") else original
        if (body.isBlank()) return false

        val dao = db.appointments()
        val existing = dao.byMessage(m.chatId, m.messageId).singleOrNull()
        if (existing != null && existing.status != ApptStatus.PENDING) return false   // already handled by the admin

        val zone = ZoneId.systemDefault()
        val writtenOn = if (m.date > 0) Instant.ofEpochSecond(m.date).atZone(zone).toLocalDate() else LocalDate.now(zone)
        val parsed = runCatching { RequestParser.parse(body, writtenOn) }.getOrElse { ParsedRequest.EMPTY }
        val service = ServiceMatcher.match(parsed.service, db.services().allActive())
        dao.upsert(AppointmentFactory.fromRequest(m.copy(text = original), userId, parsed, service, System.currentTimeMillis(), existing))
        return existing == null
    }
}
