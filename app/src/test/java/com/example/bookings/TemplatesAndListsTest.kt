package com.example.bookings

import com.example.bookings.core.AppointmentFactory
import com.example.bookings.core.ListTab
import com.example.bookings.core.ParsedRequest
import com.example.bookings.core.RequestParser
import com.example.bookings.core.Templates
import com.example.bookings.core.filterAppointments
import com.example.bookings.data.Appointment
import com.example.bookings.data.ApptStatus
import com.example.bookings.data.Service
import com.example.bookings.telegram.TgMessage
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatesAndListsTest {
    private val values = mapOf("name" to "Ann", "service" to "", "date" to "Monday", "time" to "", "business" to "", "address" to "", "duration" to "30")

    @Test fun linesWithOnlyEmptyPlaceholdersAreDropped() {
        val out = Templates.render("Hello {name}\nService: {service}\n{business}\nDuration: {duration} min\nBye", values)
        assertEquals("Hello Ann\nDuration: 30 min\nBye", out)
    }

    @Test fun lineWithSomeValuesIsKept() {
        assertTrue(Templates.render("On {date} at {time}", values).startsWith("On Monday"))
    }

    @Test fun unknownOrMistypedPlaceholdersAreDetected() {
        val out = Templates.render("Hi {nmae}, {name}", values)
        assertEquals("Hi {nmae}, Ann", out)
        assertEquals(listOf("{nmae}"), Templates.unresolved(out))
        assertTrue(Templates.unresolved("Hello Ann").isEmpty())
    }

    @Test fun valuesFormatDateAndTime() {
        val v = Templates.values("Ann", "Consultation", 30, LocalDate.of(2026, 11, 2), LocalTime.of(10, 30), "Clinic", "Main St", Locale.US)
        assertTrue(v.getValue("date").contains("2026"))
        assertTrue(v.getValue("time").contains("10:30"))
        assertEquals("30", v.getValue("duration"))
        assertEquals("", Templates.values("A", null, null, null, null, "", "", Locale.US).getValue("date"))
    }

    // ---- request -> appointment ----

    private fun msg(id: Long, text: String) = TgMessage(id, 55, 55, "ann", "Ann", "L", 1_793_000_000, text, false)

    @Test fun newRequestIsPendingAndKeepsBothIds() {
        val m = msg(9, "Name: Ann\nService: Consultation\nDate: 2026-11-05\nTime: 10:30")
        val parsed = RequestParser.parse(m.text!!, LocalDate.of(2026, 11, 1))
        val a = AppointmentFactory.fromRequest(m.copy(chatId = -100777), 55, parsed, Service(4, "Consultation", 30), 5_000, null)
        assertEquals(ApptStatus.PENDING, a.status)
        assertEquals(55L, a.telegramUserId)
        assertEquals(-100777L, a.chatId)
        assertEquals("2026-11-05", a.reqDate)
        assertEquals("10:30", a.reqTime)
        assertEquals(4L, a.serviceId)
        assertNull(a.slotStart)
        assertEquals(1_793_000_000_000L, a.createdAt)
        assertEquals(m.text, a.originalMessage)
    }

    @Test fun editedMessageRefreshesRequestButKeepsIdentity() {
        val first = AppointmentFactory.fromRequest(msg(9, "Name: Ann\nDate: 2026-11-05"), 55, RequestParser.parse("Name: Ann\nDate: 2026-11-05", LocalDate.of(2026, 11, 1)), null, 1, null).copy(id = 12)
        val edited = msg(9, "Name: Ann Lee\nDate: 2026-11-06")
        val again = AppointmentFactory.fromRequest(edited, 55, RequestParser.parse(edited.text!!, LocalDate.of(2026, 11, 1)), null, 2, first)
        assertEquals(12L, again.id)
        assertEquals("Ann Lee", again.clientName)
        assertEquals("2026-11-06", again.reqDate)
        assertEquals(first.createdAt, again.createdAt)
        assertEquals(ApptStatus.PENDING, again.status)
    }

    @Test fun unparsedMessageIsStillKept() {
        val a = AppointmentFactory.fromRequest(msg(1, "hello"), 55, ParsedRequest.EMPTY, null, 1, null)
        assertEquals("hello", a.originalMessage)
        assertNull(a.clientName); assertNull(a.reqDate)
        assertEquals("Ann L", a.displayName())
    }

    // ---- list filter ----

    private fun make(id: Long, name: String, st: ApptStatus, created: Long, slot: Long? = null) = AppointmentFactory.fromRequest(
        msg(id, "Name: $name"), 55, ParsedRequest(name, "0555$id", null, null, null, null, null, true), null, created, null,
    ).copy(id = id, status = st, slotStart = slot, createdAt = created, updatedAt = created)

    @Test fun tabsSearchAndOrdering() {
        val list = listOf(
            make(1, "Zed", ApptStatus.PENDING, 300), make(2, "Amy", ApptStatus.PENDING, 100),
            make(3, "Bob", ApptStatus.CONFIRMED, 200, slot = 2000), make(4, "Cat", ApptStatus.CONFIRMED, 250, slot = 1000),
            make(5, "Dan", ApptStatus.DECLINED, 50), make(6, "Eve", ApptStatus.DISMISSED, 60),
        )
        assertEquals(listOf(2L, 1L), filterAppointments(list, ListTab.PENDING, "").map { it.id })        // oldest request first
        assertEquals(listOf(4L, 3L), filterAppointments(list, ListTab.CONFIRMED, "").map { it.id })      // soonest appointment first
        assertEquals(setOf(5L, 6L), filterAppointments(list, ListTab.CLOSED, "").map { it.id }.toSet())
        assertEquals(6, filterAppointments(list, ListTab.ALL, "").size)
        assertEquals(listOf(3L), filterAppointments(list, ListTab.ALL, "bob").map { it.id })
        assertEquals(listOf(4L), filterAppointments(list, ListTab.ALL, "05554").map { it.id })           // phone
        assertEquals(6, filterAppointments(list, ListTab.ALL, "55").size)                                // Telegram id
    }
}
