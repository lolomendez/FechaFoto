package com.fechafoto

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Extrae la fecha del nombre. Soporta, entre otros:
 *  IMG_20230415_142310.jpg · PXL_20230415_142310123.jpg · VID_20230415_142310.mp4
 *  2023-04-15 14.23.10.jpg · Screenshot_2023-04-15-14-23-10.png
 *  IMG-20230415-WA0001.jpg (solo día) · 1681567390123.jpg (epoch en ms)
 */
object NameDateParser {
    private val ymd = Regex(
        """(?<!\d)((?:19|20)\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])""" +
            """(?:[-_. T]?([01]\d|2[0-3])[-_.]?([0-5]\d)[-_.]?([0-5]\d))?(?!\d)"""
    )
    private val epochMs = Regex("""(?<!\d)(1\d{12})(?!\d)""")

    fun parse(fileName: String): NameDate? {
        val base = fileName.substringBeforeLast('.')
        for (m in ymd.findAll(base)) {
            val g = m.groupValues
            try {
                val date = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
                return if (g[4].isNotEmpty())
                    NameDate(date.atTime(g[4].toInt(), g[5].toInt(), g[6].toInt()), true)
                else NameDate(date.atTime(12, 0), false)
            } catch (_: DateTimeException) { /* fecha imposible, probar la siguiente */ }
        }
        epochMs.find(base)?.let { m ->
            val dt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(m.groupValues[1].toLong()), ZoneId.systemDefault()
            )
            if (dt.year in 2000..2100) return NameDate(dt, true)
        }
        return null
    }
}
