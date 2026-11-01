package com.example.bookings

import com.example.bookings.core.RequestParser
import com.example.bookings.core.ServiceMatcher
import com.example.bookings.data.Service
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestParserTest {
    private val today = LocalDate.of(2026, 11, 1)   // a Sunday

    private fun p(text: String) = RequestParser.parse(text, today)

    @Test fun arabicLabeledRequest() {
        val r = p("الاسم: أحمد محمد\nالخدمة: كشف\nالتاريخ: 2026-11-05\nالوقت: 10:30 ص\nالهاتف: 0555123456")
        assertEquals("أحمد محمد", r.name)
        assertEquals("كشف", r.service)
        assertEquals(LocalDate.of(2026, 11, 5), r.date)
        assertEquals(LocalTime.of(10, 30), r.time)
        assertEquals("0555123456", r.phone)
        assertTrue(r.labeled)
    }

    @Test fun englishLabeledRequest() {
        val r = p("Name: John Smith\nService: Consultation\nDate: 05/11/2026\nTime: 3:30 pm\nPhone: +966 55 123 4567\nNotes: first visit")
        assertEquals("John Smith", r.name)
        assertEquals("Consultation", r.service)
        assertEquals(LocalDate.of(2026, 11, 5), r.date)
        assertEquals(LocalTime.of(15, 30), r.time)
        assertEquals("+966551234567", r.phone)
        assertEquals("first visit", r.notes)
    }

    @Test fun freeTextEnglish() {
        val r = p("I would like an appointment tomorrow at 10:30")
        assertEquals(LocalDate.of(2026, 11, 2), r.date)
        assertEquals(LocalTime.of(10, 30), r.time)
        assertFalse(r.labeled)
    }

    @Test fun freeTextArabic() {
        val r = p("أريد موعد غداً الساعة 3 م")
        assertEquals(LocalDate.of(2026, 11, 2), r.date)
        assertEquals(LocalTime.of(15, 0), r.time)
    }

    @Test fun weekdayMeansNextOccurrence() {
        assertEquals(LocalDate.of(2026, 11, 2), p("Date: Monday\nTime: 11:00").date)
        assertEquals(LocalDate.of(2026, 11, 5), p("التاريخ: الخميس").date)
        assertEquals(LocalDate.of(2026, 11, 8), p("Date: Sunday").date)   // never "today"
    }

    @Test fun monthNames() {
        assertEquals(LocalDate.of(2026, 11, 5), p("Date: 5 November\nTime: 9:15 am").date)
        assertEquals(LocalTime.of(9, 15), p("Date: 5 November\nTime: 9:15 am").time)
        assertEquals(LocalDate.of(2026, 11, 5), p("التاريخ: 5 نوفمبر").date)
        assertEquals(LocalDate.of(2027, 10, 28), p("Date: 28 October").date)   // already passed => next year
        assertEquals(LocalDate.of(2026, 12, 3), p("التاريخ: 3 كانون الأول").date)
    }

    @Test fun combinedAppointmentLabel() {
        val r = p("Appointment: tomorrow 3 pm")
        assertEquals(LocalDate.of(2026, 11, 2), r.date)
        assertEquals(LocalTime.of(15, 0), r.time)
        assertEquals("tomorrow 3 pm", r.whenText)
    }

    @Test fun arabicIndicDigits() {
        val r = p("التاريخ: ٢٠٢٦-١١-٠٥\nالوقت: ١٠:٣٠ م")
        assertEquals(LocalDate.of(2026, 11, 5), r.date)
        assertEquals(LocalTime.of(22, 30), r.time)
    }

    @Test fun ambiguousTimeIsNotGuessed() {
        assertNull(p("Time: 3:30").time)
        assertEquals(LocalTime.of(10, 0), p("Time: 10").time)
        assertEquals("3:30", p("Time: 3:30").whenText)
    }

    @Test fun valueOnNextLineAndMultiLineNotes() {
        val r = p("Name:\nJohn Smith\nNotes: line one\nline two")
        assertEquals("John Smith", r.name)
        assertEquals("line one\nline two", r.notes)
    }

    @Test fun nothingUnderstoodStaysNull() {
        val r = p("hello")
        assertNull(r.name); assertNull(r.date); assertNull(r.time); assertNull(r.phone)
        assertFalse(r.labeled)
    }

    @Test fun garbageNeverCrashes() {
        for (s in listOf("", "   ", "\n\n", "::::", "Date:", "Time: 99:99", "Date: 31/02/2026", "😀😀", "a".repeat(5000), "الاسم: \u0000\u0001")) {
            RequestParser.parse(s, today)
        }
    }

    @Test fun serviceMatching() {
        val s = listOf(Service(1, "General Consultation", 30), Service(2, "Dental Cleaning", 45), Service(3, "Dental Filling", 60))
        assertEquals(1L, ServiceMatcher.match("consultation", s)?.id)
        assertEquals(2L, ServiceMatcher.match("Dental Cleaning", s)?.id)
        assertNull(ServiceMatcher.match("dental", s))        // two candidates: do not guess
        assertNull(ServiceMatcher.match("x-ray", s))
        assertNull(ServiceMatcher.match(null, s))
    }
}
