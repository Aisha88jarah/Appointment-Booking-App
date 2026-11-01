package com.example.bookings

import com.example.bookings.core.Busy
import com.example.bookings.core.Slots
import com.example.bookings.core.WorkDay
import com.example.bookings.core.WorkWeek
import com.example.bookings.data.Appointment
import com.example.bookings.data.ApptStatus
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotsTest {
    private val day = WorkDay(9 * 60, 12 * 60)

    @Test fun plainGrid() {
        assertEquals(listOf(540, 570, 600, 630, 660, 690), Slots.freeStarts(day, 30, 30, 0, false, emptyList()))
    }

    @Test fun changingTheStepChangesTheGrid() {
        assertEquals(listOf(540, 600, 660), Slots.freeStarts(day, 30, 60, 0, false, emptyList()))
        assertEquals(listOf(540, 555, 570, 585), Slots.freeStarts(WorkDay(540, 630), 45, 15, 0, false, emptyList()).take(4))
    }

    @Test fun gapAroundConfirmedAppointment() {
        val busy = listOf(Busy(600, 630))   // 10:00-10:30
        assertEquals(listOf(540, 660, 690), Slots.freeStarts(day, 30, 30, 10, false, busy))
        assertEquals(listOf(540, 570, 630, 660, 690), Slots.freeStarts(day, 30, 30, 0, false, busy))   // touching appointments are fine without a gap
    }

    @Test fun biggerGapRemovesMoreSlots() {
        val busy = listOf(Busy(600, 630))
        assertEquals(listOf(690), Slots.freeStarts(day, 30, 30, 40, false, busy))
    }

    @Test fun packTightOffersTimeRightAfterAnAppointment() {
        val busy = listOf(Busy(600, 630))
        assertEquals(listOf(540, 640, 660, 690), Slots.freeStarts(day, 30, 30, 10, true, busy))
    }

    @Test fun serviceDurationMustFitBeforeClosing() {
        assertEquals(listOf(540, 600), Slots.freeStarts(day, 120, 60, 0, false, emptyList()))
        assertTrue(Slots.freeStarts(day, 200, 30, 0, false, emptyList()).isEmpty())
    }

    @Test fun closedDayAndEarliest() {
        assertTrue(Slots.freeStarts(null, 30, 30, 0, false, emptyList()).isEmpty())
        assertEquals(listOf(600, 630, 660, 690), Slots.freeStarts(day, 30, 30, 0, false, emptyList(), earliest = 600))
    }

    private fun appt(id: Long, status: ApptStatus, start: Long?, end: Long?) = Appointment(
        id = id, telegramUserId = 1, chatId = 1, username = null, firstName = null, lastName = null,
        clientName = "X", phone = null, serviceId = null, serviceText = null, serviceName = null, durationMin = 30,
        reqDate = null, reqTime = null, reqText = null, notes = null, slotStart = start, slotEnd = end,
        originalMessage = "m", messageId = id, createdAt = 0, updatedAt = 0, status = status,
        lastMessageText = null, lastMessageAt = null,
    )

    @Test fun busyOnUsesOnlyConfirmedAppointmentsOfThatDay() {
        val d = LocalDate.of(2026, 11, 2)
        fun ms(h: Int, m: Int, day: LocalDate = d) = day.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
        val list = listOf(
            appt(1, ApptStatus.CONFIRMED, ms(10, 0), ms(10, 30)),
            appt(2, ApptStatus.PENDING, ms(11, 0), ms(11, 30)),
            appt(3, ApptStatus.CANCELLED, ms(12, 0), ms(12, 30)),
            appt(4, ApptStatus.CONFIRMED, ms(10, 0, d.plusDays(1)), ms(10, 30, d.plusDays(1))),
            appt(5, ApptStatus.CONFIRMED, ms(14, 0), ms(14, 45)),
        )
        assertEquals(listOf(Busy(600, 630), Busy(840, 885)), Slots.busyOn(d, list, ZoneOffset.UTC))
        assertEquals(listOf(Busy(840, 885)), Slots.busyOn(d, list, ZoneOffset.UTC, excludeId = 1))
    }

    @Test fun conflictsRespectTheGap() {
        val busy = listOf(Busy(600, 630))
        assertTrue(Slots.conflicts(635, 30, 10, busy))
        assertTrue(!Slots.conflicts(640, 30, 10, busy))
        assertTrue(Slots.conflicts(575, 30, 10, busy))
        assertTrue(!Slots.conflicts(560, 30, 10, busy))
    }

    @Test fun workWeekRoundTripAndParsing() {
        val custom = WorkWeek.default().toMutableMap()
        custom[6] = WorkDay(10 * 60, 14 * 60)
        custom[2] = null
        assertEquals(custom, WorkWeek.decode(WorkWeek.encode(custom)))
        assertEquals(WorkWeek.default(), WorkWeek.decode("garbage;;1=bad;9=09:00-10:00"))
        assertEquals(570, WorkWeek.parseHm("9:30"))
        assertEquals(570, WorkWeek.parseHm("٠٩:٣٠"))
        assertEquals(1440, WorkWeek.parseHm("24:00"))
        assertNull(WorkWeek.parseHm("25:00"))
        assertNull(WorkWeek.parseHm("09:60"))
        assertNull(WorkWeek.parseHm("9"))
        assertEquals("09:05", WorkWeek.hm(545))
    }
}
