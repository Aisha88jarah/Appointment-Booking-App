package com.example.bookings.core

import com.example.bookings.data.Appointment
import com.example.bookings.data.ApptStatus
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Opening hours of one day, in minutes from midnight. */
data class WorkDay(val openMin: Int, val closeMin: Int)

/** Weekly opening hours. Keys are java.time.DayOfWeek values: 1 = Monday ... 7 = Sunday; null = closed. */
object WorkWeek {
    fun default(): Map<Int, WorkDay?> = (1..7).associateWith { d -> if (d in 1..5) WorkDay(9 * 60, 17 * 60) else null }

    fun hm(min: Int): String = String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60)

    fun parseHm(s: String): Int? {
        val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$").find(TextUtil.clean(s)) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        val total = h * 60 + mi
        return if (h in 0..24 && mi in 0..59 && total <= 1440) total else null
    }

    /** "1=09:00-17:00;2=09:00-17:00;...;6=-;7=-" */
    fun encode(m: Map<Int, WorkDay?>): String = (1..7).joinToString(";") { d ->
        val w = m[d]
        "$d=" + (if (w == null) "-" else hm(w.openMin) + "-" + hm(w.closeMin))
    }

    fun decode(s: String): Map<Int, WorkDay?> {
        val out = default().toMutableMap()
        for (part in s.split(';')) {
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) continue
            val d = kv[0].trim().toIntOrNull() ?: continue
            if (d !in 1..7) continue
            val v = kv[1].trim()
            if (v == "-") { out[d] = null; continue }
            val ab = v.split('-')
            if (ab.size != 2) continue
            val a = parseHm(ab[0])
            val b = parseHm(ab[1])
            if (a != null && b != null && a < b) out[d] = WorkDay(a, b)
        }
        return out
    }
}

/** A confirmed appointment on one day, in minutes from that day's midnight. */
data class Busy(val startMin: Int, val endMin: Int)

object Slots {
    /** Confirmed appointments overlapping [date], optionally ignoring one appointment (the one being edited). */
    fun busyOn(date: LocalDate, appts: List<Appointment>, zone: ZoneId, excludeId: Long? = null): List<Busy> {
        val base = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return appts.mapNotNull { a ->
            val s = a.slotStart
            val e = a.slotEnd
            if (a.status != ApptStatus.CONFIRMED || s == null || e == null || a.id == excludeId) {
                null
            } else {
                val sm = ((s - base) / 60_000L).coerceIn(-100_000L, 100_000L).toInt()
                val em = ((e - base) / 60_000L).coerceIn(-100_000L, 100_000L).toInt()
                if (em <= 0 || sm >= 1440) null else Busy(sm, em)
            }
        }
    }

    /** True when an appointment [start, start+duration) is closer than [buffer] minutes to a busy one. */
    fun conflicts(start: Int, duration: Int, buffer: Int, busy: List<Busy>): Boolean =
        busy.any { start < it.endMin + buffer && it.startMin < start + duration + buffer }

    /**
     * Possible start times (minutes) for one day.
     *  - [step]: how often an appointment may start (grid from opening time).
     *  - [buffer]: gap kept before and after every confirmed appointment.
     *  - [packTight]: additionally offer the first free time right after an appointment (end + buffer).
     *  - [earliest]: nothing before this minute (used for "today").
     */
    fun freeStarts(
        day: WorkDay?, duration: Int, step: Int, buffer: Int, packTight: Boolean,
        busy: List<Busy>, earliest: Int = 0,
    ): List<Int> {
        if (day == null || duration <= 0 || step <= 0) return emptyList()
        val candidates = sortedSetOf<Int>()
        var t = day.openMin
        while (t + duration <= day.closeMin) {
            candidates.add(t)
            t += step
        }
        if (packTight) {
            for (b in busy) {
                val c = b.endMin + buffer
                if (c >= day.openMin && c + duration <= day.closeMin) candidates.add(c)
            }
        }
        return candidates.filter { it >= earliest && !conflicts(it, duration, buffer, busy) }
    }
}
