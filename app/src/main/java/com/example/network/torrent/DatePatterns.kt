package com.example.network.torrent

import java.text.SimpleDateFormat
import java.util.*

object DatePatterns {

    private val MONTH_NAMES_SHORT = arrayOf(
        "jan", "feb", "mar", "apr", "may", "jun",
        "jul", "aug", "sep", "oct", "nov", "dec"
    )

    private val MONTH_NAMES_FULL = arrayOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"
    )

    data class DateComponents(val year: Int, val month: Int, val day: Int)

    /**
     * Formats targetDateMs as "YY MM DD"
     */
    fun formatDateStr(timeMs: Long?): String {
        if (timeMs == null || timeMs <= 0L) return ""
        val sdf = SimpleDateFormat("yy MM dd", Locale.US)
        return sdf.format(Date(timeMs))
    }

    /**
     * Parse date components from yyyy-mm-dd / yyyy/mm/dd / yyyy.mm.dd or yy mm dd
     */
    fun parseDateComponents(dateStr: String?): DateComponents? {
        if (dateStr.isNullOrBlank()) return null
        val clean = dateStr.trim()
        val parts = clean.split(Regex("[-/.\\s]+")).filter { it.isNotBlank() }
        if (parts.size >= 3) {
            val p0 = parts[0].toIntOrNull() ?: return null
            val p1 = parts[1].toIntOrNull() ?: return null
            val p2 = parts[2].toIntOrNull() ?: return null

            return if (p0 > 1000) {
                // yyyy MM dd
                DateComponents(p0, p1, p2)
            } else if (p2 > 1000) {
                // dd MM yyyy
                DateComponents(p2, p1, p0)
            } else {
                // 2-digit year: yy MM dd
                val fullYear = if (p0 < 70) 2000 + p0 else 1900 + p0
                DateComponents(fullYear, p1, p2)
            }
        }
        return null
    }

    private fun componentsFromCalendar(cal: Calendar): DateComponents {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return DateComponents(y, m, d)
    }

    /**
     * Generates all date pattern strings according to Section 5.6.
     * Evaluates for both UTC and Local timezone from targetDateMs,
     * as well as any explicit date string.
     */
    fun generatePatterns(
        date: String? = null,
        dateStr: String? = null,
        targetDateMs: Long? = null
    ): Set<String> {
        val componentsSet = mutableSetOf<DateComponents>()

        if (targetDateMs != null && targetDateMs > 0L) {
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = targetDateMs
            }
            componentsSet.add(componentsFromCalendar(utcCal))

            val localCal = Calendar.getInstance().apply {
                timeInMillis = targetDateMs
            }
            componentsSet.add(componentsFromCalendar(localCal))
        }

        parseDateComponents(date)?.let { componentsSet.add(it) }
        parseDateComponents(dateStr)?.let { componentsSet.add(it) }

        val patterns = mutableSetOf<String>()
        for (comp in componentsSet) {
            val yyyy = String.format(Locale.US, "%04d", comp.year)
            val yy = String.format(Locale.US, "%02d", comp.year % 100)
            val mm = String.format(Locale.US, "%02d", comp.month)
            val dd = String.format(Locale.US, "%02d", comp.day)
            val dNum = comp.day.toString()

            val monthIndex = (comp.month - 1).coerceIn(0, 11)
            val mShort = MONTH_NAMES_SHORT[monthIndex]
            val mFull = MONTH_NAMES_FULL[monthIndex]

            // 1-17 numeric variants
            patterns.add("$yy $mm $dd")
            patterns.add("$yy.$mm.$dd")
            patterns.add("$yy-$mm-$dd")
            patterns.add("$yy$mm$dd")
            patterns.add("$yyyy.$mm.$dd")
            patterns.add("$yyyy-$mm-$dd")
            patterns.add("$yyyy $mm $dd")
            patterns.add("$dd.$mm.$yyyy")
            patterns.add("$dd-$mm-$yyyy")
            patterns.add("$dd $mm $yyyy")
            patterns.add("$dd.$mm.$yy")
            patterns.add("$dd-$mm-$yy")
            patterns.add("$dd $mm $yy")
            patterns.add("$mm.$dd.$yyyy")
            patterns.add("$mm-$dd-$yyyy")
            patterns.add("$mm.$dd.$yy")
            patterns.add("$mm-$dd-$yy")

            // 18-27 short month variants
            patterns.add("$mShort $dd $yyyy")
            patterns.add("$mShort $dNum $yyyy")
            patterns.add("$dd $mShort $yyyy")
            patterns.add("$dNum $mShort $yyyy")
            patterns.add("$mShort $dd $yy")
            patterns.add("$mShort $dNum $yy")
            patterns.add("$mShort $dd")
            patterns.add("$mShort $dNum")
            patterns.add("$dd $mShort")
            patterns.add("$dNum $mShort")

            // 28-31 full month variants
            patterns.add("$mFull $dd $yyyy")
            patterns.add("$mFull $dNum $yyyy")
            patterns.add("$dd $mFull $yyyy")
            patterns.add("$dNum $mFull $yyyy")
        }

        return patterns.map { it.lowercase() }.toSet()
    }

    /**
     * Checks if a title (case-insensitive) contains at least one of the accepted date patterns.
     */
    fun matchesAnyDatePattern(title: String, patterns: Set<String>): Boolean {
        if (patterns.isEmpty()) return true
        val lower = title.lowercase()
        for (pattern in patterns) {
            if (lower.contains(pattern)) {
                return true
            }
        }
        return false
    }
}
