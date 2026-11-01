package com.example.bookings.core

import com.example.bookings.data.Service
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class ParsedRequest(
    val name: String?,
    val phone: String?,
    val service: String?,
    val date: LocalDate?,
    val time: LocalTime?,
    val whenText: String?,    // date/time exactly as the client wrote it (labeled values only)
    val notes: String?,
    val labeled: Boolean,
) {
    companion object {
        val EMPTY = ParsedRequest(null, null, null, null, null, null, null, false)
    }
}

/**
 * Reads appointment requests written in Arabic or English, e.g.
 *   الاسم: أحمد / الخدمة: كشف / التاريخ: غدا / الوقت: 10:30 ص
 *   Name: John / Service: Consultation / Date: 2026-11-05 / Time: 3:30 pm
 * Free text such as "tomorrow at 10:30" is also understood. Nothing is guessed: whatever
 * cannot be read stays null and is shown to the administrator as the client wrote it.
 */
object RequestParser {
    private enum class Field { NAME, PHONE, SERVICE, DATE, TIME, WHEN, NOTES }

    private val labels: Map<Field, List<String>> = mapOf(
        Field.NAME to listOf(
            "اسم المريض", "اسم العميل", "اسم الزائر", "الاسم الكامل", "الاسم", "اسم", "المريض", "العميل",
            "full name", "patient name", "client name", "customer name", "name", "patient", "client",
        ),
        Field.PHONE to listOf(
            "رقم الهاتف", "رقم الجوال", "رقم الموبايل", "رقم التواصل", "الهاتف", "الجوال", "الموبايل", "واتساب", "رقم",
            "phone number", "mobile", "phone", "whatsapp", "tel",
        ),
        Field.SERVICE to listOf(
            "نوع الخدمة", "نوع الموعد", "نوع الاستشارة", "الخدمة", "خدمة", "الاستشارة", "الكشف",
            "appointment type", "service", "treatment", "type",
        ),
        Field.DATE to listOf("تاريخ الموعد", "التاريخ", "تاريخ", "اليوم", "date", "day"),
        Field.TIME to listOf("وقت الموعد", "الوقت", "وقت", "الساعة", "time"),
        Field.WHEN to listOf("التاريخ والوقت", "الموعد", "موعد", "date and time", "appointment", "datetime", "when"),
        Field.NOTES to listOf(
            "ملاحظات", "ملاحظة", "سبب الزيارة", "السبب", "التفاصيل",
            "notes", "note", "reason for visit", "reason", "comments", "comment", "details",
        ),
    ).mapValues { (_, v) -> v.map { TextUtil.key(it) } }

    private val labelToField: Map<String, Field> = labels.flatMap { (f, l) -> l.map { it to f } }.toMap()
    private val alternation: String =
        labelToField.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val colonLabel = Regex("(?<![\\p{L}\\p{N}])($alternation)\\s*[:：=＝]")

    private class Seg(val field: Field, val value: String)

    private fun scan(line: String): List<Seg>? {
        val k = TextUtil.key(line)
        val hits = colonLabel.findAll(k).toList()
        if (hits.isEmpty()) return null
        return hits.mapIndexed { i, m ->
            val end = if (i + 1 < hits.size) hits[i + 1].range.first else line.length
            Seg(labelToField.getValue(m.groupValues[1]), TextUtil.trimValue(line.substring(m.range.last + 1, end)))
        }
    }

    fun parse(raw: String, today: LocalDate): ParsedRequest {
        val cleaned = TextUtil.clean(raw)
        val lines = cleaned.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val found = linkedMapOf<Field, MutableList<String>>()
        var current: Field? = null
        var currentEmpty = false
        for (line in lines) {
            val segs = scan(line)
            if (segs == null) {
                val f = current
                // value on the next line, or a multi-line note
                if (f != null && (f == Field.NOTES || currentEmpty)) {
                    found.getOrPut(f) { mutableListOf() }.add(TextUtil.trimValue(line))
                    currentEmpty = false
                }
                continue
            }
            for (seg in segs) {
                if (seg.value.isNotEmpty()) found.getOrPut(seg.field) { mutableListOf() }.add(seg.value)
            }
            current = segs.last().field
            currentEmpty = segs.last().value.isEmpty()
        }

        fun last(f: Field): String? = found[f]?.lastOrNull()?.let { TextUtil.collapse(it) }?.takeIf { it.isNotEmpty() }

        val name = last(Field.NAME)
        val rawPhone = last(Field.PHONE)
        val phone = rawPhone?.let { cleanPhone(it) }
        val service = last(Field.SERVICE)
        val dateText = last(Field.DATE)
        val timeText = last(Field.TIME)
        val whenText = last(Field.WHEN)
        val notes = found[Field.NOTES]?.filter { it.isNotEmpty() }?.joinToString("\n")?.takeIf { it.isNotEmpty() }

        var date = DateParser.parse(dateText ?: whenText, today)
        var time = if (timeText != null) TimeParser.parse(timeText, labeled = true) else TimeParser.parse(whenText, labeled = false)

        // Free text ("tomorrow at 10:30") when the client used no date/time label at all.
        if (dateText == null && timeText == null && whenText == null) {
            val free = if (rawPhone != null) cleaned.replace(rawPhone, " ") else cleaned
            date = DateParser.parse(free, today)
            time = TimeParser.parse(free, labeled = false)
        }

        val shown = listOfNotNull(dateText, timeText, whenText).joinToString(" ").ifEmpty { null }
        return ParsedRequest(name, phone, service, date, time, shown, notes, found.isNotEmpty())
    }

