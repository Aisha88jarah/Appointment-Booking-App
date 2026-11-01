package com.example.bookings.core

import com.example.bookings.data.Appointment
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dependency-free XLSX writer (one sheet). Text is UTF-8 inline strings (Arabic renders correctly);
 * Telegram IDs and phone numbers are text cells so they are never turned into floating point numbers.
 */
object XlsxExporter {
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val STAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    const val SHEET = "Appointments"

    private val HEADERS = listOf(
        "Number", "Client Name", "Service", "Date", "Start Time", "End Time", "Duration (min)", "Status",
        "Phone", "Telegram User ID", "Telegram Chat ID", "Telegram Username", "Requested Date/Time",
        "Notes", "Original Message", "Received At",
    )
    private val WIDTHS = listOf(9, 26, 22, 13, 11, 11, 15, 14, 18, 22, 22, 20, 22, 36, 50, 18)
    // 0 default, 2 text ("@"), 3 wrapped text
    private val STYLES = listOf(0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 0, 0, 3, 3, 0)

    fun write(out: OutputStream, list: List<Appointment>, zone: ZoneId = ZoneId.systemDefault()) {
        val rows = list.mapIndexed { i, a ->
            val s = a.slotStart?.let { Instant.ofEpochMilli(it).atZone(zone) }
            val e = a.slotEnd?.let { Instant.ofEpochMilli(it).atZone(zone) }
            listOf<Any?>(
                i + 1L, a.displayName(), a.serviceName ?: a.serviceText,
                s?.format(DATE_FMT), s?.format(TIME_FMT), e?.format(TIME_FMT),
                a.durationMin?.toLong(), a.status.name,
                a.phone, a.telegramUserId.toString(), a.chatId.toString(), a.username,
                a.reqText ?: listOfNotNull(a.reqDate, a.reqTime).joinToString(" ").ifEmpty { null },
                a.notes, a.originalMessage,
                Instant.ofEpochMilli(a.createdAt).atZone(zone).format(STAMP_FMT),
            )
        }
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", WORKBOOK)
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLESHEET)
            put("xl/worksheets/sheet1.xml", sheetXml(rows))
        }
    }

    private fun colName(i: Int) = ('A' + i).toString()

    internal fun sheetXml(rows: List<List<Any?>>): String {
        val lastCol = colName(HEADERS.size - 1)
        val lastRow = rows.size + 1
        val sb = StringBuilder(4096)
        sb.append(XML_HEAD)
        sb.append("<worksheet xmlns=\"$NS\">")
        sb.append("<dimension ref=\"A1:$lastCol$lastRow\"/>")
        sb.append("<sheetViews><sheetView tabSelected=\"1\" workbookViewId=\"0\">")
            .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            .append("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView></sheetViews>")
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        sb.append("<cols>")
        WIDTHS.forEachIndexed { i, w -> sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        sb.append("</cols><sheetData>")
        sb.append("<row r=\"1\" ht=\"20\" customHeight=\"1\">")
        HEADERS.forEachIndexed { c, h ->
            sb.append("<c r=\"${colName(c)}1\" s=\"1\" t=\"inlineStr\"><is><t>${esc(h)}</t></is></c>")
        }
        sb.append("</row>")
        rows.forEachIndexed { r, row ->
            val rn = r + 2
            sb.append("<row r=\"$rn\">")
            row.forEachIndexed { c, v ->
                when (v) {
                    null -> {}
                    is Long -> sb.append("<c r=\"${colName(c)}$rn\"><v>$v</v></c>")
                    else -> {
                        val s = v.toString()
                        if (s.isNotEmpty()) {
                            sb.append("<c r=\"${colName(c)}$rn\" s=\"${STYLES[c]}\" t=\"inlineStr\">")
                                .append("<is><t xml:space=\"preserve\">${esc(s)}</t></is></c>")
                        }
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        sb.append("<autoFilter ref=\"A1:$lastCol$lastRow\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 0x20 && ch != '\n' && ch != '\t' && ch != '\r' -> {}
                ch == '\uFFFE' || ch == '\uFFFF' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val CONTENT_TYPES = XML_HEAD +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private val ROOT_RELS = XML_HEAD +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private val WORKBOOK = XML_HEAD +
        "<workbook xmlns=\"$NS\" xmlns:r=\"$REL_NS\"><bookViews><workbookView/></bookViews><sheets>" +
        "<sheet name=\"$SHEET\" sheetId=\"1\" r:id=\"rId1\"/>" +
        "</sheets></workbook>"

    private val WORKBOOK_RELS = XML_HEAD +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    // cellXfs: 0 default, 1 bold header, 2 text ("@"), 3 wrapped text
    private val STYLESHEET = XML_HEAD +
        "<styleSheet xmlns=\"$NS\">" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDDEBF7\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"4\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"
}
