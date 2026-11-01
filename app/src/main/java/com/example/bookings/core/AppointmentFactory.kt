package com.example.bookings.core

import com.example.bookings.data.Appointment
import com.example.bookings.data.ApptStatus
import com.example.bookings.data.Service
import com.example.bookings.telegram.TgMessage
import java.time.format.DateTimeFormatter
import java.util.Locale

object AppointmentFactory {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * New PENDING request, or - when [existing] is the same Telegram message (edit / re-delivery) -
     * the same request refreshed from the message. Admin decisions (status, slot) are never touched.
     */
    fun fromRequest(
        msg: TgMessage, userId: Long, p: ParsedRequest, service: Service?, now: Long, existing: Appointment?,
    ): Appointment {
        val text = msg.text.orEmpty()
        val reqDate = p.date?.toString()
        val reqTime = p.time?.format(hhmm)
        return if (existing == null) {
            Appointment(
                telegramUserId = userId, chatId = msg.chatId, username = msg.username,
                firstName = msg.firstName, lastName = msg.lastName,
                clientName = p.name, phone = p.phone,
                serviceId = service?.id, serviceText = p.service, serviceName = service?.name,
                durationMin = service?.durationMin,
                reqDate = reqDate, reqTime = reqTime, reqText = p.whenText, notes = p.notes,
                slotStart = null, slotEnd = null,
                originalMessage = text, messageId = msg.messageId,
                createdAt = if (msg.date > 0) msg.date * 1000 else now, updatedAt = now,
                status = ApptStatus.PENDING, lastMessageText = null, lastMessageAt = null,
            )
        } else {
            existing.copy(
                username = msg.username ?: existing.username,
                firstName = msg.firstName ?: existing.firstName,
                lastName = msg.lastName ?: existing.lastName,
                clientName = p.name, phone = p.phone,
                serviceId = service?.id, serviceText = p.service, serviceName = service?.name,
                durationMin = service?.durationMin,
                reqDate = reqDate, reqTime = reqTime, reqText = p.whenText, notes = p.notes,
                originalMessage = text, updatedAt = now,
            )
        }
    }
}