    private fun cleanPhone(v: String): String? {
        val d = v.filter { it.isDigit() || it == '+' }
        return if (d.count { it.isDigit() } >= 6) d else null
    }
}

object DateParser {
    private val iso = Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)")
    private val dmy = Regex("(?<!\\d)(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}|\\d{2})(?!\\d)")
    private val dm = Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?![\\d/:])")

    private val monthNames: List<Pair<String, Int>> = listOf(
        "january" to 1, "jan" to 1, "كانون الثاني" to 1, "يناير" to 1,
        "february" to 2, "feb" to 2, "شباط" to 2, "فبراير" to 2,
        "march" to 3, "mar" to 3, "آذار" to 3, "مارس" to 3,
        "april" to 4, "apr" to 4, "نيسان" to 4, "أبريل" to 4, "ابريل" to 4,
        "may" to 5, "أيار" to 5, "مايو" to 5,
        "june" to 6, "jun" to 6, "حزيران" to 6, "يونيو" to 6,
        "july" to 7, "jul" to 7, "تموز" to 7, "يوليو" to 7,
        "august" to 8, "aug" to 8, "آب" to 8, "أغسطس" to 8,
        "september" to 9, "sept" to 9, "sep" to 9, "أيلول" to 9, "سبتمبر" to 9,
        "october" to 10, "oct" to 10, "تشرين الأول" to 10, "أكتوبر" to 10,
        "november" to 11, "nov" to 11, "تشرين الثاني" to 11, "نوفمبر" to 11,
        "december" to 12, "dec" to 12, "كانون الأول" to 12, "ديسمبر" to 12,
    ).map { TextUtil.key(it.first) to it.second }.sortedByDescending { it.first.length }

    private val weekdays: List<Pair<String, DayOfWeek>> = listOf(
        "الأحد" to DayOfWeek.SUNDAY, "أحد" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
        "الاثنين" to DayOfWeek.MONDAY, "الإثنين" to DayOfWeek.MONDAY, "اثنين" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
        "الثلاثاء" to DayOfWeek.TUESDAY, "ثلاثاء" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "الأربعاء" to DayOfWeek.WEDNESDAY, "أربعاء" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "الخميس" to DayOfWeek.THURSDAY, "خميس" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
        "الجمعة" to DayOfWeek.FRIDAY, "جمعة" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
        "السبت" to DayOfWeek.SATURDAY, "سبت" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
    ).map { TextUtil.key(it.first) to it.second }

    private val afterTomorrow = Regex("(?<![\\p{L}])(?:بعد غد|بعد بكره|بعد بكرا|day after tomorrow)(?![\\p{L}])")
    private val tomorrow = Regex("(?<![\\p{L}])(?:غدا|غدوه|بكره|بكرا|tomorrow|tomorow|tmrw)(?![\\p{L}])")
    private val todayRe = Regex("(?<![\\p{L}])(?:اليوم|today)(?![\\p{L}])")

    /** Day/month order is day first (31/12/2026). `today` is the day the client wrote the message. */
    fun parse(text: String?, today: LocalDate): LocalDate? {
        if (text.isNullOrBlank()) return null
        val k = TextUtil.key(TextUtil.clean(text))
        return numeric(k, today) ?: byMonthName(k, today) ?: relative(k, today) ?: weekday(k, today)
    }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (e: DateTimeException) { null }

    private fun dayMonth(a: Int, b: Int, year: Int): LocalDate? =
        if (b > 12 && a <= 12) safe(year, a, b) else safe(year, b, a)   // 12/31 is read as month/day

    private fun yearless(day: Int, month: Int, today: LocalDate): LocalDate? {
        val d = safe(today.year, month, day) ?: return null
        return if (d.isBefore(today)) safe(today.year + 1, month, day) else d
    }

    private fun numeric(k: String, today: LocalDate): LocalDate? {
        val a = iso.find(k)
        if (a != null) return safe(a.groupValues[1].toInt(), a.groupValues[2].toInt(), a.groupValues[3].toInt())
        val b = dmy.find(k)
        if (b != null) {
            var y = b.groupValues[3].toInt()
            if (y < 100) y += 2000
            return dayMonth(b.groupValues[1].toInt(), b.groupValues[2].toInt(), y)
        }
        val c = dm.find(k)
        if (c != null) {
            val x = c.groupValues[1].toInt()
            val y = c.groupValues[2].toInt()
            return if (y > 12 && x <= 12) yearless(y, x, today) else yearless(x, y, today)
        }
        return null
    }

    private fun byMonthName(k: String, today: LocalDate): LocalDate? {
        for ((name, month) in monthNames) {
            val m = Regex("(?<![\\p{L}])" + Regex.escape(name) + "(?![\\p{L}])").find(k) ?: continue
            val before = k.substring(0, m.range.first)
            val after = k.substring(m.range.last + 1)
            val dayBefore = Regex("(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?\\s*(?:of|من)?\\s*$").find(before)
            val dayAfter = Regex("^\\s*(?:the\\s*)?(\\d{1,2})(?!\\d)").find(after)
            val day = (dayBefore ?: dayAfter)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val year = Regex("(?<!\\d)(20\\d{2})(?!\\d)").find(k)?.groupValues?.get(1)?.toIntOrNull()
            return if (year != null) safe(year, month, day) else yearless(day, month, today)
        }
        return null
    }

    private fun relative(k: String, today: LocalDate): LocalDate? = when {
        afterTomorrow.containsMatchIn(k) -> today.plusDays(2)
        tomorrow.containsMatchIn(k) -> today.plusDays(1)
        todayRe.containsMatchIn(k) -> today
        else -> null
    }

    /** A weekday name means its next occurrence (never today). */
    private fun weekday(k: String, today: LocalDate): LocalDate? {
        for ((name, dow) in weekdays) {
            if (Regex("(?<![\\p{L}])" + Regex.escape(name) + "(?![\\p{L}])").containsMatchIn(k)) {
                var diff = (dow.value - today.dayOfWeek.value + 7) % 7
                if (diff == 0) diff = 7
                return today.plusDays(diff.toLong())
            }
        }
        return null
    }
}

