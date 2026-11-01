package com.example.bookings.core

import com.example.bookings.data.Appointment
import com.example.bookings.data.ApptStatus

enum class ListTab { PENDING, CONFIRMED, CLOSED, ALL }

fun filterAppointments(all: List<Appointment>, tab: ListTab, query: String): List<Appointment> {
    val q = query.trim().lowercase()
    val list = all.filter { a ->
        val inTab = when (tab) {
            ListTab.PENDING -> a.status == ApptStatus.PENDING
            ListTab.CONFIRMED -> a.status == ApptStatus.CONFIRMED
            ListTab.CLOSED -> a.status == ApptStatus.DECLINED || a.status == ApptStatus.CANCELLED || a.status == ApptStatus.DISMISSED
            ListTab.ALL -> true
        }
        inTab && (q.isEmpty() || listOf(
            a.clientName, a.firstName, a.lastName, a.username, a.phone, a.serviceName, a.serviceText,
            a.telegramUserId.toString(), a.chatId.toString(),
        ).any { it?.lowercase()?.contains(q) == true })
    }
    return when (tab) {
        ListTab.PENDING -> list.sortedBy { it.createdAt }
        ListTab.CONFIRMED -> list.sortedBy { it.slotStart ?: Long.MAX_VALUE }
        else -> list.sortedByDescending { it.updatedAt }
    }
}
