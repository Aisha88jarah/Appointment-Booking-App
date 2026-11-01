package com.example.bookings

import com.example.bookings.core.XlsxExporter
import com.example.bookings.data.Appointment
import com.example.bookings.data.ApptStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxExporterTest {
    private val bigId = 6123456789012345L

    private fun appt(id: Long, name: String?, status: ApptStatus, withSlot: Boolean, msg: String = "m"): Appointment {
        val start = LocalDate.of(2026, 11, 5).atTime(10, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        return Appointment(
            id = id, telegramUserId = bigId + id, chatId = -1001234567890L, username = "user$id", firstName = "A", lastName = "B",
            clientName = name, phone = "0555123456", serviceId = null, serviceText = "كشف", serviceName = "كشف عام", durationMin = 30,
            reqDate = "2026-11-05", reqTime = "10:30", reqText = "غدا 10:30", notes = "ملاحظة <&>", slotStart = if (withSlot) start else null,
            slotEnd = if (withSlot) start + 30 * 60_000L else null, originalMessage = msg, messageId = id, createdAt = 1_793_000_000_000L,
            updatedAt = 1_793_000_000_000L, status = status, lastMessageText = null, lastMessageAt = null,
        )
    }

    private fun export(list: List<Appointment>): Map<String, String> {
        val out = ByteArrayOutputStream()
        XlsxExporter.write(out, list, ZoneOffset.UTC)
        val parts = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                parts[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return parts
    }

    @Test fun packageHasRequiredPartsAndAllXmlIsWellFormed() {
        val parts = export(listOf(appt(1, "أحمد محمد", ApptStatus.CONFIRMED, true)))
        assertEquals(
            listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"),
            parts.keys.toList(),
        )
        val f = DocumentBuilderFactory.newInstance()
        parts.forEach { (_, xml) -> f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))) }
        assertTrue(parts.getValue("xl/workbook.xml").contains("Appointments"))
    }

    @Test fun arabicTextExactIdsAndPhoneArePreserved() {
        val s = export(listOf(appt(1, "أحمد محمد", ApptStatus.CONFIRMED, true))).getValue("xl/worksheets/sheet1.xml")
        assertTrue(s.contains(">أحمد محمد<"))
        assertTrue(s.contains(">كشف عام<"))
        assertTrue(s.contains(">${bigId + 1}<"))
        assertTrue(s.contains(">-1001234567890<"))
        assertTrue(s.contains(">0555123456<"))            // leading zero kept
        assertTrue(s.contains(">2026-11-05<"))
        assertTrue(s.contains(">10:30<"))
        assertTrue(s.contains(">11:00<"))                 // end time
        assertTrue(s.contains("ملاحظة &lt;&amp;&gt;"))
        assertTrue(!s.contains("E+"))
    }

    @Test fun headersFilterAndFrozenPane() {
        val s = export(listOf(appt(1, "A", ApptStatus.PENDING, false), appt(2, "B", ApptStatus.CONFIRMED, true)))
            .getValue("xl/worksheets/sheet1.xml")
        assertTrue(s.contains("<autoFilter ref=\"A1:P3\"/>"))
        assertTrue(s.contains("state=\"frozen\""))
        assertTrue(s.contains(">Telegram User ID<"))
        assertTrue(s.contains(">Duration (min)<"))
        assertTrue(s.contains("s=\"1\""))
    }

    @Test fun emptyListStillProducesAValidSheet() {
        val s = export(emptyList()).getValue("xl/worksheets/sheet1.xml")
        assertTrue(s.contains("<autoFilter ref=\"A1:P1\"/>"))
    }
}