object TimeParser {
    private val re = Regex(
        "(?<![\\d:.])(\\d{1,2})(?:([:.])(\\d{2}))?\\s*" +
            "(am|a\\.m\\.?|pm|p\\.m\\.?|صباحا|صباح|فجرا|مساء|مساءا|عصرا|ظهرا|ليلا|ص|م)?(?![\\p{L}\\d])"
    )
    private val hourPrefix = Regex("(?:الساعه|at|@)\\s*$")
    private val amWords = setOf("am", "a.m", "a.m.", "ص", "صباحا", "صباح", "فجرا")
    private val pmWords = setOf("pm", "p.m", "p.m.", "م", "مساء", "مساءا", "عصرا")

    /**
     * labeled = the text is the value of a "Time:" label, so a bare "10" is accepted.
     * In free text a time needs ':' minutes or am/pm. Hours 1-6 without am/pm are not guessed.
     */
    fun parse(text: String?, labeled: Boolean): LocalTime? {
        if (text.isNullOrBlank()) return null
        val k = TextUtil.key(TextUtil.clean(text))
        for (m in re.findAll(k)) {
            val hour = m.groupValues[1].toInt()
            val sep = m.groupValues[2]
            val min = m.groupValues[3].ifEmpty { "0" }.toInt()
            val mer = m.groupValues[4]
            if (!labeled && sep != ":" && mer.isEmpty()) {
                val prefixed = hourPrefix.containsMatchIn(k.substring(0, m.range.first))
                if (!prefixed || sep.isNotEmpty()) continue
            }
            val t = resolve(hour, min, mer)
            if (t != null) return t
        }
        return null
    }

    private fun resolve(hour: Int, min: Int, mer: String): LocalTime? {
        if (min > 59 || hour > 23) return null
        val h: Int = when {
            mer in amWords -> if (hour !in 1..12) return null else if (hour == 12) 0 else hour
            mer in pmWords -> if (hour !in 1..12) return null else if (hour == 12) 12 else hour + 12
            mer == "ظهرا" -> if (hour !in 1..12) return null else if (hour >= 11) hour else hour + 12
            mer == "ليلا" -> if (hour !in 1..12) return null else if (hour == 12) 0 else if (hour >= 6) hour + 12 else hour
            hour in 1..6 -> return null      // ambiguous (3:30 am or pm?) - do not guess
            else -> hour
        }
        return LocalTime.of(h, min)
    }
}

object ServiceMatcher {
    /** Returns the service the client named, or null when none / more than one fits. */
    fun match(text: String?, services: List<Service>): Service? {
        if (text.isNullOrBlank()) return null
        val t = TextUtil.norm(text)
        val active = services.filter { it.active }
        active.firstOrNull { TextUtil.norm(it.name) == t }?.let { return it }
        val hits = active.filter {
            val n = TextUtil.norm(it.name)
            n.length >= 3 && (t.contains(n) || (t.length >= 3 && n.contains(t)))
        }
        return hits.singleOrNull()
    }
}
