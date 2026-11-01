package com.example.bookings.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Templates {
    val KEYS = listOf("name", "service", "date", "time", "duration", "business", "address")
    private val placeholder = Regex("\\{([A-Za-z_]+)\\}")

    /**
     * Fills {placeholders}. A line whose placeholders are all empty is dropped, so a message never
     * contains "Service: " or an empty address line.
     */
    fun render(template: String, values: Map<String, String>): String {
        val out = mutableListOf<String>()
        for (line in template.split('\n')) {
            val keys = placeholder.findAll(line).map { it.groupValues[1] }.filter { it in values }.toList()
            if (keys.isNotEmpty() && keys.all { values[it].isNullOrBlank() }) continue
            var l = line
            for ((k, v) in values) l = l.replace("{$k}", v)
            out.add(l)
        }
        return out.joinToString("\n").trim()
    }

    /** Placeholders still present in the (edited) text, e.g. a typo like {nmae}. */
    fun unresolved(text: String): List<String> =
        placeholder.findAll(text).map { it.value }.distinct().toList()

    fun values(
        name: String, service: String?, durationMin: Int?, date: LocalDate?, time: LocalTime?,
        business: String, address: String, locale: Locale,
    ): Map<String, String> = mapOf(
        "name" to name,
        "service" to (service ?: ""),
        "date" to (date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)) ?: ""),
        "time" to (time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) ?: ""),
        "duration" to (durationMin?.toString() ?: ""),
        "business" to business,
        "address" to address,
    )
}
